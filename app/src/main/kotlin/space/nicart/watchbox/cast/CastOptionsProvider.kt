package space.nicart.watchbox.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Cast SDK configuration.
 *
 * The framework discovers this class by name from an `<meta-data>` entry in the
 * manifest, so it must stay public with a no-arg constructor and must not be
 * obfuscated away.
 *
 * Uses the **Default Media Receiver** (`CC1AD845`). A custom receiver id would
 * point at someone else's branded receiver app — go2tv, for instance, ships its
 * own id, which is registered to them and would be wrong to borrow.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        val notificationOptions = NotificationOptions.Builder()
            .setActions(
                listOf(
                    com.google.android.gms.cast.framework.media.MediaIntentReceiver
                        .ACTION_TOGGLE_PLAYBACK,
                    com.google.android.gms.cast.framework.media.MediaIntentReceiver
                        .ACTION_STOP_CASTING,
                ),
                intArrayOf(0, 1),
            )
            .build()

        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            // Deliberately no expanded controller activity: playback is driven
            // from our own player UI, and naming a non-existent activity here
            // crashes when the notification is tapped.
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(DEFAULT_MEDIA_RECEIVER_ID)
            .setCastMediaOptions(mediaOptions)
            // Stop the receiver when our session ends, so the TV returns to its
            // home screen rather than sitting on an idle receiver.
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    /** No additional session providers; only the default media receiver. */
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        /** Google's Default Media Receiver. */
        const val DEFAULT_MEDIA_RECEIVER_ID = "CC1AD845"
    }
}
