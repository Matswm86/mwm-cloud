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
import kotlinx.coroutines.CancellationException
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.R
import no.mwmai.mwmcloud.data.download.Downloader
import no.mwmai.mwmcloud.data.remote.RemoteFile
import no.mwmai.mwmcloud.net.TransportException

/**
 * Copies one folder from the box back onto the phone.
 *
 * The mirror of [UploadWorker], and a worker rather than a coroutine on the
 * screen for the same reason: restoring a month of holiday video is minutes of
 * work that has to survive the user switching apps or locking the phone.
 *
 * A single file the user tapped is *not* routed through here. That path is
 * immediate and visible on screen, and putting a 3 MB photo through WorkManager
 * would make it feel broken.
 *
 * Nothing on the box is modified. Every remote call is a read.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        download()
    } catch (e: CancellationException) {
        // A stopped worker is not a failed restore.
        throw e
    } catch (e: Throwable) {
        Result.failure(error(applicationContext.getString(R.string.err_generic)))
    }

    private suspend fun download(): Result {
        val creds = Graph.credentialStore(applicationContext).current()
            ?: return Result.failure(error(applicationContext.getString(R.string.err_generic)))

        val folder = inputData.getString(KEY_FOLDER)
            ?: return Result.failure(error(applicationContext.getString(R.string.err_generic)))

        val transport = Graph.transport(creds)
        val downloader = Downloader(applicationContext)

        val pending = try {
            transport.list(folder)
                .filter { !it.isCollection }
                .map { RemoteFile(it.path, it.name, it.size, it.lastModified) }
        } catch (e: TransportException) {
            return if (e.kind.isRetryable) Result.retry() else Result.failure(error(describe(e)))
        }

        if (pending.isEmpty()) return Result.success(progress(0, 0))

        showProgress(0, pending.size)

        var done = 0
        var failed = 0
        var lastError: TransportException? = null

        for (file in pending) {
            if (isStopped) break
            try {
                downloader.save(transport, file)
                // Files already on the phone count as done. They are, and a
                // second run of the same folder should read as finished rather
                // than as "0 of 300".
                done++
            } catch (e: CancellationException) {
                // Not a failed file: the worker was stopped mid-transfer.
                throw e
            } catch (e: TransportException) {
                failed++
                lastError = e
                // Auth failures will not fix themselves, and there is nothing to
                // learn from failing identically another 299 times.
                if (!e.kind.isRetryable) break
            } catch (e: Exception) {
                // Out of space on the phone, a name the filesystem rejects, a
                // revoked permission. Skip the file, keep the rest going.
                failed++
            }
            setProgress(progress(done, pending.size))
            showProgress(done, pending.size)
        }

        return when {
            failed == 0 -> Result.success(progress(done, pending.size))
            lastError?.kind?.isRetryable == true -> Result.retry()
            else -> Result.failure(
                Data.Builder()
                    .putAll(progress(done, pending.size))
                    .putString(KEY_ERROR, describe(lastError))
                    .build(),
            )
        }
    }

    private fun describe(e: TransportException?): String = applicationContext.getString(
        when (e?.kind) {
            no.mwmai.mwmcloud.net.FailureKind.AUTH -> R.string.err_auth
            no.mwmai.mwmcloud.net.FailureKind.NOT_FOUND -> R.string.err_not_found
            no.mwmai.mwmcloud.net.FailureKind.NETWORK -> R.string.err_network
            else -> R.string.err_generic
        },
    )

    private fun error(message: String) = Data.Builder().putString(KEY_ERROR, message).build()

    private fun progress(done: Int, total: Int) = Data.Builder()
        .putInt(KEY_DONE, done)
        .putInt(KEY_TOTAL, total)
        .build()

    /** Best-effort, exactly as in [UploadWorker]: no notification is not a reason to stop. */
    private suspend fun showProgress(done: Int, total: Int) {
        try {
            setForeground(foregroundInfo(done, total))
        } catch (_: Exception) {
            // The download continues without it.
        }
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.notif_channel_restore),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.notif_restoring))
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
        const val WORK_NAME = "mwmcloud-download"
        const val KEY_FOLDER = "folder"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "restore"

        /** Distinct from the upload notification, so the two never replace each other. */
        private const val NOTIF_ID = 4712

        /**
         * @param allowMetered set only when the user chose to restore on mobile
         *   data. Default is wifi-only for the same reason as upload: a month of
         *   video is gigabytes.
         */
        fun enqueue(context: Context, folder: String, allowMetered: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                        )
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInputData(Data.Builder().putString(KEY_FOLDER, folder).build())
                .build()

            // Appended, not replaced: two folders queued in a row should both be
            // restored. REPLACE would silently cancel the first one.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
