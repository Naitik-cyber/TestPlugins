package com.cloudstream.nx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

// Replace with your TMDB API key
const val TMDB_API_KEY = "d48c912adb725b6424a3ce88671982b9"
const val TMDB_BASE = "https://api.themoviedb.org/3"
const val TMDB_IMAGE = "https://image.tmdb.org/t/p/w500"

class NX : MainAPI() {
    override var mainUrl = "https://nxsha.space"
    override var name = "NX"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$TMDB_BASE/movie/popular?api_key=$TMDB_API_KEY" to "Popular Movies",
        "$TMDB_BASE/tv/popular?api_key=$TMDB_API_KEY" to "Popular TV Shows",
        "$TMDB_BASE/movie/top_rated?api_key=$TMDB_API_KEY" to "Top Rated Movies",
        "$TMDB_BASE/trending/all/week?api_key=$TMDB_API_KEY" to "Trending",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page"
        val response = app.get(url).parsedSafe<TMDBResponse>() ?: return newHomePageResponse(request.name, emptyList())
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
        val tmdbId = parts[0]
        val type = parts[1]

        return if (type == "tv") {
            val data = app.get("$TMDB_BASE/tv/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=seasons").parsedSafe<TMDBDetail>() ?: return null
            val episodes = mutableListOf<Episode>()
            data.seasons?.forEach { season ->
                val seasonNum = season.season_number ?: return@forEach
                if (seasonNum == 0) return@forEach
                val seasonData = app.get("$TMDB_BASE/tv/$tmdbId/season/$seasonNum?api_key=$TMDB_API_KEY").parsedSafe<TMDBSeason>()
                seasonData?.episodes?.forEach { ep ->
                    episodes.add(newEpisode("$tmdbId|tv|$seasonNum|${ep.episode_number}") {
                        this.name = ep.name
                        this.season = seasonNum
                        this.episode = ep.episode_number
                        this.posterUrl = ep.still_path?.let { "$TMDB_IMAGE$it" }
                        this.description = ep.overview
                    })
                }
            }
            newTvSeriesLoadResponse(data.name ?: return null, url, TvType.TvSeries, episodes) {
                this.posterUrl = data.poster_path?.let { "$TMDB_IMAGE$it" }
                this.backgroundPosterUrl = data.backdrop_path?.let { "$TMDB_IMAGE$it" }
                this.plot = data.overview
                this.year = data.first_air_date?.take(4)?.toIntOrNull()
            }
        } else {
            val data = app.get("$TMDB_BASE/movie/$tmdbId?api_key=$TMDB_API_KEY").parsedSafe<TMDBDetail>() ?: return null
            newMovieLoadResponse(data.title ?: return null, url, TvType.Movie, "$tmdbId|movie") {
                this.posterUrl = data.poster_path?.let { "$TMDB_IMAGE$it" }
                this.backgroundPosterUrl = data.backdrop_path?.let { "$TMDB_IMAGE$it" }
                this.plot = data.overview
                this.year = data.release_date?.take(4)?.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val tmdbId = parts[0]
        val type = parts[1]
        val season = parts.getOrNull(2)?.toIntOrNull() ?: 1
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
                    "Origin" to mainUrl
                )
            ).text

            Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4|txt)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .forEach { match ->
                    val streamUrl = match.value.replace("\\u0026", "&").replace("\\/", "/").trim()
                    if (streamUrl.length > 20) {
                        val isM3u8 = streamUrl.contains(".m3u8", true) || streamUrl.contains(".txt", true)
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = streamUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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

    // TMDB Data Classes
    data class TMDBResponse(val results: List<TMDBItem>?)
    data class TMDBItem(
        val id: Int?,
        val title: String?,
        val name: String?,
        val poster_path: String?,
        val media_type: String?,
        val release_date: String?,
        val first_air_date: String?
    ) {
       fun toSearchResponse(api: MainAPI): SearchResponse? {
    val tmdbId = id ?: return null
    val type = if (media_type == "tv") "tv" else "movie"
    val displayTitle = title ?: name ?: return null
    val poster = poster_path?.let { "$TMDB_IMAGE$it" }
    return if (type == "tv") {
        api.newTvSeriesSearchResponse(displayTitle, "$tmdbId|tv", TvType.TvSeries, false) {
            this.posterUrl = poster
        }
    } else {
        api.newMovieSearchResponse(displayTitle, "$tmdbId|movie", TvType.Movie, false) {
            this.posterUrl = poster
        }
    }
}
    }
    data class TMDBDetail(
        val id: Int?,
        val title: String?,
        val name: String?,
        val overview: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val release_date: String?,
        val first_air_date: String?,
        val seasons: List<TMDBSeason>?
    )
    data class TMDBSeason(
        val season_number: Int?,
        val episodes: List<TMDBEpisode>?
    )
    data class TMDBEpisode(
        val episode_number: Int?,
        val name: String?,
        val overview: String?,
        val still_path: String?
    )
}

@CloudstreamPlugin
class NXPlugin : Plugin() {
    override fun load(manager: Context) {
        registerMainAPI(NX())
    }
}
