package com.cloudstream.nx

import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

const val TMDB_API_KEY = "d48c912adb725b6424a3ce88671982b9"
const val TMDB_BASE = "https://api.themoviedb.org/3"
const val TMDB_IMAGE = "https://image.tmdb.org/t/p/w500"
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
    @JsonProperty("season_number") val seasonNumber: Int? = null
    // FIX 1: Remove `episodes` here — TMDB /tv/{id} does NOT include episodes
    // in the seasons array. Fetching them separately below works correctly.
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBSeasonDetail(
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class TMDBExternalIds(
    @JsonProperty("imdb_id") val imdbId: String? = null
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
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val response = app.get("$TMDB_BASE/search/multi?api_key=$TMDB_API_KEY&query=$encodedQuery")
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
            val raw = app.get("$TMDB_BASE/tv/$tmdbId?api_key=$TMDB_API_KEY").text
            val data = try { JSONObject(raw) } catch (_: Exception) { return null }

            val showName = data.optString("name").takeIf { it.isNotBlank() } ?: "Unknown"
            val overview = data.optString("overview")
            val posterPath = data.optString("poster_path")
            val backdropPath = data.optString("backdrop_path")
            val year = data.optString("first_air_date").take(4).toIntOrNull()

            val episodes = mutableListOf<Episode>()
            val seasons = data.optJSONArray("seasons") ?: JSONArray()

            for (i in 0 until seasons.length()) {
                val seasonObj = seasons.optJSONObject(i) ?: continue
                val seasonNum = seasonObj.optInt("season_number", -1)
                if (seasonNum <= 0) continue

                val seasonRaw = app.get(
                    "$TMDB_BASE/tv/$tmdbId/season/$seasonNum?api_key=$TMDB_API_KEY"
                ).text
                val seasonData = try { JSONObject(seasonRaw) } catch (_: Exception) { continue }
                val epArray = seasonData.optJSONArray("episodes") ?: continue

                for (j in 0 until epArray.length()) {
                    val ep = epArray.optJSONObject(j) ?: continue
                    val epNum = ep.optInt("episode_number", -1)
                    if (epNum < 0) continue

                    episodes.add(
                        newEpisode("$tmdbId|tv|$seasonNum|$epNum") {
                            this.name = ep.optString("name").takeIf { it.isNotBlank() }
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = ep.optString("still_path").takeIf { it.isNotBlank() }
                                ?.let { "$TMDB_IMAGE$it" }
                            this.description = ep.optString("overview").takeIf { it.isNotBlank() }
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(showName, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterPath.takeIf { it.isNotBlank() }?.let { "$TMDB_IMAGE$it" }
                this.backgroundPosterUrl = backdropPath.takeIf { it.isNotBlank() }?.let { "$TMDB_IMAGE$it" }
                this.plot = overview.takeIf { it.isNotBlank() }
                this.year = year
            }
        } else {
            val raw = app.get("$TMDB_BASE/movie/$tmdbId?api_key=$TMDB_API_KEY").text
            val data = try { JSONObject(raw) } catch (_: Exception) { return null }

            val title = data.optString("title").takeIf { it.isNotBlank() } ?: "Unknown"
            val overview = data.optString("overview")
            val posterPath = data.optString("poster_path")
            val backdropPath = data.optString("backdrop_path")
            val year = data.optString("release_date").take(4).toIntOrNull()

            newMovieLoadResponse(title, url, TvType.Movie, "$tmdbId|movie|1|1") {
                this.posterUrl = posterPath.takeIf { it.isNotBlank() }?.let { "$TMDB_IMAGE$it" }
                this.backgroundPosterUrl = backdropPath.takeIf { it.isNotBlank() }?.let { "$TMDB_IMAGE$it" }
                this.plot = overview.takeIf { it.isNotBlank() }
                this.year = year
            }
        }
    }

    private fun md5(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("MD5").digest(data)
    }

    private fun evpBytesToKey(
        password: ByteArray,
        salt: ByteArray,
        keySize: Int = 32,
        ivSize: Int = 16
    ): Pair<ByteArray, ByteArray> {
        val targetSize = keySize + ivSize
        val derived = ArrayList<Byte>()
        var block = ByteArray(0)

        while (derived.size < targetSize) {
            val input = block + password + salt
            block = md5(input)
            block.forEach { derived.add(it) }
        }

        val all = derived.toByteArray()
        return all.copyOfRange(0, keySize) to all.copyOfRange(keySize, keySize + ivSize)
    }

    // FIX 3: Build JSONObject explicitly to avoid Boolean serialization bugs
    private fun encodeData(obj: JSONObject): String {
        return try {
            obj.put("_req_ts", System.currentTimeMillis())
            obj.put("_req_salt", Math.random().toString().substring(2).take(10))

            val json = obj.toString()
            val salt = ByteArray(8)
            SecureRandom().nextBytes(salt)

            val password = NX_KEY.toByteArray(Charsets.UTF_8)
            val (key, iv) = evpBytesToKey(password, salt)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )

            val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
            val openssl = "Salted__".toByteArray(Charsets.UTF_8) + salt + encrypted

            Base64.encodeToString(openssl, Base64.NO_WRAP)
                .replace("+", "-")
                .replace("/", "_")
                .replace("=", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun decodeData(encrypted: String): JSONObject? {
        return try {
            var base64 = encrypted.replace("-", "+").replace("_", "/")
            while (base64.length % 4 != 0) base64 += "="

            val raw = Base64.decode(base64, Base64.DEFAULT)
            if (raw.size < 16) return null

            val header = String(raw.copyOfRange(0, 8), Charsets.UTF_8)
            if (header != "Salted__") return null

            val salt = raw.copyOfRange(8, 16)
            val cipherText = raw.copyOfRange(16, raw.size)

            val password = NX_KEY.toByteArray(Charsets.UTF_8)
            val (key, iv) = evpBytesToKey(password, salt)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )

            val decrypted = cipher.doFinal(cipherText)
            val json = JSONObject(String(decrypted, Charsets.UTF_8))

            json.remove("_req_ts")
            json.remove("_req_salt")

            json
        } catch (_: Exception) {
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

        val headers = mapOf(
            "Referer" to mainUrl,
            "Origin" to mainUrl,
            "User-Agent" to "Mozilla/5.0"
        )

        // FIX 2: Fetch real IMDb ID from TMDB external IDs
        val mediaPath = if (type == "tv") "tv" else "movie"
        val imdbId = try {
            app.get("$TMDB_BASE/$mediaPath/$tmdbId/external_ids?api_key=$TMDB_API_KEY")
                .parsedSafe<TMDBExternalIds>()?.imdbId ?: ""
        } catch (_: Exception) { "" }

        var found = false

        try {
            // FIX 3: Build JSONObject directly — avoids Boolean/type serialization issues
            val serversPayload = JSONObject().apply {
                put("tmdbId", tmdbId)
                put("imdb_id", imdbId)
                put("type", type)
                put("season", season)
                put("episode", episode)
            }

            val serversEncoded = encodeData(serversPayload)
            if (serversEncoded.isEmpty()) return false

            val serversResponse = app.get(
                "$mainUrl/api/servers?q=$serversEncoded",
                headers = headers
            ).text

            val serversHash = JSONObject(serversResponse).optString("_hash")
            val serversJson = decodeData(serversHash) ?: return false
            val servers = serversJson.optJSONArray("servers") ?: JSONArray()

            for (i in 0 until servers.length()) {
                val server = servers.optJSONObject(i) ?: continue

                // FIX 4: Only skip if web_support is explicitly false (not missing)
                if (server.has("web_support") && !server.optBoolean("web_support", true)) {
                    continue
                }

                val scraper = server.optString("scraper")
                if (scraper.isBlank()) continue

                // FIX 3: Build JSONObject directly — ex_lang as real Boolean
                val sourcesPayload = JSONObject().apply {
                    put("ex_lang", false)
                    put("provider", scraper)
                    put("tmdbId", tmdbId)
                    put("imdb_id", imdbId)
                    put("type", type)
                    put("season", season)
                    put("episode", episode)
                }

                val sourcesEncoded = encodeData(sourcesPayload)
                if (sourcesEncoded.isEmpty()) continue

                val sourcesResponse = app.get(
                    "$mainUrl/api/sources?q=$sourcesEncoded",
                    headers = headers
                ).text

                val sourcesHash = JSONObject(sourcesResponse).optString("_hash")
                val sourcesJson = decodeData(sourcesHash) ?: continue
                val sources = sourcesJson.optJSONArray("sources") ?: continue

                for (j in 0 until sources.length()) {
                    val source = sources.optJSONObject(j) ?: continue
                    val streamUrl = source.optString("url")
                    if (streamUrl.isBlank()) continue

                    if (source.optBoolean("isEmbed", false)) {
                        if (loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)) {
                            found = true
                        }
                        continue
                    }

                    val isM3u8 = streamUrl.contains(".m3u8", true) ||
                        source.optString("type").contains("hls", true)

                    callback(
                        newExtractorLink(
                            source = server.optString("name", name),
                            name = source.optString("quality", server.optString("name", name)),
                            url = streamUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = when (source.optString("quality", "").lowercase()) {
                                "2160p", "4k" -> Qualities.P2160.value
                                "1080p", "fhd" -> Qualities.P1080.value
                                "720p", "hd" -> Qualities.P720.value
                                "480p", "sd" -> Qualities.P480.value
                                "360p" -> Qualities.P360.value
                                else -> Qualities.Unknown.value
                            }
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
