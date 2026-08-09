package space.nicart.watchbox.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * SSDP discovery for DLNA/UPnP renderers.
 *
 * ## Multicast lock
 *
 * Android's Wi-Fi driver drops multicast frames not addressed to the device
 * unless a [WifiManager.MulticastLock] is held — which is precisely the traffic
 * SSDP relies on, so without one discovery finds nothing at all and looks like a
 * network problem. The lock is taken per scan rather than held permanently:
 * it disables hardware filtering, so holding it while nothing is listening only
 * costs battery.
 *
 * ## Search target
 *
 * Searches `ssdp:all` rather than `MediaRenderer:1`. Counter-intuitive, but many
 * TVs answer with a non-AVTransport ST while still exposing AVTransport in their
 * description XML; filtering by ST loses those devices. Every LOCATION is fetched
 * and filtered on the parsed XML instead.
 */
class SsdpDiscovery(private val context: Context) {

    /**
     * Sends one M-SEARCH burst and collects distinct LOCATION URLs.
     *
     * Returns locations, not devices: fetching and parsing description XML is a
     * separate, cancellable step.
     */
    suspend fun discoverLocations(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Set<String> =
        withContext(Dispatchers.IO) {
            withMulticastLock {
                withTimeoutOrNull(timeoutMs + TIMEOUT_SLACK_MS) {
                    collect(timeoutMs)
                } ?: emptySet()
            }
        }

    private fun collect(timeoutMs: Long): Set<String> {
        val locations = linkedSetOf<String>()

        runCatching {
            DatagramSocket().use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS

                val request = buildSearchRequest(mxSeconds = (timeoutMs / 1000).toInt().coerceAtLeast(1))
                val payload = request.toByteArray()
                val group = InetAddress.getByName(SSDP_ADDRESS)

                socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(group, SSDP_PORT)))

                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(RESPONSE_BUFFER_BYTES)

                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val received = runCatching { socket.receive(packet); true }
                        .getOrDefault(false)
                    if (!received) continue

                    val response = String(packet.data, 0, packet.length)
                    locationOf(response)?.let(locations::add)
                }
            }
        }.onFailure {
            Log.d(TAG, "ssdp scan ended: ${it.javaClass.simpleName}: ${it.message}")
        }

        return locations
    }

    /**
     * `MAN` must be quoted and every line CRLF-terminated, including a blank
     * final line. Renderers are strict about this and simply ignore malformed
     * searches.
     */
    private fun buildSearchRequest(mxSeconds: Int): String = buildString {
        append("M-SEARCH * HTTP/1.1").append(CRLF)
        append("HOST: ").append(SSDP_ADDRESS).append(':').append(SSDP_PORT).append(CRLF)
        append("MAN: \"ssdp:discover\"").append(CRLF)
        append("MX: ").append(mxSeconds).append(CRLF)
        append("ST: ssdp:all").append(CRLF)
        append(CRLF)
    }

    /** Header names are case-insensitive in practice, so match loosely. */
    private fun locationOf(response: String): String? = response
        .lineSequence()
        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

    /**
     * Runs [block] with a multicast lock held, releasing it even on failure.
     *
     * Reference-counted so overlapping scans cannot release each other's lock.
     */
    private inline fun <T> withMulticastLock(block: () -> T): T {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager

        val lock = runCatching {
            wifi?.createMulticastLock(LOCK_TAG)?.apply {
                setReferenceCounted(true)
                acquire()
            }
        }.getOrNull()

        return try {
            block()
        } finally {
            runCatching { lock?.takeIf { it.isHeld }?.release() }
        }
    }

    private companion object {
        const val TAG = "Ssdp"
        const val CRLF = "\r\n"
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val DEFAULT_TIMEOUT_MS = 3_000L
        const val TIMEOUT_SLACK_MS = 1_000L
        const val SOCKET_READ_TIMEOUT_MS = 800
        const val RESPONSE_BUFFER_BYTES = 2048
        const val LOCK_TAG = "watchbox-ssdp"
    }
}
