package space.nicart.watchbox.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.model.BannerItem
import space.nicart.watchbox.data.model.OperatingSection
import space.nicart.watchbox.data.model.Subject
import space.nicart.watchbox.data.model.SubjectType
import space.nicart.watchbox.data.remote.OneRoomApi
import space.nicart.watchbox.data.remote.TmdbApi
import space.nicart.watchbox.data.remote.TmdbImage
import space.nicart.watchbox.data.remote.TmdbType
import space.nicart.watchbox.data.remote.WatchBoxApi
import space.nicart.watchbox.data.source.NativeServer
import space.nicart.watchbox.data.source.NativeSourceResolver
import space.nicart.watchbox.data.source.NativeSourcesResponse
import space.nicart.watchbox.data.source.ResolveResult

/**
 * The single repository.
 *
 * Merges the ONEROOM content API, TMDB enrichment and our own stream proxy into
 * the UI models in `domain/Models.kt`. All network work is dispatched on IO.
 */
class MediaRepository(
    private val oneRoom: OneRoomApi,
    private val tmdb: TmdbApi,
    private val watchBox: WatchBoxApi,
    private val resolver: NativeSourceResolver,
    private val store: WatchBoxStore,
    private val allowDevOnlyServers: Boolean,
) {

    // ------------------------------------------------------------------ home

    /**
     * Home rows + hero.
     *
     * Only `BANNER` (hero) and subject-bearing rows are kept; `CUSTOM`, `FILTER`
     * and `SPORT_LIVE` sections carry no playable media for this app's scope.
     * Hero backdrops are enriched from TMDB concurrently.
     */
    suspend fun home(): Result<HomeContent> = runCatching {
        withContext(Dispatchers.IO) {
            val response = oneRoom.home(page = 1, perPage = 12)
                ?: error("Home feed unavailable")

            val sections = response.operatingList.orEmpty()

            val heroSubjects = sections
                .firstOrNull { it.type == TYPE_BANNER }
                ?.banner
                ?.items
                .orEmpty()
                .mapNotNull(::bannerToSubject)
                .filter { SubjectType.isPlayableMedia(it.subjectType) }
                .take(MAX_HERO)

            val rows = sections
                .filter { it.type in ROW_TYPES }
                .mapNotNull(::sectionToRow)

            val hero = coroutineScope {
                heroSubjects
                    .map { subject -> async { enrichHero(subject) } }
                    .map { it.await() }
            }

            HomeContent(hero = hero, rows = rows)
        }
    }

    private fun bannerToSubject(item: BannerItem): Subject? {
        item.subject?.takeIf { it.detailPath.isNotBlank() }?.let { return it }
        if (item.detailPath.isBlank()) return null
        return Subject(
            subjectId = item.subjectId,
            subjectType = item.subjectType,
            title = item.title,
            detailPath = item.detailPath,
            cover = item.image,
        )
    }

    private fun sectionToRow(section: OperatingSection): MediaRow? {
        val items = section.subjects.orEmpty()
            .filter { SubjectType.isPlayableMedia(it.subjectType) }
            .filter { it.detailPath.isNotBlank() }
            .map(MediaCard::from)
        if (items.isEmpty()) return null
        val title = section.title.ifBlank { return null }
        return MediaRow(
            id = section.opId.ifBlank { title },
            title = title,
            items = items,
        )
    }

    /** Fetch a wide backdrop + title logo so the hero isn't a stretched poster. */
    private suspend fun enrichHero(subject: Subject): HeroItem {
        val card = MediaCard.from(subject)
        val enriched = runCatching {
            val type = TmdbType.of(subject.subjectType)
            val id = tmdb.findId(subject.title, subject.year, type) ?: return@runCatching null
            tmdb.details(id, type)
        }.getOrNull()

        return HeroItem(
            card = card,
            backdropUrl = TmdbImage.backdrop(enriched?.backdropPath, wide = true)
                ?: subject.coverUrl,
            logoUrl = TmdbImage.logo(enriched?.logoPath),
        )
    }

    // ---------------------------------------------------------------- detail

    /**
     * Full detail for [detailPath].
     *
     * ONEROOM supplies the canonical episode counts (`resource.seasons`), TMDB
     * supplies artwork, cast and per-episode metadata.
     */
    suspend fun detail(detailPath: String): Result<MediaDetail> = runCatching {
        withContext(Dispatchers.IO) {
            val response = oneRoom.detail(detailPath) ?: error("Title unavailable")
            val subject = response.subject ?: error("Title unavailable")

            val type = TmdbType.of(subject.subjectType)
            val tmdbId = runCatching { tmdb.findId(subject.title, subject.year, type) }.getOrNull()
            val details = tmdbId?.let { runCatching { tmdb.details(it, type) }.getOrNull() }

            val apiSeasons = response.resource?.seasons.orEmpty()
            val tmdbSeasons = details?.seasons.orEmpty().associateBy { it.seasonNumber }

            val seasons = if (subject.isSeries) {
                apiSeasons
                    .filter { it.se > 0 }
                    .map { season ->
                        SeasonSummary(
                            season = season.se,
                            episodeCount = maxOf(
                                season.maxEp,
                                tmdbSeasons[season.se]?.episodeCount ?: 0,
                            ),
                            posterUrl = TmdbImage.poster(tmdbSeasons[season.se]?.posterPath),
                            label = "Season ${season.se}",
                        )
                    }
                    .ifEmpty {
                        listOf(SeasonSummary(1, 1, null, "Season 1"))
                    }
            } else {
                emptyList()
            }

            val cast = buildCast(response.stars.orEmpty(), details)

            // TMDB "recommendations" are TMDB ids, which cannot be opened via a
            // ONEROOM detailPath, so the API's own related list is the only usable
            // source here.
            val recommendations = runCatching {
                oneRoom.recommendations(subject.subjectId)
                    ?.results
                    .orEmpty()
                    .filter { SubjectType.isPlayableMedia(it.subjectType) }
                    .filter { it.detailPath.isNotBlank() && it.detailPath != detailPath }
                    .map(MediaCard::from)
                    .take(20)
            }.getOrDefault(emptyList())

            MediaDetail(
                subjectId = subject.subjectId,
                detailPath = subject.detailPath.ifBlank { detailPath },
                title = subject.title,
                overview = subject.description.ifBlank { details?.overview.orEmpty() },
                posterUrl = subject.coverUrl ?: TmdbImage.poster(details?.posterPath),
                backdropUrl = TmdbImage.backdrop(details?.backdropPath, wide = true)
                    ?: subject.coverUrl,
                logoUrl = TmdbImage.logo(details?.logoPath),
                subjectType = subject.subjectType,
                year = subject.year ?: details?.year,
                runtimeMinutes = subject.duration.takeIf { it > 0 }?.div(60)
                    ?: details?.runtimeMinutes,
                genres = subject.genres.ifEmpty { details?.genres.orEmpty().map { it.name } },
                country = subject.countryName,
                imdbRating = subject.imdbRatingValue.takeIf { it.isNotBlank() && it != "0" },
                tmdbRating = details?.voteAverage ?: 0.0,
                tmdbId = tmdbId,
                isUpcoming = subject.isUpcoming,
                releaseDate = subject.releaseDate.ifBlank { details?.releaseDate.orEmpty() },
                seasons = seasons,
                cast = cast,
                recommendations = recommendations,
                dominantColorHex = subject.cover?.avgHueDark,
            )
        }
    }

    private fun buildCast(
        stars: List<space.nicart.watchbox.data.model.Staff>,
        details: space.nicart.watchbox.data.remote.TmdbDetails?,
    ): List<CastMember> {
        val apiCast = stars
            .filter { it.name.isNotBlank() }
            .map {
                CastMember(
                    name = it.name,
                    character = it.character,
                    photoUrl = it.avatarUrl.takeIf { url -> url.isNotBlank() },
                )
            }
        if (apiCast.isNotEmpty()) return apiCast.take(24)

        return details?.credits?.cast.orEmpty()
            .sortedBy { it.order }
            .take(24)
            .map {
                CastMember(
                    name = it.name,
                    character = it.character,
                    photoUrl = TmdbImage.profile(it.profilePath),
                )
            }
    }

    /**
     * Episodes for a season.
     *
     * The API knows how many episodes are playable; TMDB supplies titles, stills
     * and synopses. Extra TMDB episodes beyond the API count are surfaced (flagged
     * [EpisodeItem.tmdbOnly]) only when they have a still image, matching the web
     * behaviour.
     */
    suspend fun episodes(
        tmdbId: Int?,
        season: Int,
        apiEpisodeCount: Int,
    ): List<EpisodeItem> = withContext(Dispatchers.IO) {
        val tmdbEpisodes = tmdbId
            ?.let { runCatching { tmdb.seasonEpisodes(it, season) }.getOrDefault(emptyList()) }
            .orEmpty()

        if (tmdbEpisodes.isEmpty()) {
            return@withContext (1..maxOf(apiEpisodeCount, 1)).map { ep ->
                EpisodeItem(
                    season = season,
                    episode = ep,
                    title = "Episode $ep",
                    overview = "",
                    stillUrl = null,
                    airDate = null,
                    runtimeMinutes = null,
                    rating = 0.0,
                )
            }
        }

        tmdbEpisodes
            .filter { it.episodeNumber > 0 }
            .filter { it.episodeNumber <= apiEpisodeCount || it.stillPath != null }
            .map { EpisodeItem.from(it, season, tmdbOnly = it.episodeNumber > apiEpisodeCount) }
            .sortedBy { it.episode }
    }

    // ---------------------------------------------------------------- search

    suspend fun search(query: String, page: Int = 1): Result<List<MediaCard>> = runCatching {
        withContext(Dispatchers.IO) {
            oneRoom.search(query, page = page)
                ?.results
                .orEmpty()
                .filter { SubjectType.isPlayableMedia(it.subjectType) }
                .filter { it.detailPath.isNotBlank() }
                .map(MediaCard::from)
        }
    }

    suspend fun trending(page: Int = 1): List<MediaCard> = withContext(Dispatchers.IO) {
        runCatching {
            oneRoom.trending(page = page)
                ?.results
                .orEmpty()
                .filter { SubjectType.isPlayableMedia(it.subjectType) }
                .map(MediaCard::from)
        }.getOrDefault(emptyList())
    }

    // -------------------------------------------------------------- playback

    /**
     * Resolve a playable source.
     *
     * Order of attack mirrors the web app:
     *  1. the ONEROOM play API (fastest, direct MP4/HLS with official captions);
     *  2. the native provider chain via the Worker, with real failure detection.
     */
    suspend fun resolvePlayback(
        detail: MediaDetail,
        season: Int,
        episode: Int,
    ): Result<PlaybackSource> = runCatching {
        withContext(Dispatchers.IO) {
            primarySource(detail, season, episode)
                ?: nativeSource(detail, season, episode)
                ?: error("No playable source found.")
        }
    }

    private suspend fun primarySource(
        detail: MediaDetail,
        season: Int,
        episode: Int,
    ): PlaybackSource? {
        val play = runCatching {
            watchBox.play(detail.subjectId, detail.detailPath, season, episode)
        }.getOrNull() ?: return null

        val raw = (play.streams.orEmpty() + play.hls.orEmpty())
            .filter { it.url.isNotBlank() && !it.vipLocked }
        if (raw.isEmpty()) return null

        val streams = raw
            .sortedByDescending { it.heightOrZero }
            .map { stream ->
                PlayableStream(
                    url = watchBox.proxiedStreamUrl(stream.url),
                    label = stream.resolutions.takeIf { it.isNotBlank() }
                        ?.let { "${it}p" }
                        ?: "Auto",
                    height = stream.heightOrZero,
                    isHls = stream.isHls,
                    streamId = stream.id,
                    format = stream.format.ifBlank { "MP4" },
                )
            }

        val subtitles = raw.firstOrNull()?.let { first ->
            runCatching {
                oneRoom.captions(
                    streamId = first.id,
                    subjectId = detail.subjectId,
                    detailPath = detail.detailPath,
                    format = first.format.ifBlank { "MP4" },
                )?.captions.orEmpty().map { caption ->
                    PlayableSubtitle(
                        url = watchBox.subtitleUrl(caption.url),
                        language = caption.lan,
                        label = caption.lanName.ifBlank { caption.lan.uppercase() },
                    )
                }
            }.getOrDefault(emptyList())
        }.orEmpty()

        return PlaybackSource(
            serverName = "StreamBox",
            serverId = null,
            streams = streams,
            subtitles = subtitles,
            hosts = emptyList(),
            audioTracks = emptyList(),
            introRange = null,
            outroRange = null,
        )
    }

    private suspend fun nativeSource(
        detail: MediaDetail,
        season: Int,
        episode: Int,
    ): PlaybackSource? {
        val preferred = NativeServer.byId(store.currentSettings().lastServerId)
        val (success, _) = resolver.resolveWithFallback(
            title = detail.title,
            year = detail.year,
            isSeries = detail.isSeries,
            episode = episode.takeIf { detail.isSeries },
            tmdbId = detail.tmdbId,
            malId = null,
            isAnime = detail.isAnime,
            includeDevOnly = allowDevOnlyServers,
            preferred = preferred,
        )
        val resolved = success ?: return null
        store.setLastServerId(resolved.server.id)
        return resolved.toPlaybackSource(season, episode)
    }

    private suspend fun ResolveResult.Success.toPlaybackSource(
        season: Int,
        episode: Int,
    ): PlaybackSource = payload.let { body ->
        PlaybackSource(
            serverName = body.server?.takeIf { it.isNotBlank() } ?: server.displayName,
            serverId = server.id,
            streams = body.sources.orEmpty()
                .filter { it.url.isNotBlank() }
                .map { stream ->
                    PlayableStream(
                        url = watchBox.proxiedStreamUrl(stream.url),
                        label = stream.quality.ifBlank { "Auto" },
                        height = stream.quality.filter(Char::isDigit).toIntOrNull() ?: 0,
                        isHls = stream.type?.equals("hls", true) == true ||
                            body.type?.equals("hls", true) == true ||
                            stream.url.contains(".m3u8"),
                    )
                },
            subtitles = body.subtitles.orEmpty()
                .filter { it.url.isNotBlank() }
                .map {
                    PlayableSubtitle(
                        url = watchBox.subtitleUrl(it.url),
                        language = it.lang,
                        label = it.label.ifBlank { it.lang.uppercase() },
                    )
                },
            hosts = body.servers.orEmpty()
                .filter { it.url.isNotBlank() }
                .map {
                    AlternateHost(
                        label = it.label,
                        url = watchBox.proxiedStreamUrl(it.url),
                        isHls = it.type?.equals("hls", true) == true,
                    )
                },
            audioTracks = body.langs.orEmpty()
                .filter { it.url.isNotBlank() }
                .map { lang ->
                    AudioTrack(
                        language = lang.lang,
                        label = lang.label.ifBlank { lang.lang.uppercase() },
                        url = watchBox.proxiedStreamUrl(lang.url),
                        isHls = lang.type?.equals("hls", true) == true,
                        subtitles = lang.subtitles.orEmpty().map {
                            PlayableSubtitle(
                                url = watchBox.subtitleUrl(it.url),
                                language = it.lang,
                                label = it.label.ifBlank { it.lang.uppercase() },
                            )
                        },
                    )
                },
            introRange = body.introRange(),
            outroRange = body.outroRange(),
        )
    }

    private fun NativeSourcesResponse.introRange(): ClosedRange<Long>? =
        intro?.takeIf { it.isValid }?.let { (it.start * 1000).toLong()..(it.end * 1000).toLong() }

    private fun NativeSourcesResponse.outroRange(): ClosedRange<Long>? =
        outro?.takeIf { it.isValid }?.let { (it.start * 1000).toLong()..(it.end * 1000).toLong() }

    private companion object {
        const val TYPE_BANNER = "BANNER"
        val ROW_TYPES = setOf("SUBJECTS_MOVIE", "APPOINTMENT_LIST")
        const val MAX_HERO = 6
    }
}
