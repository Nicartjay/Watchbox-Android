package space.nicart.watchbox.ui.player

import space.nicart.watchbox.domain.StreamOption

/**
 * Splits a stream's label into the three things a viewer actually chooses
 * between: which server, which resolution, and which audio track.
 *
 * A source hands back one flat list, which for a well-served film runs to
 * thirty-odd entries that differ in only one of those three ways. Grouping them
 * turns that into three short lists.
 *
 * The input is a display string, because that is all the source API exposes -
 * `Video.quality` is free text. Every extension in practice builds it by joining
 * parts with a middle dot, so that is what this splits on:
 *
 *     Art/4k-Hub · 2160p · BluRay · HEVC · 66.39 GB · MKV
 *     Art/MbPly · 1080p · Hindi dub · MP4
 *     Yoru · 1080p · HLS · Original audio · 12 subs
 *
 * Being a display string, the format can change without warning, so nothing here
 * is allowed to fail: an unrecognised label still yields a [StreamFacets] whose
 * server is the whole label and whose other axes are null. A stream is never
 * hidden because it could not be parsed.
 */
internal data class StreamFacets(
    /** Server name, always present - the whole label if nothing else could be read. */
    val server: String,
    /** Resolution as shown, e.g. `2160p`, or null when the label carries none. */
    val quality: String?,
    /** Audio descriptor, e.g. `Hindi dub`, or null when the server offers one track. */
    val dub: String?,
) {
    companion object {
        private const val SEPARATOR = '·'

        /** `1080p`, `2160p`, or a bare `4K`. */
        private val RESOLUTION = Regex("""^(\d{3,4})p$|^4k$""", RegexOption.IGNORE_CASE)

        /**
         * Audio descriptors, as the sources word them.
         *
         * `sub` is included because some servers distinguish entries only by
         * subtitle language ("Arabic sub"), which is the same kind of choice from
         * the viewer's side even though it is not really audio.
         */
        private val AUDIO = Regex("""\b(dub|dubbed|sub|subbed|audio)\b""", RegexOption.IGNORE_CASE)

        /**
         * A subtitle *count*, not an audio track.
         *
         * "12 subs" is a property of the stream, not something to choose between,
         * and it matches [AUDIO] on `sub`, so it has to be excluded explicitly.
         */
        private val SUBTITLE_COUNT = Regex("""^\d+\s+subs?$""", RegexOption.IGNORE_CASE)

        /** Container and release tags, never an audio choice. */
        private val NOT_AUDIO = setOf(
            "hls", "dash", "mkv", "mp4", "webm", "avi", "ts",
            "hevc", "x265", "x264", "avc", "bluray", "web-dl", "webrip", "hdrip",
        )

        fun parse(label: String): StreamFacets {
            val parts = label.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return StreamFacets(label.trim(), null, null)

            val server = parts.first()
            val rest = parts.drop(1)

            val quality = rest.firstOrNull { RESOLUTION.matches(it) }

            val dub = rest.firstOrNull { part ->
                part != quality &&
                    part.lowercase() !in NOT_AUDIO &&
                    !SUBTITLE_COUNT.matches(part) &&
                    AUDIO.containsMatchIn(part)
            }

            return StreamFacets(server, quality, dub)
        }
    }
}

/** Parsed facets for this stream. */
internal val StreamOption.facets: StreamFacets get() = StreamFacets.parse(label)

/**
 * Picks the stream that best matches a requested server, quality and audio.
 *
 * Used when the viewer changes one axis: the other two are carried over where the
 * new server supports them, so switching server does not silently reset a chosen
 * resolution. Where it cannot be honoured - a server topping out at 1080p when
 * 2160p was selected - the nearest lower resolution is taken, falling back to the
 * highest on offer, because dropping to the next best is closer to the intent
 * than jumping to the top.
 *
 * Returns null only when [streams] is empty.
 */
internal fun pickStream(
    streams: List<StreamOption>,
    server: String?,
    quality: String?,
    dub: String?,
): StreamOption? {
    if (streams.isEmpty()) return null

    val onServer = streams.filter { server == null || it.facets.server == server }
        .ifEmpty { streams }

    // Exact audio match first, since a viewer who chose a dub cares more about
    // hearing the right language than about resolution.
    val onAudio = onServer.filter { dub == null || it.facets.dub == dub }
        .ifEmpty { onServer }

    val exact = onAudio.firstOrNull { it.facets.quality == quality }
    if (exact != null) return exact

    val wanted = quality?.let { resolutionOf(it) }
    if (wanted == null) return onAudio.maxByOrNull { it.resolution }

    // Nearest at or below the request, else the best available.
    return onAudio.filter { it.resolution in 1..wanted }.maxByOrNull { it.resolution }
        ?: onAudio.maxByOrNull { it.resolution }
}

/** Height in pixels for a shown resolution, or null when it is not one. */
private fun resolutionOf(quality: String): Int? = when {
    quality.equals("4k", ignoreCase = true) -> 2160
    else -> quality.removeSuffix("p").removeSuffix("P").toIntOrNull()
}

/** Servers in the order the source listed them, without duplicates. */
internal fun List<StreamOption>.serverOptions(): List<String> =
    map { it.facets.server }.distinct()

/**
 * Chooses the stream to open with, given the viewer's saved height.
 *
 * Nearest at or below [preferredHeight], because an exact match fell back to
 * "whatever the source listed first" whenever the chosen height was absent - on a
 * list led by a 2160p remux that meant asking for 1080p and being handed 4K.
 * Where every stream is above the request the smallest is taken instead, since
 * overshooting a deliberate 480p by an order of magnitude is the worse failure.
 *
 * A null [preferredHeight] keeps the source's own order: extensions sort their
 * configured preference to the front, and that is the best available signal when
 * the viewer has expressed none.
 *
 * Streams whose labels carry no height are only fallen back to, never preferred,
 * so an unparseable "Auto" entry cannot displace a known 1080p.
 */
internal fun defaultStream(
    streams: List<StreamOption>,
    preferredHeight: Int?,
): StreamOption? {
    if (preferredHeight == null) return streams.firstOrNull()

    return streams.filter { it.resolution in 1..preferredHeight }.maxByOrNull { it.resolution }
        ?: streams.filter { it.resolution > 0 }.minByOrNull { it.resolution }
        ?: streams.firstOrNull()
}

/**
 * Best resolution each server offers, keyed by server name.
 *
 * Shown beside the server so the choice can be made on what a server actually
 * carries: the names are opaque, and picking one only to find it tops out at
 * 360p is a wasted trip through two panels.
 *
 * A server whose labels carry no resolution is absent rather than zero, so the
 * caller can leave the line off instead of printing a meaningless height.
 */
internal fun List<StreamOption>.serverBestQuality(): Map<String, String> =
    groupBy { it.facets.server }
        .mapNotNull { (server, streams) ->
            streams
                .filter { it.facets.quality != null }
                .maxByOrNull { it.resolution }
                ?.facets?.quality
                ?.let { server to it }
        }
        .toMap()

/**
 * Resolutions available on [server], highest first.
 *
 * Ordered by actual height rather than by name so `720p` cannot sort above
 * `1080p`, and de-duplicated because mirrors of one release repeat it.
 */
internal fun List<StreamOption>.qualityOptions(server: String?): List<String> =
    filter { server == null || it.facets.server == server }
        .mapNotNull { stream -> stream.facets.quality?.let { it to stream.resolution } }
        .distinctBy { it.first }
        .sortedByDescending { it.second }
        .map { it.first }

/**
 * A resolution offered by a server, and which audio track it needs.
 *
 * Servers routinely carry a resolution on one audio track only - a 1080p Hindi
 * dub beside a 720p original. Listing the heights alone made those look
 * interchangeable, and choosing one while on the other track resolved straight
 * back to the stream already playing, so the row appeared to do nothing.
 *
 * [requiresDub] names the track a row would move to, or is null when the row is
 * available on the current one. The panel uses it to say so, and to switch both
 * axes together when the row is chosen.
 */
internal data class QualityChoice(
    val quality: String,
    val requiresDub: String?,
)

/**
 * Resolutions available on [server], highest first, each marked with the audio
 * track it needs when that is not [dub].
 *
 * Rows on the current track keep a null [QualityChoice.requiresDub]; the rest
 * name the track they would switch to. Nothing is dropped: a resolution reachable
 * only by changing track is still worth offering, as long as the switch is stated
 * rather than silent.
 */
internal fun List<StreamOption>.qualityChoices(
    server: String?,
    dub: String?,
): List<QualityChoice> {
    val onServer = filter { server == null || it.facets.server == server }

    return onServer
        .mapNotNull { stream -> stream.facets.quality?.let { Triple(it, stream.resolution, stream.facets.dub) } }
        .groupBy { it.first }
        .entries
        // Prefer the current track where a resolution exists on several, so a row
        // is only annotated when it genuinely is not available here.
        .map { (quality, group) ->
            val height = group.first().second
            val onCurrent = dub == null || group.any { it.third == dub }
            val requires = if (onCurrent) null else group.first().third
            Triple(quality, height, requires)
        }
        .sortedByDescending { it.second }
        .map { QualityChoice(it.first, it.third) }
}

/**
 * Audio tracks available on [server], in source order.
 *
 * Empty when the server labels no audio, which is the signal to leave the dub
 * button out rather than show a list of one.
 */
internal fun List<StreamOption>.dubOptions(server: String?): List<String> =
    filter { server == null || it.facets.server == server }
        .mapNotNull { it.facets.dub }
        .distinct()
