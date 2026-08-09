package space.nicart.watchbox.cast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permission Cast discovery needs on Android 13+.
 *
 * `NEARBY_WIFI_DEVICES` is a runtime permission, so declaring it in the manifest is
 * not enough - the Cast SDK's scan returns no routes until it is granted. Below API
 * 33 there is nothing to request: the multicast and Wi-Fi-state permissions are
 * install-time, and discovery works without a prompt.
 */
object CastPermissions {

    /** Null when nothing needs requesting on this platform version. */
    val required: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            null
        }

    /** True when discovery can proceed. */
    fun isGranted(context: Context): Boolean {
        val permission = required ?: return true
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
