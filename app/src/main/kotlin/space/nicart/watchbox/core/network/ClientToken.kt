package space.nicart.watchbox.core.network

import java.security.MessageDigest

/**
 * AOneRoom / MovieBox API client-token generation.
 *
 * Ported from the web app's `js/shared.js:88-100`. The upstream contract is:
 *
 *   X-Client-Token: "<unixSeconds>,<md5(reverse(unixSeconds))>"
 *
 * The server answers with an `x-user` response header carrying a JWT, which is
 * then sent as `Authorization: Bearer <jwt>` until it expires.
 *
 * Unlike the web version this uses the platform [MessageDigest] instead of a
 * hand-rolled MD5 (the web app vendored one only because browsers expose no
 * synchronous MD5).
 */
internal object ClientToken {

    fun generate(nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val reversed = nowSeconds.toString().reversed()
        return "$nowSeconds,${md5Hex(reversed)}"
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
