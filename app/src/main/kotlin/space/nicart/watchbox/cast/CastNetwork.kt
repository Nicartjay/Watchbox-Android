package space.nicart.watchbox.cast

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Network helpers shared by both cast paths.
 */
internal object CastNetwork {

    /**
     * Picks the local IP the target device can actually reach us on.
     *
     * A UDP "connect" sends no packets but makes the kernel run its routing
     * table and choose the egress interface for that specific destination. That
     * is materially better than taking the first non-loopback interface, which
     * picks the wrong address whenever a VPN, hotspot or virtual adapter is
     * present — a common cause of "casting starts then the TV shows nothing".
     */
    fun localAddressFor(targetHost: String, targetPort: Int): String? = runCatching {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(targetHost, targetPort))
            socket.localAddress?.hostAddress?.takeIf { it != "0.0.0.0" }
        }
    }.getOrNull()

    /** Fallback when no specific device is known yet. */
    fun outboundAddress(): String? = localAddressFor("8.8.8.8", 53)

    /** Asks the OS for a free port rather than probing upward from a guess. */
    fun freePort(): Int = runCatching {
        ServerSocket(0).use { it.localPort }
    }.getOrDefault(DEFAULT_PORT)

    fun isLoopback(host: String): Boolean = runCatching {
        InetAddress.getByName(host).isLoopbackAddress
    }.getOrDefault(false)

    const val DEFAULT_PORT = 8909
}
