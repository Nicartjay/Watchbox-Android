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
    /**
     * What is left of the label once server, resolution and audio are taken out.
     *
     * This is what separates releases that agree on all three - `BluRay · HEVC ·
     * 66.39 GB · MKV` against the same at 41.84 GB - so it is kept rather than
     * discarded, and shown under a row whose title would otherwise be a
     * duplicate.
     */
    val detail: String?,
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
            if (parts.isEmpty()) return StreamFacets(label.trim(), null, null, null)

            val server = parts.first()
            val rest = parts.drop(1)

            val quality = rest.firstOrNull { RESOLUTION.matches(it) }

            val dub = rest.firstOrNull { part ->
                part != quality &&
                    part.lowercase() !in NOT_AUDIO &&
                    !SUBTITLE_COUNT.matches(part) &&
                    AUDIO.containsMatchIn(part)
            }

            // Whatever is left tells two same-resolution releases apart.
            val detail = rest
                .filter { it != quality && it != dub }
                .joinToString(" · ")
                .takeIf { it.isNotBlank() }

            return StreamFacets(server, quality, dub, detail)
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
 * A row in the quality panel: one stream, titled by its resolution.
 *
 * Carries the [stream] rather than just a height, because a resolution is not a
 * unique key - a server routinely offers the same one several times over, as
 * different releases or as mirrors of one file - and picking by height alone made
 * every copy but the first unreachable.
 *
 * [requiresDub] names the audio track a row would move to, or is null when the
 * row plays on the current one.
 */
internal data class QualityChoice(
    val stream: StreamOption,
    /** Row title: the resolution, suffixed `-1`, `-2` … where it repeats. */
    val label: String,
    /** What distinguishes this row from others at the same resolution. */
    val detail: String?,
    val requiresDub: String?,
)

/**
 * Every stream on [server], as rows titled by resolution, highest first.
 *
 * Nothing is collapsed. Streams sharing a resolution are numbered `1080p-1`,
 * `1080p-2` and carry their remaining label detail - size, source, codec - so the
 * rows can be told apart. Deduplicating by height previously hid real
 * alternatives: a server offering four different 2160p releases showed one row,
 * and because that left a single distinct quality the panel's own button was
 * hidden too, making all four unreachable.
 *
 * Rows only playable by changing audio track are marked rather than dropped.
 */
internal fun List<StreamOption>.qualityChoices(
    server: String?,
    dub: String?,
): List<QualityChoice> {
    val onServer = filter { server == null || it.facets.server == server }
        .filter { it.facets.quality != null }

    // Which resolutions repeat, so only those are numbered.
    val counts = onServer.groupingBy { it.facets.quality }.eachCount()
    val seen = mutableMapOf<String, Int>()

    // Grouped so a row is marked only when its resolution is genuinely absent
    // from the current audio track.
    val availableOnCurrent = onServer
        .filter { dub == null || it.facets.dub == dub }
        .mapNotNull { it.facets.quality }
        .toSet()

    return onServer
        .sortedByDescending { it.resolution }
        .map { stream ->
            val quality = stream.facets.quality!!
            val nth = seen.merge(quality, 1, Int::plus)!!
            QualityChoice(
                stream = stream,
                label = if ((counts[quality] ?: 0) > 1) "$quality-$nth" else quality,
                detail = stream.facets.detail,
                requiresDub = stream.facets.dub
                    ?.takeIf { dub != null && it != dub && quality !in availableOnCurrent },
            )
        }
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
