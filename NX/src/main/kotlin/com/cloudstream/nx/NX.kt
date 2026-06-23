package com.nx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response

class NXProvider : MainAPI() {

    override var mainUrl              = "https://www.themoviedb.org"
    override var name                 = "NX"
    override var lang                 = "en"
    override val hasMainPage          = true
    override val hasSearch            = true
    override val supportedTypes       = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AnimeMovie,
        TvType.Anime,
    )

    // ─── TMDB ────────────────────────────────────────────────────────────────
    private val tmdbApiKey   = "d48c912adb725b6424a3ce88671982b9"
    private val tmdbBase     = "https://api.themoviedb.org/3"
    private val tmdbImageBase = "https://image.tmdb.org/t/p/w500"

    private suspend fun tmdbGet(path: String, params: Map<String, String> = emptyMap()): String {
        val query = (params + mapOf("api_key" to tmdbApiKey))
            .entries.joinToString("&") { "${it.key}=${it.value}" }
        return app.get("$tmdbBase$path?$query").text
    }

    // ─── DATA CLASSES ────────────────────────────────────────────────────────
    data class TmdbResult(
        val id: Int,
        val title: String?,
        val name: String?,
        val overview: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val vote_average: Double?,
        val release_date: String?,
        val first_air_date: String?,
        val media_type: String?,
        val genre_ids: List<Int>?,
    )

    data class TmdbResponse(val results: List<TmdbResult>)

    data class TmdbMovieDetail(
        val id: Int,
        val title: String?,
        val overview: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val vote_average: Double?,
        val release_date: String?,
        val runtime: Int?,
        val genres: List<TmdbGenre>?,
        val tagline: String?,
        val status: String?,
        val imdb_id: String?,
    )

    data class TmdbSeriesDetail(
        val id: Int,
        val name: String?,
        val overview: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val vote_average: Double?,
        val first_air_date: String?,
        val number_of_seasons: Int?,
        val number_of_episodes: Int?,
        val genres: List<TmdbGenre>?,
        val tagline: String?,
        val status: String?,
        val external_ids: TmdbExternalIds?,
        val seasons: List<TmdbSeason>?,
    )

    data class TmdbSeason(
        val id: Int,
        val season_number: Int,
        val episode_count: Int,
        val name: String?,
        val overview: String?,
        val poster_path: String?,
        val air_date: String?,
    )

    data class TmdbSeasonDetail(
        val season_number: Int,
        val episodes: List<TmdbEpisode>?,
    )

    data class TmdbEpisode(
        val id: Int,
        val episode_number: Int,
        val season_number: Int,
        val name: String?,
        val overview: String?,
        val still_path: String?,
        val vote_average: Double?,
        val air_date: String?,
    )

    data class TmdbGenre(val id: Int, val name: String)

    data class TmdbExternalIds(
        val imdb_id: String?,
        val tvdb_id: Int?,
    )

    data class TmdbCredits(val cast: List<TmdbCast>)
    data class TmdbCast(val name: String, val character: String?, val profile_path: String?)

    // ─── HOMEPAGE SECTIONS ───────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$tmdbBase/trending/all/week?api_key=$tmdbApiKey"        to "🔥 Trending This Week",
        "$tmdbBase/movie/popular?api_key=$tmdbApiKey"            to "🎬 Popular Movies",
        "$tmdbBase/tv/popular?api_key=$tmdbApiKey"               to "📺 Popular Series",
        "$tmdbBase/movie/top_rated?api_key=$tmdbApiKey"          to "⭐ Top Rated Movies",
        "$tmdbBase/tv/top_rated?api_key=$tmdbApiKey"             to "🏆 Top Rated Series",
        "$tmdbBase/movie/now_playing?api_key=$tmdbApiKey"        to "🆕 Now Playing",
        "$tmdbBase/tv/on_the_air?api_key=$tmdbApiKey"            to "📡 Currently Airing",
        "$tmdbBase/movie/upcoming?api_key=$tmdbApiKey"           to "📅 Upcoming Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page"
        val json = app.get(url).text
        val data = parseJson<TmdbResponse>(json)

        val items = data.results.mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ─── SEARCH ──────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val json = tmdbGet("/search/multi", mapOf("query" to query, "page" to "1"))
        val data = parseJson<TmdbResponse>(json)
        return data.results
            .filter { it.media_type != "person" }
            .mapNotNull { it.toSearchResult() }
    }

    // ─── LOAD (metadata) ─────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        // url format: tmdb://movie/{id}  or  tmdb://tv/{id}
        val parts = url.removePrefix("tmdb://").split("/")
        if (parts.size < 2) return null
        val type = parts[0]   // "movie" or "tv"
        val id   = parts[1]

        return if (type == "movie") loadMovie(id) else loadSeries(id)
    }

    private suspend fun loadMovie(id: String): MovieLoadResponse? {
        val json = tmdbGet("/movie/$id", mapOf("append_to_response" to "credits,external_ids"))
        val d    = parseJson<TmdbMovieDetail>(json)
        val credJson = tmdbGet("/movie/$id/credits")
        val cred     = parseJson<TmdbCredits>(credJson)

        return newMovieLoadResponse(
            name    = d.title ?: return null,
            url     = "tmdb://movie/$id",
            type    = TvType.Movie,
            dataUrl = "tmdb://movie/$id/play",
        ) {
            this.posterUrl   = d.poster_path?.let { "$tmdbImageBase$it" }
            this.backgroundPosterUrl = d.backdrop_path?.let { "$tmdbImageBase$it" }
            this.plot        = buildDescription(d.overview, d.tagline, d.status, d.runtime, null, null)
            this.year        = d.release_date?.take(4)?.toIntOrNull()
            this.rating      = d.vote_average?.times(10)?.toInt()
            this.tags        = d.genres?.map { it.name } ?: emptyList()
            this.actors      = cred.cast.take(15).map {
                ActorData(Actor(it.name, it.profile_path?.let { p -> "$tmdbImageBase$p" }), roleString = it.character)
            }
            this.recommendations = fetchRecommendations("movie", id)
        }
    }

    private suspend fun loadSeries(id: String): TvSeriesLoadResponse? {
        val json = tmdbGet("/tv/$id", mapOf("append_to_response" to "credits,external_ids"))
        val d    = parseJson<TmdbSeriesDetail>(json)
        val credJson = tmdbGet("/tv/$id/credits")
        val cred     = parseJson<TmdbCredits>(credJson)

        val episodes = mutableListOf<Episode>()
        d.seasons?.filter { it.season_number > 0 }?.forEach { season ->
            val sJson = tmdbGet("/tv/$id/season/${season.season_number}")
            val sData = parseJson<TmdbSeasonDetail>(sJson)
            sData.episodes?.forEach { ep ->
                episodes.add(
                    newEpisode("tmdb://tv/$id/season/${ep.season_number}/episode/${ep.episode_number}") {
                        this.name          = ep.name
                        this.season        = ep.season_number
                        this.episode       = ep.episode_number
                        this.description   = ep.overview
                        this.posterUrl     = ep.still_path?.let { "$tmdbImageBase$it" }
                        this.rating        = ep.vote_average?.times(10)?.toInt()
                        this.date          = ep.air_date
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(
            name     = d.name ?: return null,
            url      = "tmdb://tv/$id",
            type     = TvType.TvSeries,
            episodes = episodes,
        ) {
            this.posterUrl           = d.poster_path?.let { "$tmdbImageBase$it" }
            this.backgroundPosterUrl = d.backdrop_path?.let { "$tmdbImageBase$it" }
            this.plot        = buildDescription(d.overview, d.tagline, d.status, null, d.number_of_seasons, d.number_of_episodes)
            this.year        = d.first_air_date?.take(4)?.toIntOrNull()
            this.rating      = d.vote_average?.times(10)?.toInt()
            this.tags        = d.genres?.map { it.name } ?: emptyList()
            this.actors      = cred.cast.take(15).map {
                ActorData(Actor(it.name, it.profile_path?.let { p -> "$tmdbImageBase$p" }), roleString = it.character)
            }
            this.recommendations = fetchRecommendations("tv", id)
        }
    }

    // ─── LOAD LINKS (stub — scraper goes here later) ──────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // TODO: Add scraper sources here in the next step
        return false
    }

    // ─── HELPERS ─────────────────────────────────────────────────────────────
    private fun TmdbResult.toSearchResult(): SearchResponse? {
        val resolvedType = media_type ?: if (title != null) "movie" else "tv"
        val resolvedTitle = title ?: name ?: return null
        val poster = poster_path?.let { "$tmdbImageBase$it" }
        val url = "tmdb://$resolvedType/$id"

        return if (resolvedType == "movie") {
            newMovieSearchResponse(resolvedTitle, url, TvType.Movie) {
                this.posterUrl = poster
                this.year      = release_date?.take(4)?.toIntOrNull()
                this.quality   = getRating(vote_average)
            }
        } else {
            newTvSeriesSearchResponse(resolvedTitle, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year      = first_air_date?.take(4)?.toIntOrNull()
                this.quality   = getRating(vote_average)
            }
        }
    }

    private fun getRating(score: Double?): SearchQuality {
        return when {
            score == null        -> SearchQuality.HD
            score >= 8.0         -> SearchQuality.FourK
            score >= 7.0         -> SearchQuality.HD
            else                 -> SearchQuality.SD
        }
    }

    private fun buildDescription(
        overview: String?,
        tagline: String?,
        status: String?,
        runtime: Int?,
        seasons: Int?,
        episodes: Int?,
    ): String {
        return buildString {
            if (!tagline.isNullOrBlank()) append("\"$tagline\"\n\n")
            if (!overview.isNullOrBlank()) append(overview)
            append("\n\n")
            if (status != null)   append("Status: $status  ")
            if (runtime != null)  append("Runtime: ${runtime}min  ")
            if (seasons != null)  append("Seasons: $seasons  ")
            if (episodes != null) append("Episodes: $episodes")
        }.trim()
    }

    private suspend fun fetchRecommendations(type: String, id: String): List<SearchResponse> {
        return try {
            val json = tmdbGet("/$type/$id/recommendations", mapOf("page" to "1"))
            parseJson<TmdbResponse>(json).results.mapNotNull {
                it.copy(media_type = type).toSearchResult()
            }.take(12)
        } catch (e: Exception) { emptyList() }
    }
}
