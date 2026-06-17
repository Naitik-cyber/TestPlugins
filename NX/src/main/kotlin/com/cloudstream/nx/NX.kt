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
data class ExternalIds(
    @JsonProperty("imdb_id") val imdb_id: String? = null
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
    @JsonProperty("imdb_id") val imdb_id: String? = null,
    @JsonProperty("external_ids") val external_ids: ExternalIds? = null
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
            // Append external_ids to reliably fetch the IMDb ID for TV series
            val rawResponse = app.get("$TMDB_BASE/tv/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=external_ids")
            val data = rawResponse.parsedSafe<TMDBDetail>()
            
            if (data == null) {
                println("NX Plugin Error: Failed parsing TV Details.")
                return null
            }

            val imdbId = data.external_ids?.imdb_id ?: data.imdb_id ?: ""
            val episodes = mutableListOf<Episode>()
            data.seasons?.forEach { season ->
                val seasonNum = season.season_number ?: return@forEach
                if (seasonNum == 0) return@forEach
                val seasonData = app.get(
                    "$TMDB_BASE/tv/$tmdbId/season/$seasonNum?api_key=$TMDB_API_KEY"
                ).parsedSafe<TMDBSeason>()
                
                seasonData?.episodes?.forEach { ep ->
                    val epNum = ep.episode_number ?: return@forEach
                    // Pass the IMDb ID at the end of the metadata token string
                    episodes.add(
                        newEpisode("$tmdbId|tv|$seasonNum|$epNum|$imdbId") {
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
                println("NX Plugin Error: Failed parsing Movie Details.")
                return null
            }

            val imdbId = data.imdb_id ?: ""
            newMovieLoadResponse(
                data.title ?: "Unknown Movie",
                url,
                TvType.Movie,
                "$tmdbId|movie|1|1|$imdbId"
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
        val season = parts.getOrNull(2) ?: "1"
        val episode = parts.getOrNull(3) ?: "1"
        val imdbId = parts.getOrNull(4) ?: ""

        val targetServers = listOf(
            "MbPly-[Multi-Lang]", "ZetPly-[Multi-Lang]", "OrVid-[Multi-Lang]", 
            "QsPly-[Multi-Lang]", "Xuhd-[Multi-Lang]", "Ophm", 
            "Multi-Kil-[Multi-Lang]", "Omen", "YFLIX", "Neon", "Cypher", 
            "Breach", "Vyse", "Fade", "Raze", "River", "VidLnx", "RPM", 
            "MU4", "Rive-Ophim", "Gbru", "HindiSk", "Prvibd", "AsiaLug", 
            "WbStrmr", "Vnst-Ophim", "Vnst-Alfa", "Vnst-Beta", "Vnst-Lamda", 
            "Vnst-Prime", "Vnst-Gama", "Vnst-Sigma", "Vnst-Hexa", "Vnst-Catflix"
        )

        // Try using IMDb ID first; fall back to TMDb ID if unavailable
        val preferredId = if (imdbId.isNotEmpty() && imdbId.startsWith("tt")) imdbId else tmdbId

        val baseUrls = if (type == "tv") {
            listOf(
                "$mainUrl/embed/tv/$preferredId/$season/$episode",
                "$mainUrl/embed/tv/$tmdbId/$season/$episode"
            )
        } else {
            listOf(
                "$mainUrl/embed/movie/$preferredId",
                "$mainUrl/embed/movie/$tmdbId"
            )
        }

        val results = targetServers.amap { serverName ->
            var localFound = false
            
            // Loop through both ID types to ensure compatibility
            for (baseUrl in baseUrls) {
                if (localFound) break
                try {
                    val encodedServer = java.net.URLEncoder.encode(serverName, "UTF-8")
                    val targetUrl = "$baseUrl?server=$encodedServer&one_server=true&lang=en"
                    
                    val rawHtml = app.get(
                        targetUrl,
                        referer = mainUrl,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                        )
                    ).text

                    // Critical step: unescape JSON slashes so standard regex statements can read them
                    val html = rawHtml.replace("\\/", "/")

                    // Strategy 1: Sub-Iframe Source Extraction
                    val iframeRegex = """<iframe[^>]+src=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
                    iframeRegex.findAll(html).forEach { match ->
                        var iframeUrl = match.groupValues[1]
                        if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"
                        if (iframeUrl.startsWith("/")) iframeUrl = "$mainUrl$iframeUrl"
                        
                        if (loadExtractor(iframeUrl, targetUrl, subtitleCallback, callback)) {
                            localFound = true
                        } else {
                            // Fallback: If no system extractor handles the domain, scrape the target iframe directly
                            try {
                                val subHtml = app.get(iframeUrl, referer = targetUrl).text.replace("\\/", "/")
                                """https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""".toRegex(RegexOption.IGNORE_CASE)
                                    .findAll(subHtml).forEach { subMatch ->
                                        callback(
                                            newExtractorLink(
                                                source = name,
                                                name = "NX $serverName (Direct)",
                                                url = subMatch.value.trim(),
                                                type = ExtractorLinkType.M3U8
                                            ) {
                                                this.referer = iframeUrl
                                                this.quality = Qualities.Unknown.value
                                            }
                                        )
                                        localFound = true
                                    }
                            } catch (inner: Exception) { }
                        }
                    }

                    // Strategy 2: Direct-To-Node Manifest Mapping Injection
                    val m3u8Regex = """https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""".toRegex(RegexOption.IGNORE_CASE)
                    m3u8Regex.findAll(html).forEach { match ->
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "NX $serverName",
                                url = match.value.trim(),
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = targetUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        localFound = true
                    }
                } catch (e: Exception) {
                    println("NX Production Engine: Node skipped [$serverName]: ${e.message}")
                }
            }
            localFound
        }

        return results.any { it }
    }
}

@CloudstreamPlugin
class NXPlugin : Plugin() {
    override fun load(manager: Context) {
        registerMainAPI(NX())
    }
}
