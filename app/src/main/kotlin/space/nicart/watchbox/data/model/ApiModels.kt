package space.nicart.watchbox.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * AOneRoom / MovieBox ("ONEROOM") API DTOs.
 *
 * Field names and nesting are taken from live responses captured from
 * `https://h5-api.aoneroom.com/wefeed-h5api-bff`. Every field is optional with a
 * default because the upstream API omits keys freely and returns `null` for
 * empty collections rather than `[]`.
 */

@Serializable
data class ApiEnvelope<T>(
    val code: Int = -1,
    val message: String? = null,
    val data: T? = null,
) {
    val isOk: Boolean get() = code == 0 && data != null
}

@Serializable
data class ApiImage(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val format: String? = null,
    val thumbnail: String? = null,
    val blurHash: String? = null,
    val avgHueLight: String? = null,
    val avgHueDark: String? = null,
)

/**
 * `subjectType` discriminator used throughout the API.
 * 1 = Movie, 2 = TV series, 5 = Education, 6 = Music, 9 = Sports.
 */
object SubjectType {
    const val MOVIE = 1
    const val TV = 2
    const val EDUCATION = 5
    const val MUSIC = 6
    const val SPORTS = 9

    /** Only movies and series get TMDB enrichment and a full detail page. */
    fun isPlayableMedia(type: Int): Boolean = type == MOVIE || type == TV
}

@Serializable
data class Subject(
    val subjectId: String = "",
    val subjectType: Int = 0,
    val title: String = "",
    val description: String = "",
    val releaseDate: String = "",
    val duration: Int = 0,
    val genre: String = "",
    val cover: ApiImage? = null,
    val countryName: String = "",
    val imdbRatingValue: String = "",
    val imdbRatingCount: Int = 0,
    val subtitles: String = "",
    val hasResource: Boolean = true,
    val detailPath: String = "",
    val season: Int = 0,
    val corner: String = "",
    val postTitle: String = "",
    val appointmentDate: String = "",
    val appointmentCnt: Int = 0,
    val stills: List<ApiImage>? = null,
    val staffList: List<Staff>? = null,
    val trailer: JsonElement? = null,
) {
    val coverUrl: String? get() = cover?.url?.takeIf { it.isNotBlank() }
    val genres: List<String>
        get() = genre.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val year: String? get() = releaseDate.take(4).takeIf { it.length == 4 }
    val isUpcoming: Boolean get() = !hasResource
    val isMovie: Boolean get() = subjectType == SubjectType.MOVIE
    val isSeries: Boolean get() = subjectType == SubjectType.TV
}

@Serializable
data class Staff(
    val staffId: String = "",
    val staffType: Int = 0,
    val name: String = "",
    val character: String = "",
    val avatarUrl: String = "",
    val detailPath: String = "",
)

// ---------------------------------------------------------------- home

@Serializable
data class HomeResponse(
    val platformList: List<PlatformEntry>? = null,
    val operatingList: List<OperatingSection>? = null,
)

@Serializable
data class PlatformEntry(
    val name: String = "",
    val uploadBy: String = "",
)

/**
 * A home-page row. `type` observed in the wild:
 * `BANNER`, `SUBJECTS_MOVIE`, `CUSTOM`, `FILTER`, `SPORT_LIVE`,
 * `APPOINTMENT_LIST`.
 *
 * Only `BANNER` (hero) and `SUBJECTS_MOVIE`/`APPOINTMENT_LIST` (poster rails)
 * are rendered; the rest are skipped.
 */
@Serializable
data class OperatingSection(
    val type: String = "",
    val position: Int = 0,
    val title: String = "",
    val opId: String = "",
    val url: String = "",
    val detailPath: String = "",
    val subjects: List<Subject>? = null,
    val banner: BannerBlock? = null,
)

@Serializable
data class BannerBlock(
    val items: List<BannerItem>? = null,
)

@Serializable
data class BannerItem(
    val id: String = "",
    val title: String = "",
    val image: ApiImage? = null,
    val url: String = "",
    val subjectId: String = "",
    val subjectType: Int = 0,
    val detailPath: String = "",
    val subject: Subject? = null,
)

// ---------------------------------------------------------------- detail

@Serializable
data class DetailResponse(
    val subject: Subject? = null,
    val stars: List<Staff>? = null,
    val resource: ResourceBlock? = null,
    val isForbid: Boolean = false,
    val watchTimeLimit: Int = 0,
)

@Serializable
data class ResourceBlock(
    val seasons: List<SeasonInfo>? = null,
    val source: String = "",
    val uploadBy: String = "",
)

@Serializable
data class SeasonInfo(
    val se: Int = 1,
    val maxEp: Int = 0,
    val allEp: String = "",
    val resolutions: List<ResolutionInfo>? = null,
)

@Serializable
data class ResolutionInfo(
    val resolution: Int = 0,
    val epNum: Int = 0,
)

// ---------------------------------------------------------------- play

@Serializable
data class PlayResponse(
    val streams: List<PlayStream>? = null,
    val dash: List<PlayStream>? = null,
    val hls: List<PlayStream>? = null,
    val freeNum: Int = 0,
    val limited: Boolean = false,
    val limitedCode: String = "",
    val hasResource: Boolean = true,
    val vipLocked: Boolean = false,
)

@Serializable
data class PlayStream(
    val format: String = "",
    val id: String = "",
    val url: String = "",
    val resolutions: String = "",
    val size: String = "",
    val duration: Int = 0,
    val codecName: String = "",
    val signCookie: String = "",
    val signHeaderKey: String = "",
    val vipLocked: Boolean = false,
) {
    /** Numeric height, e.g. `"1080"` -> 1080. Unknown values sort last. */
    val heightOrZero: Int get() = resolutions.filter { it.isDigit() }.toIntOrNull() ?: 0
    val isHls: Boolean get() = format.equals("HLS", true) || url.contains(".m3u8")
}

// ---------------------------------------------------------------- captions

@Serializable
data class CaptionResponse(
    val captions: List<Caption>? = null,
)

@Serializable
data class Caption(
    val id: String = "",
    val lan: String = "",
    val lanName: String = "",
    val url: String = "",
    val size: String = "",
    val delay: Int = 0,
)

// ---------------------------------------------------------------- search / filter

@Serializable
data class SearchRequest(
    val keyword: String,
    val page: String = "1",
    val perPage: Int = 28,
    val subjectType: Int = 0,
)

@Serializable
data class PagedSubjects(
    val items: List<Subject>? = null,
    val subjects: List<Subject>? = null,
    val list: List<Subject>? = null,
    val pager: Pager? = null,
) {
    val results: List<Subject> get() = items ?: subjects ?: list ?: emptyList()
}

@Serializable
data class Pager(
    val hasMore: Boolean = false,
    val nextPage: String = "",
    val page: Int = 0,
    val perPage: Int = 0,
    val totalCount: Int = 0,
)
