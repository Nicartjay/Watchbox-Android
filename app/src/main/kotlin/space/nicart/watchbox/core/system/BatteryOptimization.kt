package space.nicart.watchbox.core.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Android's battery optimisation exemption, as far as an app is allowed to touch it.
 *
 * Doze and App Standby can suspend network access and freeze background work. That matters
 * here for two things the app does outside the foreground: the local cast proxy, which serves
 * the stream to a receiver for as long as it plays, and long playback on a screen the user has
 * stopped touching. When the system suspends either, the symptom is a stall with no error.
 *
 * An app cannot grant or revoke this itself - by design, or every app would exempt itself on
 * first launch. It can only report the current state and send the user somewhere to change it,
 * which is why this is a status plus two intents rather than a setting the app stores.
 */
/** What pressing the row should do, given the current state. */
enum class BatteryAction {
    /** Ask for the exemption via the system dialog. */
    REQUEST,

    /** Send the user to the system list, the only place the exemption can be given back. */
    MANAGE,
}

/**
 * Which action applies when the app is [isExempt].
 *
 * Separated from the intent-firing so the rule can be tested without a device: Android's API is
 * asymmetric - there is a dialog for asking but none for revoking - so an exempt app must be
 * sent to the settings list instead. Collapsing this into "always show the dialog" would be an
 * easy simplification to make later, and it would silently do nothing for anyone trying to turn
 * the exemption back off.
 */
fun batteryActionFor(isExempt: Boolean): BatteryAction =
    if (isExempt) BatteryAction.MANAGE else BatteryAction.REQUEST

object BatteryOptimization {

    /**
     * True when the system has exempted this app.
     *
     * Read live rather than cached: it is changed outside the app, so a remembered value goes
     * stale the moment the user acts on it.
     */
    fun isExempt(context: Context): Boolean {
        // Doze arrived in M. Below that there is nothing to be exempt from, and reporting
        // "restricted" would be a warning the user cannot act on.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true

        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * The system dialog that asks for the exemption directly.
     *
     * One tap for the user, but it only ever *grants*: Android has no counterpart for giving
     * the exemption back, so [openSettings] is the route once it is held.
     *
     * Returns false when no activity handles it, which happens on television builds and
     * stripped-down images - the caller should fall back to [openSettings] rather than leave a
     * dead row.
     */
    fun requestExemption(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false

        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        )
        if (context.resolvesToStub(intent)) return false
        return context.startActivitySafely(intent)
    }

    /**
     * The system's own battery-optimisation list.
     *
     * Used to revoke, and as the fallback when the dialog above is unavailable. It cannot be
     * targeted at one app - Android offers no per-app variant of this screen - so the user
     * arrives at the full list and has to find this app in it.
     */
    fun openSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (!context.resolvesToStub(intent) && context.startActivitySafely(intent)) return true

        // Falls back to this app's own details page, which every device has and which reaches
        // the battery controls in a couple more taps. Better than a row that does nothing.
        return context.startActivitySafely(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }

    /**
     * Starts [intent], reporting whether anything handled it.
     *
     * `resolveActivity` is deliberately not used to check first: it is subject to package
     * visibility filtering on API 30+ and can answer null for an activity that would in fact
     * start. Attempting the launch and catching the failure is the reliable order.
     */
    /**
     * True when [intent] resolves to a placeholder that closes itself immediately.
     *
     * Android TV declares handlers for both battery-optimisation actions and points them at
     * `EmptyStubActivity`, which finishes on creation. `startActivity` therefore reports
     * success, no screen appears, and the app has no way to tell that from a working launch -
     * so the row silently did nothing on a television. Checking the target first is what makes
     * the fall back to the app's own details page - which is real on TV, and does hold the
     * battery controls - actually happen.
     */
    private fun Context.resolvesToStub(intent: Intent): Boolean {
        val name = runCatching {
            packageManager.resolveActivity(intent, 0)?.activityInfo?.name
        }.getOrNull() ?: return false

        return name.endsWith(STUB_ACTIVITY_SUFFIX)
    }

    private fun Context.startActivitySafely(intent: Intent): Boolean = runCatching {
        // Required because this may be called with an application context, which has no task
        // of its own to place the activity in.
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /**
     * Matched by name because the platform exposes no other signal.
     *
     * There is no API for "this activity is a placeholder"; the intent resolves, the component
     * exists, and it simply finishes. Suffix-matched rather than compared in full so it holds
     * whichever package declares it.
     */
    private const val STUB_ACTIVITY_SUFFIX = "EmptyStubActivity"
}
