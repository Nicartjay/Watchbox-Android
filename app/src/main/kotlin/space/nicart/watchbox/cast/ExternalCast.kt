package space.nicart.watchbox.cast

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Hands a stream to an external casting app.
 *
 * Worth having even with casting built in: apps like Web Video Caster support
 * receivers this app does not - Roku, Fire TV, Xbox, smart TVs with proprietary
 * protocols - and they have years of per-device workarounds behind them. When a
 * specific TV refuses our stream, handing off is a better answer than reverse
 * engineering that TV.
 *
 * The stream URL is passed with its headers attached. That matters because these
 * URLs usually require a `Referer`, and an app that fetches without one gets a 403 -
 * so a hand-off that dropped the headers would fail on most sources.
 */
object ExternalCast {

    /**
     * Sends [url] to Web Video Caster, falling back to any app that can handle it.
     *
     * Targets the package explicitly first so the stream goes where the user asked
     * rather than into a chooser that might list the browser.
     */
    fun sendToWebVideoCaster(
        context: Context,
        url: String,
        headers: Map<String, String>,
        title: String,
    ): Boolean {
        val intent = buildIntent(url, headers, title)

        // Direct launch first.
        intent.setPackage(WEB_VIDEO_CASTER_PACKAGE)
        if (context.tryStart(intent)) return true

        // Then a chooser, which also covers the app being installed under a
        // different id, or the user preferring another casting app.
        intent.setPackage(null)
        if (context.tryStart(Intent.createChooser(intent, "Cast with"))) return true

        // Finally the store, so "not installed" is actionable rather than silent.
        return context.tryStart(
            Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URL)),
        )
    }

    /** True when Web Video Caster is installed, for labelling the row. */
    fun isWebVideoCasterInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(WEB_VIDEO_CASTER_PACKAGE) != null
    }.getOrDefault(false)

    private fun buildIntent(
        url: String,
        headers: Map<String, String>,
        title: String,
    ): Intent = Intent(Intent.ACTION_VIEW).apply {
        // video/* rather than a precise type: HLS is served under several
        // inconsistent MIME types, and being specific makes the app decline streams
        // it can actually play.
        setDataAndType(Uri.parse(url), "video/*")

        putExtra("title", title)

        // Web Video Caster reads headers from a String array of alternating
        // name/value entries. Extras that other apps ignore are harmless.
        if (headers.isNotEmpty()) {
            val flattened = headers.flatMap { (name, value) -> listOf(name, value) }
            putExtra("android.media.intent.extra.HTTP_HEADERS", flattened.toTypedArray())
            putExtra("headers", flattened.toTypedArray())

            headers["Referer"]?.let { putExtra("referUrl", it) }
            headers["User-Agent"]?.let { putExtra("userAgent", it) }
        }

        // The receiving app runs in its own task, and the URI grant lets it read
        // the stream without needing its own credentials.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun Context.tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        Log.d(TAG, "no handler for ${intent.`package` ?: "chooser"}: ${error.message}")
        false
    }

    private const val TAG = "ExternalCast"
    private const val WEB_VIDEO_CASTER_PACKAGE = "com.instantbits.cast.webvideo"
    private const val PLAY_STORE_URL =
        "https://play.google.com/store/apps/details?id=$WEB_VIDEO_CASTER_PACKAGE"
}
