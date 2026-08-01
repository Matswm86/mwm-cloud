package no.mwmai.mwmcloud.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import no.mwmai.mwmcloud.settings.BackupSchedule
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.R
import no.mwmai.mwmcloud.data.media.CategoryMode
import no.mwmai.mwmcloud.data.media.LocalFile
import no.mwmai.mwmcloud.data.media.MediaCategory
import no.mwmai.mwmcloud.data.media.Selection
import no.mwmai.mwmcloud.net.Content
import no.mwmai.mwmcloud.net.TransportException

/**
 * Uploads everything selected that is not already on the box.
 *
 * WorkManager rather than a long-lived service: Android 15 caps a dataSync
 * foreground service at six hours per twenty-four, so a large first backup has to
 * survive being stopped and resumed rather than assume one uninterrupted run.
 *
 * Nothing here deletes or modifies anything on the phone. Files are opened for
 * reading only.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /**
     * A backup job must never be able to kill the app. Anything unexpected is
     * reported as a failed run with a message, not thrown into the process.
     */
    override suspend fun doWork(): Result = try {
        upload()
    } catch (e: Throwable) {
        Result.failure(error(applicationContext.getString(R.string.err_generic)))
    }

    private suspend fun upload(): Result {
        val creds = Graph.credentialStore(applicationContext).current()
            ?: return Result.failure(error("Ingen tilkobling er satt opp."))

        val categories = inputData.getStringArray(KEY_CATEGORIES)
            ?.mapNotNull { runCatching { MediaCategory.valueOf(it) }.getOrNull() }
            .orEmpty()

        val transport = Graph.transport(creds)
        val ledger = Graph.ledger(applicationContext)
        val scanner = Graph.mediaScanner(applicationContext)
        val settings = Graph.settings(applicationContext)

        // One pass over the ledger beats one query per file when there are
        // thousands of them.
        val alreadyDone = ledger.uploadedKeys()

        // Files the user unticked, and the files they explicitly picked. Which of
        // the two applies is the category's mode, resolved by Selection so the
        // uploader and the interface can never disagree about what is chosen.
        val excluded = settings.currentExcluded()
        val modes = settings.currentCategoryModes()
        val included = settings.currentIncludedAll()

        val found = mutableListOf<LocalFile>()
        for (category in categories) {
            found += Selection.filter(
                files = scanner.scan(category),
                mode = modes[category] ?: CategoryMode.ALL,
                excluded = excluded,
                included = included[category].orEmpty(),
            )
        }
        // Hand-picked files and folders, which cover everything MediaStore's
        // fixed categories cannot reach. Always explicit choices, so no mode
        // applies to them.
        if (inputData.getBoolean(KEY_INCLUDE_PICKED, true)) {
            found += Graph.safScanner(applicationContext).scan(
                treeUris = settings.currentPickedFolders(),
                fileUris = settings.currentPickedFiles(),
            ).filter { it.uri.toString() !in excluded }
        }

        if (found.isEmpty()) {
            return Result.failure(error(applicationContext.getString(R.string.err_nothing_selected)))
        }

        // Distinct by remote path: a file can be reached both by category and by
        // a picked folder, and uploading it twice would double the reported work.
        val pending = found
            .distinctBy { it.remotePath }
            .filter { it.dedupeKey !in alreadyDone }

        if (pending.isEmpty()) return Result.success(progress(0, 0))

        showProgress(0, pending.size)

        var done = 0
        var failed = 0
        var lastError: TransportException? = null
        val createdDirs = mutableSetOf<String>()

        for (file in pending) {
            if (isStopped) break

            val dir = file.remotePath.substringBeforeLast('/')
            try {
                if (createdDirs.add(dir)) transport.ensureCollection(dir)

                val content = Content(file.size) {
                    applicationContext.contentResolver.openInputStream(file.uri)
                        ?: throw java.io.IOException("Could not open ${file.displayName}")
                }
                transport.put(file.remotePath, content)

                ledger.recordUploaded(
                    dedupeKey = file.dedupeKey,
                    remotePath = file.remotePath,
                    size = file.size,
                    modified = file.modified,
                    uploadedAt = System.currentTimeMillis(),
                )
                done++
            } catch (e: TransportException) {
                failed++
                lastError = e
                // Auth failures and a full box will not fix themselves, and
                // grinding through 8000 files to fail identically each time
                // wastes the user's battery and hides the real problem.
                if (!e.kind.isRetryable) break
            } catch (e: Exception) {
                failed++
            }

            setProgress(progress(done, pending.size))
            showProgress(done, pending.size)
        }

        return when {
            failed == 0 -> Result.success(progress(done, pending.size))
            // Retryable failures go back on the queue with WorkManager's backoff.
            lastError?.kind?.isRetryable == true -> Result.retry()
            else -> Result.failure(
                progress(done, pending.size).let { d ->
                    Data.Builder().putAll(d).putString(KEY_ERROR, describe(lastError)).build()
                },
            )
        }
    }

    private fun describe(e: TransportException?): String = when (e?.kind) {
        no.mwmai.mwmcloud.net.FailureKind.AUTH ->
            "Brukernavn eller passord stemmer ikke."
        no.mwmai.mwmcloud.net.FailureKind.OUT_OF_SPACE ->
            "Det er ikke mer plass igjen."
        else -> "Noe gikk galt. Prøv igjen."
    }

    private fun error(message: String) = Data.Builder().putString(KEY_ERROR, message).build()

    private fun progress(done: Int, total: Int) = Data.Builder()
        .putInt(KEY_DONE, done)
        .putInt(KEY_TOTAL, total)
        .build()

    /**
     * Promoting to a foreground service is best-effort. It legitimately fails
     * when the worker has been stopped, when the user revoked notifications, or
     * under OEM background restrictions. None of those are reasons to abandon an
     * upload that is otherwise working, and none are reasons to crash.
     */
    private suspend fun showProgress(done: Int, total: Int) {
        try {
            setForeground(foregroundInfo(done, total))
        } catch (_: Exception) {
            // Upload continues without the notification.
        }
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val ctx = applicationContext
        val manager = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.notif_channel_backup),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.notif_backing_up))
            .setContentText(ctx.getString(R.string.notif_progress, done, total))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setProgress(total.coerceAtLeast(1), done, false)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "mwmcloud-upload"

        /** Separate name, so a weekly run never cancels a backup the user just started. */
        const val PERIODIC_WORK_NAME = "mwmcloud-upload-periodic"
        const val KEY_CATEGORIES = "categories"
        const val KEY_INCLUDE_PICKED = "include_picked"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "backup"
        private const val NOTIF_ID = 4711

        /**
         * @param allowMetered set only when the user explicitly taps "back up now
         *   using mobile data". The default is wifi-only, because a first backup
         *   can be tens of gigabytes and nobody wants to discover that on a bill.
         */
        fun enqueue(
            context: Context,
            categories: Set<MediaCategory>,
            allowMetered: Boolean = false,
        ) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                        )
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInputData(inputFor(categories, includePicked = true))
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Installs, replaces or cancels the automatic backup.
         *
         * [BackupSchedule.OFF] cancels rather than scheduling something that does
         * nothing, so a user who turns it off does not keep a wakeup on the books.
         *
         * UPDATE rather than REPLACE: changing which categories run automatically
         * should not restart the clock. REPLACE would push the next run a full
         * week out every time the user opened the screen and changed their mind.
         */
        fun schedule(
            context: Context,
            schedule: BackupSchedule,
            categories: Set<MediaCategory>,
            includePicked: Boolean,
        ) {
            val manager = WorkManager.getInstance(context)
            if (schedule == BackupSchedule.OFF || (categories.isEmpty() && !includePicked)) {
                manager.cancelUniqueWork(PERIODIC_WORK_NAME)
                return
            }

            val request = PeriodicWorkRequestBuilder<UploadWorker>(schedule.hours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        // Automatic runs are wifi-only with no override. An
                        // unattended backup must never be able to spend mobile data.
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInputData(inputFor(categories, includePicked))
                .build()

            manager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }

        private fun inputFor(categories: Set<MediaCategory>, includePicked: Boolean): Data =
            Data.Builder()
                .putStringArray(KEY_CATEGORIES, categories.map { it.name }.toTypedArray())
                .putBoolean(KEY_INCLUDE_PICKED, includePicked)
                .build()
    }
}
