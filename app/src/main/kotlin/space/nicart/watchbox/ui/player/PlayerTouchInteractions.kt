package space.nicart.watchbox.ui.player

import space.nicart.watchbox.core.ui.FormFactor

/** A mobile-only gesture; even a sideloaded mobile APK must not enable it on a TV. */
internal fun supportsHoldToBoost(isMobileBuild: Boolean, formFactor: FormFactor): Boolean =
    isMobileBuild && formFactor != FormFactor.TV

/** Leave the middle fifth for ordinary taps; either side supports the temporary speed hold. */
internal fun isSpeedHoldArea(x: Float, width: Int): Boolean =
    width > 0 && x in 0f..width.toFloat() && (x <= width * 0.4f || x >= width * 0.6f)

/** Never writes the selected speed, so releasing a hold restores the user's exact preference. */
internal fun effectivePlaybackSpeed(selectedSpeed: Float, holding: Boolean): Float =
    if (holding) 2f else selectedSpeed

/** AnimatedVisibility still draws during exit: NONE must not replace brightness with volume. */
internal fun retainedLevelGesture(previous: VerticalGesture, current: VerticalGesture): VerticalGesture =
    if (current == VerticalGesture.NONE) previous else current
