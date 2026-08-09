package space.nicart.watchbox.ui.extensions

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.drawable.toBitmap
import space.nicart.watchbox.ui.components.WbAsyncImage

/**
 * Icon for an extension row.
 *
 * Installed and available extensions carry their icon in completely different
 * forms, and the two cases have to be handled separately:
 *
 *  - **Available** entries come from the repository index, which does not ship an
 *    icon field at all - the URL is derived from the package name, so the icon is
 *    a network fetch handled by Coil.
 *  - **Installed** entries are read back through `PackageManager`, which hands
 *    over a live [Drawable]. There is no URL to fetch: once the APK is on disk the
 *    repository icon is redundant, and relying on it would leave sideloaded
 *    extensions - which appear in no repository - permanently blank.
 *
 * The [Drawable] is converted to a bitmap once and cached against the drawable
 * instance, because this sits in a `LazyColumn` row that recomposes on every
 * scroll and adaptive-icon rasterisation is not free.
 */
@Composable
fun ExtensionIcon(
    drawable: Drawable?,
    iconUrl: String?,
    modifier: Modifier = Modifier,
) {
    val painter = rememberDrawablePainter(drawable)

    when {
        painter != null -> Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )

        // Falls through to the URL so an installed extension whose drawable could
        // not be read still shows the repository icon when one exists.
        else -> WbAsyncImage(
            url = iconUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}

/**
 * Rasterises a [Drawable] into a Compose [Painter], or null when it cannot be
 * drawn.
 *
 * Adaptive icons report no intrinsic size, so a fixed box is used for them rather
 * than trusting the drawable's own dimensions - otherwise `toBitmap` is asked for
 * a -1 x -1 bitmap and throws.
 */
@Composable
fun rememberDrawablePainter(drawable: Drawable?): Painter? =
    remember(drawable) {
        if (drawable == null) return@remember null

        runCatching {
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: ICON_FALLBACK_PX
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: ICON_FALLBACK_PX
            BitmapPainter(drawable.toBitmap(width, height).asImageBitmap())
        }.getOrNull()
    }

/** Side length used when a drawable reports no intrinsic size (adaptive icons). */
private const val ICON_FALLBACK_PX = 108

/** Fixed-size slot so rows keep a stable height while an icon loads. */
@Composable
fun ExtensionIconSlot(
    drawable: Drawable?,
    iconUrl: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        ExtensionIcon(
            drawable = drawable,
            iconUrl = iconUrl,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
