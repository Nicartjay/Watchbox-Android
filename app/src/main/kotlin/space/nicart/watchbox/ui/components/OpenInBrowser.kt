package space.nicart.watchbox.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Normalises a source-supplied address into something safe to hand to `ACTION_VIEW`,
 * or null when it is not a web address at all.
 *
 * Separate from [openInBrowser] so the validation can be unit-tested: the decision
 * is pure string work, while opening the link needs a real `Context`.
 *
 * Restricted to `http`/`https`. An extension's `baseUrl` is third-party code and
 * could return any string; forwarding an arbitrary scheme to `ACTION_VIEW` would let
 * it aim an implicit intent at other installed apps. A schemeless value like
 * `example.com` is treated as `https` rather than rejected, because some extensions
 * store their domain without one.
 */
internal fun sanitiseWebUrl(url: String?): String? {
    val target = url?.trim().orEmpty()
    if (target.isEmpty()) return null

    // Reject anything with whitespace inside: never a valid address, and it is the
    // shape a malformed or injected value takes.
    if (target.any { it.isWhitespace() }) return null

    val scheme = target.substringBefore("://", missingDelimiterValue = "").lowercase()

    return when {
        scheme == "http" || scheme == "https" ->
            target.takeIf { it.length > "$scheme://".length }

        // No scheme at all: assume https rather than dropping the link.
        "://" !in target && ":" !in target.substringBefore("/") -> "https://$target"

        // Some other scheme - not ours to open.
        else -> null
    }
}

/**
 * Opens a web address in whatever the user uses for browsing.
 *
 * Returns false when the address is unusable or nothing on the device can handle it,
 * so callers can say so rather than appearing to do nothing.
 */
fun Context.openInBrowser(url: String?): Boolean {
    val target = sanitiseWebUrl(url) ?: run {
        Log.d(TAG, "not a web address: $url")
        return false
    }

    return try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        true
    } catch (error: ActivityNotFoundException) {
        // A device with no browser is unusual but not impossible - notably a bare TV
        // build - so this reports failure instead of crashing.
        Log.d(TAG, "no handler for $target: ${error.message}")
        false
    }
}

/**
 * Opens a YouTube link in the YouTube app, falling back to the browser.
 *
 * A plain `ACTION_VIEW` does not do this. Whichever app holds the persisted default for
 * `youtube.com` wins, and on a device where the user has ever chosen "always open in
 * Chrome" that is the browser - verified on device, where the intent resolved straight to
 * `com.android.chrome/...ChromeTabbedActivity` even with three YouTube apps installed and
 * the domain listed as `system_configured` for app links.
 *
 * So the package is named explicitly first. That is a stronger statement than a chooser:
 * the user asked to watch a trailer, and the app that plays trailers is the answer.
 *
 * Falls through to [openInBrowser] when YouTube is absent, which also covers a TV build
 * where it may not be installed.
 */
fun Context.openYouTube(url: String?): Boolean {
    val target = sanitiseWebUrl(url) ?: return false

    // Ordered by preference: the main app, then the TV app for a leanback device.
    for (pkg in YOUTUBE_PACKAGES) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).setPackage(pkg)
        try {
            startActivity(intent)
            return true
        } catch (error: ActivityNotFoundException) {
            Log.d(TAG, "$pkg cannot handle $target")
        }
    }

    return openInBrowser(target)
}

/**
 * YouTube's package names, most preferred first.
 *
 * The TV app is a separate package and is the only one present on many Android TV devices,
 * so naming both is what makes this work on the big screen as well.
 */
private val YOUTUBE_PACKAGES = listOf(
    "com.google.android.youtube",
    "com.google.android.youtube.tv",
)

private const val TAG = "WbBrowser"
