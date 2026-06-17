package com.cloudstream.nx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

const val TMDB_API_KEY = "YOUR_TMDB_API_KEY_HERE"
const val TMDB_BASE = "https://api.themoviedb.org/3"
const val TMDB_IMAGE = "https://image.tmdb.org/t/p/w500"

// ── TMDB search/browse ──────────────────────────────────────────────────────

data class TMDBResponse(
    val results: List<TMDBItem>? = null
)

data class TMDBItem(
    val id: Int? = null,
    val title: String? = null,
    val name: String? = null,
    val poster_path: String? = null,
    val media_type: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null
)

// ── TMDB detail — two separate shapes ──────────────────────────────────────

// Shape returned by /movie/{id}
data class TMDBMovieDetail(
    val id: Int? = null,
    val title: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null
)

// Shape returned by /tv/{id}  (seasons field has NO episodes here)
data class TMDBTvDetail(
    val id: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val first_air_date: String? = null,
    val seasons: List<TMDBSeasonSummary>? = null   // summary only
)

// Season summary inside /tv/{id}
data class TMDBSeasonSummary(
    val season_number: Int? = null,
    val episode_count: Int? = null
)

// Full season returned by /tv/{id}/season/{n}
data class TMDBSeasonDetail(
    val season_number: Int? = null,
    val episodes: List<TMDBEpisode>? = null
)

data class TMDBEpisode(
    val episode_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null
)

// ── Main provider ───────────────────────────────────────────────────────────

class NX : MainAPI() {
    override var mainUrl = "https://nxsha.space"
    override var name = "NX"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$TMDB_BASE/movie/popular?api_key=$TMDB_API_KEY"      to "Popular Movies",
        "$TMDB_BASE/tv/popular?api_key=$TMDB_API_KEY"         to "Popular TV Shows",
        "$TMDB_BASE/movie/top_rated?api_key=$TMDB_API_KEY"    to "Top Rated Movies",
        "$TMDB_BASE/trending/all/week?api_key=$TMDB_API_KEY"  to "Trending",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page"
        val response = app.get(url).parsedSafe<TMDBResponse>()
            ?: return newHomePageResponse(request.name, emptyList())
        val items = response.results?.mapNotNull { it.toSearchResponse(this) } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$TMDB_BASE/search/multi?api_key=$TMDB_API_KEY&query=${query.replace(" ", "+")}"
        val response = app.get(url).parsedSafe<TMDBResponse>() ?: return emptyList()
        return response.results?.mapNotNull { it.toSearchResponse(this) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("|")
        val tmdbId = parts.getOrNull(0) ?: return null
        val type   = parts.getOrNull(1) ?: "movie"

        return if (type == "tv") loadTv(tmdbId, url) else loadMovie(tmdbId, url)
    }

    // ── Movie ──────────────────────────────────────────────────────────────

    private suspend fun loadMovie(tmdbId: String, url: String): LoadResponse? {
        val data = app.get("$TMDB_BASE/movie/$tmdbId?api_key=$TMDB_API_KEY")
            .parsedSafe<TMDBMovieDetail>() ?: return null

        return newMovieLoadResponse(
            data.title ?: return null,
            url,
            TvType.Movie,
            "$tmdbId|movie"
        ) {
            posterUrl          = data.poster_path?.let { "$TMDB_IMAGE$it" }
            backgroundPosterUrl = data.backdrop_path?.let { "$TMDB_IMAGE$it" }
            plot               = data.overview
            year               = data.release_date?.take(4)?.toIntOrNull()
        }
    }

    // ── TV Series ─────────────────────────────────────────────────────────

    private suspend fun loadTv(tmdbId: String, url: String): LoadResponse? {
        // Step 1: get show metadata + season list (no episodes yet)
        val show = app.get("$TMDB_BASE/tv/$tmdbId?api_key=$TMDB_API_KEY")
            .parsedSafe<TMDBTvDetail>() ?: return null

        // Step 2: fetch each real season individually
        val episodes = mutableListOf<Episode>()
        show.seasons
            ?.filter { (it.season_number ?: 0) > 0 }   // skip specials (season 0)
            ?.forEach { summary ->
                val seasonNum = summary.season_number ?: return@forEach
                val seasonData = app.get(
                    "$TMDB_BASE/tv/$tmdbId/season/$seasonNum?api_key=$TMDB_API_KEY"
                ).parsedSafe<TMDBSeasonDetail>() ?: return@forEach

                seasonData.episodes?.forEach { ep ->
                    episodes.add(
                        newEpisode("$tmdbId|tv|$seasonNum|${ep.episode_number}") {
                            name        = ep.name
                            season      = seasonNum
                            episode     = ep.episode_number
                            posterUrl   = ep.still_path?.let { "$TMDB_IMAGE$it" }
                            description = ep.overview
                        }
                    )
                }
            }

        return newTvSeriesLoadResponse(
            show.name ?: return null,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl           = show.poster_path?.let { "$TMDB_IMAGE$it" }
            backgroundPosterUrl = show.backdrop_path?.let { "$TMDB_IMAGE$it" }
            plot                = show.overview
            year                = show.first_air_date?.take(4)?.toIntOrNull()
        }
    }

    // ── Link extraction ───────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts   = data.split("|")
        val tmdbId  = parts.getOrNull(0) ?: return false
        val type    = parts.getOrNull(1) ?: "movie"
        val season  = parts.getOrNull(2)?.toIntOrNull() ?: 1
        val episode = parts.getOrNull(3)?.toIntOrNull() ?: 1

        val embedUrl = if (type == "tv") {
            "$mainUrl/embed?tmdbId=$tmdbId&type=tv&s=$season&e=$episode&autoplay=true"
        } else {
            "$mainUrl/embed?tmdbId=$tmdbId&type=movie&autoplay=true"
        }

        return extractFromEmbed(embedUrl, subtitleCallback, callback)
    }

    private suspend fun extractFromEmbed(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) return true

            val html = app.get(
                embedUrl,
                referer = mainUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Origin"     to mainUrl
                )
            ).text

            // Match HLS .txt/.woff2 streams from syd.mountainviewfinance.cfd
            Regex(
                """https?://[^\s"'<>\\]+\.(?:m3u8|mp4|txt|woff2)[^\s"'<>\\]*""",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { match ->
                val streamUrl = match.value
                    .replace("\\u0026", "&")
                    .replace("\\/", "/")
                    .trim()
                if (streamUrl.length > 20) {
                    val isM3u8 = streamUrl.contains(".m3u8", true)
                            || streamUrl.contains(".txt",   true)
                            || streamUrl.contains(".woff2", true)
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = name,
                            url    = streamUrl,
                            type   = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = embedUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return found
    }
}

// ── Search response helper ──────────────────────────────────────────────────

fun TMDBItem.toSearchResponse(api: MainAPI): SearchResponse? {
    val tmdbId       = id ?: return null
    val type         = if (media_type == "tv") "tv" else "movie"
    val displayTitle = title ?: name ?: return null
    val poster       = poster_path?.let { "$TMDB_IMAGE$it" }

    return if (type == "tv") {
        api.newTvSeriesSearchResponse(displayTitle, "$tmdbId|tv", TvType.TvSeries, false) {
            posterUrl = poster
        }
    } else {
        api.newMovieSearchResponse(displayTitle, "$tmdbId|movie", TvType.Movie, false) {
            posterUrl = poster
        }
    }
}

// ── Plugin entry point ──────────────────────────────────────────────────────

@CloudstreamPlugin
class NXPlugin : Plugin() {
    override fun load(manager: Context) {
        registerMainAPI(NX())
    }
}
