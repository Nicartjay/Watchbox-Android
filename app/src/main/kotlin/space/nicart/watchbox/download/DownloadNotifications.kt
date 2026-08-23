package space.nicart.watchbox.download

import android.app.Notification
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.scheduler.Requirements
import space.nicart.watchbox.R

/**
 * The download notification.
 *
 * Wraps Media3's [DownloadNotificationHelper] so the wording is ours: the helper's own
 * strings are generic, and the one thing the notification has to communicate clearly is
 * *why* nothing is happening. A queue stalled waiting for Wi-Fi looks identical to a broken
 * download otherwise, and that is the state users actually hit, because Wi-Fi-only is the
 * default.
 */
@UnstableApi
class DownloadNotifications(private val context: Context) {

    private val helper by lazy {
        DownloadNotificationHelper(context, MediaDownloadService.CHANNEL_ID)
    }

    fun progressNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification {
        val message = when {
            // Checked before the network bit, because an unmet charging or storage
            // requirement is the more specific complaint.
            notMetRequirements and Requirements.NETWORK_UNMETERED != 0 ->
                context.getString(R.string.download_waiting_wifi)

            notMetRequirements and Requirements.NETWORK != 0 ->
                context.getString(R.string.download_waiting_network)

            else -> null
        }

        return helper.buildProgressNotification(
            context,
            R.drawable.ic_download_notification,
            /* contentIntent = */ null,
            message,
            downloads,
            notMetRequirements,
        )
    }
}
