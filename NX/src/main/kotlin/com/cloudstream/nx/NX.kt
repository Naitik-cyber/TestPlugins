package com.cloudstream.nx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import android.content.Context

const val TMDB_API_KEY = "d48c912adb725b6424a3ce88671982b9"
const val TMDB_BASE = "https://api.themoviedb.org/3"
const val TMDB_IMAGE = "https://image.tmdb.org/t/p/w500"

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBResponse(
    @JsonProperty("results") val results: List<TMDBItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val poster_path: String? = null,
    @JsonProperty("media_type") val media_type: String? = null,
    @JsonProperty("release_date") val release_date: String? = null,
    @JsonProperty("first_air_date") val first_air_date: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val poster_path: String? = null,
    @JsonProperty("backdrop_path") val backdrop_path: String? = null,
    @JsonProperty("release_date") val release_date: String? = null,
    @JsonProperty("first_air_date") val first_air_date: String? = null,
    @JsonProperty("seasons") val seasons: List<TMDBSeason>? = null,
    @JsonProperty("imdb_id") val imdb_id: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBSeason(
    @JsonProperty("season_number") val season_number: Int? = null,
    @JsonProperty("episodes") val episodes: List<TMDBEpisode>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBEpisode(
    @JsonProperty("episode_number") val episode_number: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val still_path: String? = null
)

fun TMDBItem.toSearchResponse(api: MainAPI, forcedType: String? = null): SearchResponse? {
    val tmdbId = id ?: return null
    val type = forcedType ?: if (media_type == "tv") "tv" else "movie"
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
        val response = app.get(url).parsedSafe<TMDBResponse>()
            ?: return newHomePageResponse(request.name, emptyList())
        
        val forcedType = when {
            request.data.contains("/tv/") -> "tv"
            request.data.contains("/movie/") -> "movie"
            else -> null
        }
        
        val items = response.results?.mapNotNull { it.toSearchResponse(this, forcedType) } ?: emptyList()
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
        val type = parts.getOrNull(1) ?: "movie"

        return if (type == "tv") {
            val rawResponse = app.get("$TMDB_BASE/tv/$tmdbId?api_key=$TMDB_API_KEY")
            val data = rawResponse.parsedSafe<TMDBDetail>()
            
            if (data == null) {
                println("NX Plugin Error: Failed parsing TV Details. Raw payload: ${rawResponse.text}")
                return null
            }

            val episodes = mutableListOf<Episode>()
            data.seasons?.forEach { season ->
                val seasonNum = season.season_number ?: return@forEach
                if (seasonNum == 0) return@forEach
                val seasonData = app.get(
                    "$TMDB_BASE/tv/$tmdbId/season/$seasonNum?api_key=$TMDB_API_KEY"
                ).parsedSafe<TMDBSeason>()
                
                seasonData?.episodes?.forEach { ep ->
                    val epNum = ep.episode_number ?: return@forEach
                    episodes.add(
                        newEpisode("$tmdbId|tv|$seasonNum|$epNum") {
                            this.name = ep.name
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = ep.still_path?.let { "$TMDB_IMAGE$it" }
                            this.description = ep.overview
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(
                data.name ?: "Unknown TV Show",
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = data.poster_path?.let { "$TMDB_IMAGE$it" }
                this.backgroundPosterUrl = data.backdrop_path?.let { "$TMDB_IMAGE$it" }
                this.plot = data.overview
                this.year = data.first_air_date?.take(4)?.toIntOrNull()
            }
        } else {
            val rawResponse = app.get("$TMDB_BASE/movie/$tmdbId?api_key=$TMDB_API_KEY")
            val data = rawResponse.parsedSafe<TMDBDetail>()
            
            if (data == null) {
                println("NX Plugin Error: Failed parsing Movie Details. Raw payload: ${rawResponse.text}")
                return null
            }

            newMovieLoadResponse(
                data.title ?: "Unknown Movie",
                url,
                TvType.Movie,
                "$tmdbId|movie"
            ) {
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
        val tmdbId = parts.getOrNull(0) ?: return false
        val type = parts.getOrNull(1) ?: "movie"
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

            val rawHtml = app.get(
                embedUrl,
                referer = mainUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Origin" to mainUrl,
                    "Accept" to "*/*"
                )
            ).text

            val cleanHtml = rawHtml
                .replace("\\/", "/")
                .replace("\\u0026", "&")

            if (!cleanHtml.contains("mountainviewfinance") && !cleanHtml.contains(".m3u8")) {
                println("NX Embed Content Debug: $cleanHtml")
            }

            val videoRegex = """https?://[^\s"'<>\\]+\.(?:m3u8|mp4|txt)[^\s"'<>\\]*""".toRegex(RegexOption.IGNORE_CASE)
            val cdnRegex = """https?://syd\.mountainviewfinance\.cfd/[^\s"'>\\]+""".toRegex(RegexOption.IGNORE_CASE)

            val allMatches = (videoRegex.findAll(cleanHtml) + cdnRegex.findAll(cleanHtml))
                .map { it.value.trim() }
                .distinct()

            allMatches.forEach { streamUrl ->
                if (streamUrl.length > 20) {
                    val isM3u8 = streamUrl.contains(".m3u8", true) || 
                                 streamUrl.contains(".txt", true) || 
                                 streamUrl.contains("cf-master", true)

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "NXSHA CDN Mirror",
                            url = streamUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://nxsha.space/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                                "Origin" to mainUrl
                            )
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

@CloudstreamPlugin
class NXPlugin : Plugin() {
    override fun load(manager: Context) {
        registerMainAPI(NX())
    }
}
