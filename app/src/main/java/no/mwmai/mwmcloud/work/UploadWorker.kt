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
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.R
import no.mwmai.mwmcloud.data.media.LocalFile
import no.mwmai.mwmcloud.data.media.MediaCategory
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

    override suspend fun doWork(): Result {
        val creds = Graph.credentialStore(applicationContext).current()
            ?: return Result.failure(error("Ingen tilkobling er satt opp."))

        val categories = inputData.getStringArray(KEY_CATEGORIES)
            ?.mapNotNull { runCatching { MediaCategory.valueOf(it) }.getOrNull() }
            ?: return Result.failure(error("Ingen mapper er valgt."))

        val transport = Graph.transport(creds)
        val ledger = Graph.ledger(applicationContext)
        val scanner = Graph.mediaScanner(applicationContext)

        // One pass over the ledger beats one query per file when there are
        // thousands of them.
        val alreadyDone = ledger.uploadedKeys()

        val pending = mutableListOf<LocalFile>()
        for (category in categories) {
            pending += scanner.scan(category).filter { it.dedupeKey !in alreadyDone }
        }

        if (pending.isEmpty()) return Result.success(progress(0, 0))

        setForeground(foregroundInfo(0, pending.size))

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
            setForeground(foregroundInfo(done, pending.size))
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
        const val KEY_CATEGORIES = "categories"
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
                .setInputData(
                    Data.Builder()
                        .putStringArray(KEY_CATEGORIES, categories.map { it.name }.toTypedArray())
                        .build(),
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
