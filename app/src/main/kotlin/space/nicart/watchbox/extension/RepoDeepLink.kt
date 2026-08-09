package space.nicart.watchbox.extension

/**
 * A repository URL handed over by another app or a web page.
 *
 * Aniyomi and its forks publish repository links as `aniyomi://add-repo?url=...`,
 * and extension repositories advertise themselves with exactly that scheme. Parsing
 * it here means a link from a browser or a chat app adds the repository directly,
 * which matters because the app ships with no repositories at all - typing a long
 * raw URL by hand would otherwise be the only way to get started.
 */
object RepoDeepLink {

    /** Schemes accepted for a repository link. */
    private val SCHEMES = setOf("aniyomi", "tachiyomi", "watchbox")

    /** Hosts that mean "add this repository". */
    private val HOSTS = setOf("add-repo", "add-repository")

    /**
     * Extracts a repository URL from a deep link, or null when it is not one.
     *
     * Parsed from the raw string rather than through `android.net.Uri` so it is
     * testable off-device, and because the `url` parameter itself contains `://`
     * and `/` - which some `Uri` helpers mangle depending on how the sender encoded
     * it. Both encoded and literal forms are accepted, since links found in the
     * wild use both.
     */
    fun parse(link: String?): String? {
        val raw = link?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val scheme = raw.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme !in SCHEMES) return null

        val afterScheme = raw.substringAfter("://")
        val host = afterScheme.substringBefore('?').trim('/').lowercase()
        if (host !in HOSTS) return null

        val query = afterScheme.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null

        val url = query.split('&')
            .firstNotNullOfOrNull { param ->
                val (key, value) = param.split('=', limit = 2)
                    .let { it.firstOrNull().orEmpty() to it.getOrNull(1).orEmpty() }

                value.takeIf { key.equals("url", ignoreCase = true) && it.isNotBlank() }
            }
            ?: return null

        val decoded = url.decodePercentEncoding()

        // Only http(s) is accepted. A repository link is fetched, so allowing
        // arbitrary schemes here would let a link point the app at something it has
        // no business opening.
        if (!decoded.startsWith("http://") && !decoded.startsWith("https://")) return null

        return decoded
    }

    /**
     * Minimal percent-decoding.
     *
     * `+` is deliberately left alone: it is legal inside a path and only means a
     * space in form encoding, which a repository URL is not.
     */
    private fun String.decodePercentEncoding(): String {
        if (!contains('%')) return this

        val out = StringBuilder(length)
        var index = 0

        while (index < length) {
            val char = this[index]
            val hex = if (char == '%' && index + 2 < length) substring(index + 1, index + 3) else null
            val decoded = hex?.toIntOrNull(radix = 16)

            if (decoded != null) {
                out.append(decoded.toChar())
                index += 3
            } else {
                out.append(char)
                index++
            }
        }
        return out.toString()
    }
}
