package space.nicart.watchbox.download

import android.app.Notification
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import space.nicart.watchbox.R
import space.nicart.watchbox.WatchBoxApplication

/**
 * Keeps downloads running when the app is not in front.
 *
 * A download measured in gigabytes cannot be tied to a visible activity: leaving the detail
 * page, or pressing Home on a television, would kill it partway. Android requires a
 * foreground service with a visible notification for work of this kind, which is also the
 * only honest way to show it - something is using the network and the storage, and it should
 * say so.
 *
 * [PlatformScheduler] restarts unfinished work after a reboot and waits for the network
 * requirements to be met, so a download queued on mobile data with Wi-Fi-only set resumes by
 * itself once Wi-Fi returns rather than needing to be started again by hand.
 */
@UnstableApi
class MediaDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    /* channelDescriptionResourceId = */ 0,
) {

    override fun getDownloadManager(): DownloadManager {
        val container = (application as WatchBoxApplication).container
        return container.downloadEngine.manager()
    }

    /**
     * Job id 1, which is this app's only scheduled job.
     *
     * `PlatformScheduler` needs `RECEIVE_BOOT_COMPLETED` to survive a restart; without it
     * the queue simply waits for the app to be opened again, which is an acceptable
     * degradation rather than a failure.
     */
    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification {
        val container = (application as WatchBoxApplication).container
        return container.downloadNotifications.progressNotification(downloads, notMetRequirements)
    }

    companion object {
        const val CHANNEL_ID = "watchbox_downloads"
        const val FOREGROUND_NOTIFICATION_ID = 4_100
        private const val JOB_ID = 1

        /** Ensures the channel exists before the first notification is posted. */
        fun ensureChannel(context: android.content.Context) {
            NotificationUtil.createNotificationChannel(
                context,
                CHANNEL_ID,
                R.string.download_channel_name,
                /* descriptionResourceId = */ 0,
                NotificationUtil.IMPORTANCE_LOW,
            )
        }
    }
}
