package space.nicart.watchbox.download

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import space.nicart.watchbox.R
import space.nicart.watchbox.WatchBoxApplication

/**
 * Keeps FFmpeg downloads running, and visible, while the app is not in front.
 *
 * Media3's own `DownloadService` covers its queue, but an ffmpeg session runs in this process and
 * that service knows nothing about it - so a remuxed download had no foreground service of its
 * own. Two symptoms followed from that single gap:
 *
 *  * Progress froze when the screen went off. With nothing holding the process in the foreground,
 *    Android throttled it and stopped scheduling the work that reports progress. The transfer
 *    carried on, so the download appeared stuck and then finished all at once on waking.
 *  * There was no notification. Media3 posts one for its own downloads; nothing did for these.
 *
 * The service holds no state. It is started when the first remux begins and stopped when the last
 * one ends, and it reads what to display from the controller so there is one source of truth for
 * what is running.
 */
@UnstableApi
class FfmpegDownloadService : Service() {

    private val controller by lazy {
        (application as WatchBoxApplication).container.downloadController
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaDownloadService.ensureChannel(this)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(intent?.getStringExtra(EXTRA_TITLE)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        // Not sticky. A restarted service would have no session to supervise - an ffmpeg
        // invocation dies with the process and cannot be resumed - so bringing it back would
        // show a notification for work that is not happening.
        return START_NOT_STICKY
    }

    /**
     * The progress notification.
     *
     * Indeterminate rather than a percentage, and deliberately: ffmpeg reports how far through the
     * timeline it has muxed, which needs the duration to become a percentage, and that is not
     * known for every stream. A bar that sat at 0% for a whole download would say less than one
     * that simply shows work in progress. The title names what is downloading, which is the part
     * that was missing.
     */
    private fun buildNotification(title: String?): Notification =
        NotificationCompat.Builder(this, MediaDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(title ?: getString(R.string.download_notification_generic))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val NOTIFICATION_ID = 4_101
        private const val EXTRA_TITLE = "title"

        /**
         * Brings the service up for a starting download.
         *
         * Safe to call for each one: a second start only refreshes the notification, and the
         * service is stopped once by [stop] when the last session ends.
         */
        fun start(context: Context, title: String) {
            val intent = Intent(context, FfmpegDownloadService::class.java)
                .putExtra(EXTRA_TITLE, title)
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, FfmpegDownloadService::class.java))
            }
        }
    }
}
