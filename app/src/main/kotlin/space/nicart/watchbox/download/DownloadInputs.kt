package space.nicart.watchbox.download

import space.nicart.watchbox.domain.SubtitleOption

/** Fragments such as Arvin-HD's #.m3u8 are client hints, never part of the HTTP request. */
internal fun ffmpegInputUrl(url: String): String = url.substringBefore('#')

/** Capture every supplied language independently of whether playback subtitles are Off. */
internal fun downloadableSubtitleTracks(tracks: List<SubtitleOption>): List<SubtitleOption> =
    tracks.filter { it.url.isNotBlank() }.distinctBy { it.url }
