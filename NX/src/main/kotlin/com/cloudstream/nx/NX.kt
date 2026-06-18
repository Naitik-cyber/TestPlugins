package com.cloudstream.nx

import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

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
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBDetail(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("seasons") val seasons: List<TMDBSeason>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBSeason(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episodes") val episodes: List<TMDBEpisode>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBEpisode(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null
)

fun TMDBItem.toSearchResponse(api: MainAPI, forcedType: String? = null): SearchResponse? {
    val tmdbId = id ?: return null
    val type = forcedType ?: if (mediaType == "tv") "tv" else "movie"
    val displayTitle = title ?: name ?: return null
    val poster = posterPath?.let { "$TMDB_IMAGE$it" }

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
        "$TMDB_BASE/trending/all/week?api_key=$TMDB_API_KEY" to "Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("${request.data}&page=$page").parsedSafe<TMDBResponse>()
            ?: return newHomePageResponse(request.name, emptyList())

        val forcedType = when {
            request.data.contains("/tv/") -> "tv"
            request.data.contains("/movie/") -> "movie"
            else -> null
        }

        val items = response.results?.mapNotNull {
            it.toSearchResponse(this, forcedType)
        } ?: emptyList()

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.replace(" ", "+")
        val response = app.get("$TMDB_BASE/search/multi?api_key=$TMDB_API_KEY&query=$fixedQuery")
            .parsedSafe<TMDBResponse>() ?: return emptyList()

        return response.results?.mapNotNull {
            it.toSearchResponse(this)
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("|")
        val tmdbId = parts.getOrNull(0) ?: return null
        val type = parts.getOrNull(1) ?: "movie"

        return if (type == "tv") {
            val data = app.get("$TMDB_BASE/tv/$tmdbId?api_key=$TMDB_API_KEY")
                .parsedSafe<TMDBDetail>() ?: return null

            val episodes = mutableListOf<Episode>()

            data.seasons?.forEach { seasonItem ->
                val seasonNum = seasonItem.seasonNumber ?: return@forEach
                if (seasonNum == 0) return@forEach

                val seasonData = app.get("$TMDB_BASE/tv/$tmdbId/season/$seasonNum?api_key=$TMDB_API_KEY")
                    .parsedSafe<TMDBSeason>()

                seasonData?.episodes?.forEach { ep ->
                    val epNum = ep.episodeNumber ?: return@forEach

                    episodes.add(
                        newEpisode("$tmdbId|tv|$seasonNum|$epNum") {
                            this.name = ep.name
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = ep.stillPath?.let { "$TMDB_IMAGE$it" }
                            this.description = ep.overview
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(data.name ?: "Unknown", url, TvType.TvSeries, episodes) {
                this.posterUrl = data.posterPath?.let { "$TMDB_IMAGE$it" }
                this.backgroundPosterUrl = data.backdropPath?.let { "$TMDB_IMAGE$it" }
                this.plot = data.overview
                this.year = data.firstAirDate?.take(4)?.toIntOrNull()
            }
        } else {
            val data = app.get("$TMDB_BASE/movie/$tmdbId?api_key=$TMDB_API_KEY")
                .parsedSafe<TMDBDetail>() ?: return null

            newMovieLoadResponse(data.title ?: "Unknown", url, TvType.Movie, "$tmdbId|movie|1|1") {
                this.posterUrl = data.posterPath?.let { "$TMDB_IMAGE$it" }
                this.backgroundPosterUrl = data.backdropPath?.let { "$TMDB_IMAGE$it" }
                this.plot = data.overview
                this.year = data.releaseDate?.take(4)?.toIntOrNull()
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

        val servers = listOf(
            "MbPly-[Multi-Lang]",
            "ZetPly-[Multi-Lang]",
            "OrVid-[Multi-Lang]",
            "QsPly-[Multi-Lang]",
            "Xuhd-[Multi-Lang]",
            "Ophm",
            "Multi-Kil-[Multi-Lang]",
            "Omen",
            "YFLIX",
            "Neon",
            "Cypher",
            "Breach",
            "Vyse",
            "Fade",
            "Raze",
            "River",
            "VidLnx",
            "RPM",
            "MU4",
            "Rive-Ophim",
            "Gbru",
            "Hindisk"
        )

        val baseEmbed = if (type == "tv") {
            "$mainUrl/embed/tv/$tmdbId/$season/$episode"
        } else {
            "$mainUrl/embed/movie/$tmdbId"
        }

        var found = false

        for (server in servers) {
            val encodedServer = URLEncoder.encode(server, "UTF-8")
            val embedUrl = "$baseEmbed?server=$encodedServer&one_server=true"

            try {
                println("NX: Trying official embed $embedUrl")

                if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) {
                    found = true
                    println("NX: Found links from $server")
                }
            } catch (e: Exception) {
                println("NX: Failed $server - ${e.message}")
            }
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
