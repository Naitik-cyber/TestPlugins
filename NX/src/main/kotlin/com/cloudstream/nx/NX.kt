package com.cloudstream.nx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import android.content.Context
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import org.json.JSONObject

const val TMDB_API_KEY = "d48c912adb725b6424a3ce88671982b9"
const val TMDB_BASE = "https://api.themoviedb.org/3"
const val TMDB_IMAGE = "https://image.tmdb.org/t/p/w500"

// NX encryption key extracted from JS source
const val NX_KEY = "S8x!Jk4ZP1uG8\$my"

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
            val data = app.get("$TMDB_BASE/tv/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=external_ids")
                .parsedSafe<TMDBDetail>() ?: return null

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

            newTvSeriesLoadResponse(data.name ?: "Unknown", url, TvType.TvSeries, episodes) {
                this.posterUrl = data.poster_path?.let { "$TMDB_IMAGE$it" }
                this.backgroundPosterUrl = data.backdrop_path?.let { "$TMDB_IMAGE$it" }
                this.plot = data.overview
                this.year = data.first_air_date?.take(4)?.toIntOrNull()
            }
        } else {
            val data = app.get("$TMDB_BASE/movie/$tmdbId?api_key=$TMDB_API_KEY")
                .parsedSafe<TMDBDetail>() ?: return null

            newMovieLoadResponse(data.title ?: "Unknown", url, TvType.Movie, "$tmdbId|movie|1|1") {
                this.posterUrl = data.poster_path?.let { "$TMDB_IMAGE$it" }
                this.backgroundPosterUrl = data.backdrop_path?.let { "$TMDB_IMAGE$it" }
                this.plot = data.overview
                this.year = data.release_date?.take(4)?.toIntOrNull()
            }
        }
    }

    // Encrypt data using NX's AES encryption
    private fun encodeData(data: Map<String, Any>): String {
        return try {
            val json = JSONObject(data.toMutableMap().also {
                it["_req_ts"] = System.currentTimeMillis()
                it["_req_salt"] = (Math.random() * 1e10).toLong().toString(36)
            }).toString()
            val keySpec = SecretKeySpec(NX_KEY.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
                .replace("+", "-")
                .replace("/", "_")
                .replace("=", "")
        } catch (e: Exception) {
            ""
        }
    }

    // Decrypt NX API response
    private fun decodeData(encrypted: String): JSONObject? {
        return try {
            var base64 = encrypted.replace("-", "+").replace("_", "/")
            while (base64.length % 4 != 0) base64 += "="
            val keySpec = SecretKeySpec(NX_KEY.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decoded = Base64.decode(base64, Base64.DEFAULT)
            val decrypted = cipher.doFinal(decoded)
            JSONObject(String(decrypted, Charsets.UTF_8))
        } catch (e: Exception) {
            null
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

        var found = false

        try {
            // Step 1: Encode request data using NX encryption
            val requestData = mapOf(
                "tmdbId" to tmdbId,
                "imdb_id" to "",
                "type" to type,
                "season" to season,
                "episode" to episode
            )
            val encoded = encodeData(requestData)
            if (encoded.isEmpty()) return false

            // Step 2: First API call
            val firstResponse = app.get(
                "$mainUrl/api/sources?q=$encoded",
                headers = mapOf(
                    "Referer" to mainUrl,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            ).text

            val firstJson = JSONObject(firstResponse)
            val hash1 = firstJson.optString("_hash") ?: return false

            // Step 3: Second API call with hash
            val secondResponse = app.get(
                "$mainUrl/api/sources?q=$hash1",
                headers = mapOf(
                    "Referer" to mainUrl,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            ).text

            val secondJson = JSONObject(secondResponse)
            val hash2 = secondJson.optString("_hash")

            // Step 4: Decode the hash to get sources
            val decoded = if (hash2.isNotEmpty()) decodeData(hash2) else decodeData(hash1)
            val sources = decoded?.optJSONArray("sources") ?: return false

            // Step 5: Extract stream URLs
            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                val streamUrl = source.optString("url") ?: continue
                if (streamUrl.isEmpty()) continue

                val isM3u8 = streamUrl.contains(".m3u8", true) || streamUrl.contains(".txt", true)
                callback(
                    newExtractorLink(
                        source = name,
                        name = source.optString("name", name),
                        url = streamUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = when (source.optString("quality", "").lowercase()) {
                            "1080p", "fhd" -> Qualities.P1080.value
                            "720p", "hd" -> Qualities.P720.value
                            "480p", "sd" -> Qualities.P480.value
                            else -> Qualities.Unknown.value
                        }
                    }
                )
                found = true

                // Also try loadExtractor for embed sources
                if (source.optBoolean("isEmbed", false)) {
                    loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
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
