package space.nicart.watchbox.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * SSDP discovery for DLNA/UPnP renderers.
 *
 * ## Multicast lock
 *
 * Android's Wi-Fi driver drops multicast frames not addressed to the device unless
 * a [WifiManager.MulticastLock] is held - precisely the traffic SSDP relies on, so
 * without one discovery finds nothing and looks like a network fault. The lock is
 * taken per scan rather than held permanently: it disables hardware filtering, so
 * holding it while nothing listens only costs battery.
 *
 * ## Why a MulticastSocket bound to the SSDP port
 *
 * Renderers normally reply by unicast to the source port, which a plain
 * `DatagramSocket` would receive. But a significant number of devices - several
 * Samsung and LG models among them - reply to the multicast group instead, and
 * some send only an unsolicited `NOTIFY` on the group. Those replies are invisible
 * to a socket that has not joined the group, so joining is what makes those TVs
 * appear at all.
 *
 * ## Why several bursts
 *
 * A single M-SEARCH is one UDP datagram with no retransmission. On Wi-Fi a lost
 * datagram is routine, and losing it means finding nothing, so the search is
 * repeated. Replies are also deliberately spread by the renderer across the `MX`
 * window, so the listen window has to outlast it.
 */
class SsdpDiscovery(private val context: Context) {

    /**
     * Sends repeated M-SEARCH bursts and collects distinct LOCATION URLs.
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
            // Bound to the SSDP port so group traffic is received, not just
            // unicast replies. Port reuse matters because other apps - and the
            // system's own media stack - hold the same port.
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(SSDP_PORT))
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS

                val group = InetAddress.getByName(SSDP_ADDRESS)
                joinOnEveryInterface(socket, group)

                val deadline = System.currentTimeMillis() + timeoutMs
                var nextSearch = 0L
                var burst = 0
                val buffer = ByteArray(RESPONSE_BUFFER_BYTES)

                while (System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()

                    // Re-sent periodically rather than once: UDP has no retries,
                    // and each target catches devices that ignore the others.
                    if (now >= nextSearch && burst < MAX_BURSTS) {
                        SEARCH_TARGETS.forEach { target ->
                            sendSearch(socket, group, target, timeoutMs)
                        }
                        burst++
                        nextSearch = now + BURST_INTERVAL_MS
                    }

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

        Log.i(TAG, "ssdp found ${locations.size} location(s)")
        return locations
    }

    /**
     * Joins the SSDP group on every usable interface.
     *
     * The default route is not necessarily the one carrying the LAN: with a VPN,
     * hotspot or active cellular data, joining only the default interface means the
     * search never reaches the network the TV is on.
     */
    private fun joinOnEveryInterface(socket: MulticastSocket, group: InetAddress) {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
        }.getOrDefault(emptyList())

        var joined = 0
        interfaces.forEach { nif ->
            val usable = runCatching {
                nif.isUp && !nif.isLoopback && nif.supportsMulticast() &&
                    nif.inetAddresses.asSequence().any { !it.isLoopbackAddress }
            }.getOrDefault(false)

            if (!usable) return@forEach

            // Failures are per-interface and expected: some report multicast
            // support without accepting a join.
            runCatching {
                socket.joinGroup(InetSocketAddress(group, SSDP_PORT), nif)
                joined++
            }
        }

        // Falls back to the default interface so a device that reports no
        // multicast-capable interface still gets a plain unicast search.
        if (joined == 0) {
            runCatching { socket.joinGroup(group) }
        }
    }

    private fun sendSearch(
        socket: MulticastSocket,
        group: InetAddress,
        searchTarget: String,
        timeoutMs: Long,
    ) {
        runCatching {
            val mx = (timeoutMs / 1000).toInt().coerceIn(1, MAX_MX_SECONDS)
            val payload = buildSearchRequest(searchTarget, mx).toByteArray()
            socket.send(
                DatagramPacket(payload, payload.size, InetSocketAddress(group, SSDP_PORT)),
            )
        }
    }

    /**
     * `MAN` must be quoted and every line CRLF-terminated, including a blank final
     * line. Renderers are strict about this and silently ignore malformed searches.
     */
    private fun buildSearchRequest(searchTarget: String, mxSeconds: Int): String = buildString {
        append("M-SEARCH * HTTP/1.1").append(CRLF)
        append("HOST: ").append(SSDP_ADDRESS).append(':').append(SSDP_PORT).append(CRLF)
        append("MAN: \"ssdp:discover\"").append(CRLF)
        append("MX: ").append(mxSeconds).append(CRLF)
        append("ST: ").append(searchTarget).append(CRLF)
        append(CRLF)
    }

    /**
     * Extracts the LOCATION header.
     *
     * Split on the header name rather than the first colon: the value is a URL that
     * contains colons of its own, and `substringAfter(':')` happens to work only
     * because the header colon comes first - which stops being true the moment a
     * device emits `LOCATION:http://...` with no space.
     */
    private fun locationOf(response: String): String? = response
        .lineSequence()
        .firstOrNull { it.trimStart().startsWith(LOCATION_HEADER, ignoreCase = true) }
        ?.trimStart()
        ?.drop(LOCATION_HEADER.length)
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
        const val LOCATION_HEADER = "LOCATION:"
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900

        /**
         * Long enough to outlast the MX window and survive a lost datagram.
         * Discovery runs in the background behind a spinner, so a longer scan costs
         * nothing but finds devices a short one misses.
         */
        const val DEFAULT_TIMEOUT_MS = 6_000L
        const val TIMEOUT_SLACK_MS = 1_500L
        const val SOCKET_READ_TIMEOUT_MS = 600
        const val BURST_INTERVAL_MS = 1_800L
        const val MAX_BURSTS = 3
        const val MAX_MX_SECONDS = 3

        /** 4 KB: replies from Android TV and some Samsung sets exceed 2 KB. */
        const val RESPONSE_BUFFER_BYTES = 4096
        const val LOCK_TAG = "watchbox-ssdp"

        /**
         * `ssdp:all` finds devices that answer with an unexpected ST, and the
         * targeted searches cut through the flood it provokes on a busy network -
         * where a renderer's reply can otherwise be crowded out.
         */
        val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "ssdp:all",
        )
    }
}
