package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import android.util.Base64
import org.jsoup.nodes.Document

class PopcornMoviesProvider : MainAPI() {

    override var mainUrl              = "https://popcornmovies.org"
    override var name                 = "PopcornMovies"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    // ── TMDB ─────────────────────────────────────────────────────────────────
    private val tmdbKey    = "d48c912adb725b6424a3ce88671982b9"
    private val tmdbBase   = "https://api.themoviedb.org/3"
    private val tmdbImg    = "https://image.tmdb.org/t/p/w500"
    private val tmdbImgBg  = "https://image.tmdb.org/t/p/w1280"

    // ── HEADERS ───────────────────────────────────────────────────────────────
    private val pcHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer"    to "$mainUrl/",
        "Origin"     to mainUrl
    )

    // ── MAIN PAGE ─────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$tmdbBase/movie/popular?api_key=$tmdbKey&language=en-US&page="       to "Popular Movies",
        "$tmdbBase/trending/movie/week?api_key=$tmdbKey&language=en-US&page=" to "Trending Movies",
        "$tmdbBase/movie/top_rated?api_key=$tmdbKey&language=en-US&page="     to "Top Rated Movies",
        "$tmdbBase/tv/popular?api_key=$tmdbKey&language=en-US&page="          to "Popular TV Shows",
        "$tmdbBase/trending/tv/week?api_key=$tmdbKey&language=en-US&page="    to "Trending TV Shows",
        "$tmdbBase/tv/top_rated?api_key=$tmdbKey&language=en-US&page="        to "Top Rated TV Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json  = app.get(request.data + page).parsedSafe<TmdbPage>() ?: return newHomePageResponse(emptyList())
        val items = json.results.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    // ── SEARCH ────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val json = app.get(
            "$tmdbBase/search/multi?api_key=$tmdbKey&query=${query.encodeUrl()}&language=en-US&page=1"
        ).parsedSafe<TmdbPage>() ?: return emptyList()
        return json.results.mapNotNull { it.toSearchResponse() }
    }

    // ── LOAD ──────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<DataHolder>(url)
        return if (data.type == "movie") loadMovie(data) else loadTvSeries(data)
    }

    private suspend fun loadMovie(data: DataHolder): MovieLoadResponse? {
        val detail = app.get(
            "$tmdbBase/movie/${data.tmdbId}?api_key=$tmdbKey&language=en-US"
        ).parsedSafe<TmdbMovieDetail>() ?: return null

        val slug       = buildSlug(detail.title, detail.releaseDate?.take(4)?.toIntOrNull())
        val popcornUrl = "$mainUrl/movie/$slug"

        return newMovieLoadResponse(
            name    = detail.title,
            url     = DataHolder(data.tmdbId, "movie", popcornUrl).toJson(),
            type    = TvType.Movie,
            dataUrl = DataHolder(data.tmdbId, "movie", popcornUrl).toJson()
        ) {
            posterUrl           = detail.posterPath?.let { tmdbImg + it }
            backgroundPosterUrl = detail.backdropPath?.let { tmdbImgBg + it }
            plot                = detail.overview
            year                = detail.releaseDate?.take(4)?.toIntOrNull()
            rating              = (detail.voteAverage * 10).toInt()
            tags                = detail.genres?.map { it.name }
        }
    }

    private suspend fun loadTvSeries(data: DataHolder): TvSeriesLoadResponse? {
        val detail = app.get(
            "$tmdbBase/tv/${data.tmdbId}?api_key=$tmdbKey&language=en-US"
        ).parsedSafe<TmdbTvDetail>() ?: return null

        val slug     = buildSlug(detail.name, detail.firstAirDate?.take(4)?.toIntOrNull())
        val episodes = mutableListOf<Episode>()

        detail.seasons?.forEach { season ->
            if (season.seasonNumber == 0) return@forEach
            val sNum    = season.seasonNumber
            val sDetail = app.get(
                "$tmdbBase/tv/${data.tmdbId}/season/$sNum?api_key=$tmdbKey&language=en-US"
            ).parsedSafe<TmdbSeasonDetail>()

            sDetail?.episodes?.forEach { ep ->
                val popcornUrl = "$mainUrl/episode/$slug/$sNum-${ep.episodeNumber}"
                episodes.add(newEpisode(
                    DataHolder(data.tmdbId, "episode", popcornUrl).toJson()
                ) {
                    name        = ep.name
                    season      = sNum
                    episode     = ep.episodeNumber
                    posterUrl   = ep.stillPath?.let { tmdbImg + it }
                    description = ep.overview
                })
            }
        }

        return newTvSeriesLoadResponse(
            name     = detail.name,
            url      = DataHolder(data.tmdbId, "tv", "$mainUrl/").toJson(),
            type     = TvType.TvSeries,
            episodes = episodes
        ) {
            posterUrl           = detail.posterPath?.let { tmdbImg + it }
            backgroundPosterUrl = detail.backdropPath?.let { tmdbImgBg + it }
            plot                = detail.overview
            year                = detail.firstAirDate?.take(4)?.toIntOrNull()
            rating              = (detail.voteAverage * 10).toInt()
            tags                = detail.genres?.map { it.name }
        }
    }

    // ── LOAD LINKS ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val holder     = parseJson<DataHolder>(data)
        val popcornUrl = holder.popcornUrl ?: return false

        // Step 1: Fetch popcornmovies page
        val pageText = app.get(popcornUrl, headers = pcHeaders).text

        // Step 2: Extract td, sign, signBucket from embedded Alpine.js data
        val td     = pageText.extractVal("trackingData") ?: run {
            // Fallback: build td manually from tmdbId
            val title = pageText.extractVal("mediaTitle") ?: return false
            val year  = pageText.extractVal("mediaYear") ?: ""
            Base64.encodeToString(
                "${holder.tmdbId}::$title::$year".toByteArray(),
                Base64.NO_WRAP
            )
        }
        val sign   = pageText.extractVal("sign")   ?: return false
        val bucket = pageText.extractVal("signBucket") ?: return false

        // Step 3: Call /api/central/sources for each provider
        val providers = listOf("up_vidrock", "aldebaran", "yoru", "gamma", "nova")
        var foundAny  = false

        providers.forEach { provider ->
            try {
                val resp = app.get(
                    "$mainUrl/api/central/sources?td=${td.encodeUrl()}&provider=$provider",
                    headers = mapOf(
                        "Accept"         to "application/json",
                        "X-Sign"         to sign,
                        "X-Sign-Bucket"  to bucket,
                        "Referer"        to popcornUrl,
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                )
                if (!resp.isSuccessful) return@forEach

                val json = resp.parsedSafe<CentralResponse>() ?: return@forEach
                if (json.success != true) return@forEach

                json.data?.forEach { serverData ->
                    val label = serverData.label ?: provider
                    serverData.sources?.forEach { source ->
                        val streamUrl = source.url ?: return@forEach
                        val isHls     = source.type == "hls" || streamUrl.contains(".m3u8")
                        val quality   = when (source.quality) {
                            "1080" -> Qualities.P1080.value
                            "720"  -> Qualities.P720.value
                            "480"  -> Qualities.P480.value
                            "360"  -> Qualities.P360.value
                            else   -> Qualities.Unknown.value
                        }

                        callback(ExtractorLink(
                            source   = this.name,
                            name     = "PopcornMovies • $label",
                            url      = streamUrl,
                            referer  = "$mainUrl/",
                            quality  = quality,
                            isM3u8   = isHls,
                            headers  = mapOf("Referer" to "$mainUrl/")
                        ))
                        foundAny = true
                    }

                    // Handle subtitles
                    serverData.subtitles?.forEach { sub ->
                        val subUrl  = sub.url  ?: return@forEach
                        val subLang = sub.lang ?: "Unknown"
                        subtitleCallback(SubtitleFile(subLang, subUrl))
                    }
                }
            } catch (e: Exception) {
                // Provider unavailable, skip silently
            }
        }
        return foundAny
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    // Build popcornmovies slug from title + year
    // e.g. "The Dark Knight" 2008 → "the-dark-knight-2008"
    private fun buildSlug(title: String, year: Int?): String {
        val base = title.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
        return if (year != null) "$base-$year" else base
    }

    // Extract value from Alpine.js data string in page HTML
    // Matches patterns like: key: 'value' or key: "value"
    private fun String.extractVal(key: String): String? {
        return Regex("""$key:\s*['"]([^'"]+)['"]""").find(this)?.groupValues?.get(1)
    }

    private fun String.encodeUrl() = java.net.URLEncoder.encode(this, "UTF-8")

    // ── TMDB HELPERS ─────────────────────────────────────────────────────────
    private fun TmdbResult.toSearchResponse(): SearchResponse? {
        val t      = title ?: name ?: return null
        val isMovie = mediaType == "movie" || (mediaType == null && title != null)
        val holder = DataHolder(id ?: return null, if (isMovie) "movie" else "tv", null)

        return if (isMovie) {
            newMovieSearchResponse(t, holder.toJson(), TvType.Movie) {
                posterUrl = posterPath?.let { tmdbImg + it }
            }
        } else {
            newTvSeriesSearchResponse(t, holder.toJson(), TvType.TvSeries) {
                posterUrl = posterPath?.let { tmdbImg + it }
            }
        }
    }

    // ── DATA CLASSES ─────────────────────────────────────────────────────────
    data class DataHolder(
        val tmdbId:    Int,
        val type:      String,  // "movie" | "tv" | "episode"
        val popcornUrl: String?
    )

    data class TmdbPage(val results: List<TmdbResult> = emptyList())

    data class TmdbResult(
        val id:           Int?,
        val title:        String?,
        val name:         String?,
        val posterPath:   String?,
        val backdropPath: String?,
        val mediaType:    String?,
        val releaseDate:  String?,
        val firstAirDate: String?
    )

    data class TmdbMovieDetail(
        val id:          Int,
        val title:       String,
        val overview:    String?,
        val posterPath:  String?,
        val backdropPath:String?,
        val releaseDate: String?,
        val voteAverage: Double = 0.0,
        val genres:      List<Genre>?
    )

    data class TmdbTvDetail(
        val id:           Int,
        val name:         String,
        val overview:     String?,
        val posterPath:   String?,
        val backdropPath: String?,
        val firstAirDate: String?,
        val voteAverage:  Double = 0.0,
        val genres:       List<Genre>?,
        val seasons:      List<TmdbSeason>?
    )

    data class TmdbSeason(val seasonNumber: Int, val episodeCount: Int)

    data class TmdbSeasonDetail(val episodes: List<TmdbEpisode> = emptyList())

    data class TmdbEpisode(
        val episodeNumber: Int,
        val name:          String?,
        val overview:      String?,
        val stillPath:     String?
    )

    data class Genre(val name: String)

    // /api/central/sources response
    data class CentralResponse(
        val success: Boolean?,
        val data:    List<ServerData>?
    )

    data class ServerData(
        val provider:  String?,
        val label:     String?,
        val sources:   List<StreamSource>?,
        val subtitles: List<SubtitleSource>?
    )

    data class StreamSource(
        val url:     String?,
        val type:    String?,   // "hls" | "mp4"
        val quality: String?    // "auto" | "1080" | "720" etc
    )

    data class SubtitleSource(
        val url:  String?,
        val lang: String?
    )
}
