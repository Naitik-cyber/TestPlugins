package com.cloudstream.nx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

class NX : MainAPI() {
    override var mainUrl = "https://nxsha.space"
    override var name = "NX"
    override var lang = "en"
    override val hasMainPage = true
    
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // -------------------------------------------------------------------------
    // Helper: parse a card element into a SearchResponse
    // -------------------------------------------------------------------------
    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val title = selectFirst(".title, .movie-title, h2, h3, .entry-title, .name, .film-name")
            ?.text()?.trim()
            ?: anchor.attr("title").trim()
        if (title.isEmpty()) return null
        val poster = selectFirst("img")
            ?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            ?.let { fixUrlNull(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    // -------------------------------------------------------------------------
    // 1. Home page
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        Pair("$mainUrl/", "Latest Movies"),
        Pair("$mainUrl/genre/action/", "Action"),
        Pair("$mainUrl/genre/drama/", "Drama"),
        Pair("$mainUrl/genre/comedy/", "Comedy"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val items = document
            .select(".movie-item, .post-item, .item, article.post, .card, .film-item, .entry-item")
            .mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    // -------------------------------------------------------------------------
    // 2. Search
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return document
            .select(".movie-item, .post-item, .item, article.post, .card, .search-item, .film-item")
            .mapNotNull { it.toSearchResponse() }
    }

    // -------------------------------------------------------------------------
    // 3. Load — extract TMDB ID and build embed URLs
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document
            .selectFirst("h1.entry-title, h1.movie-title, h1, meta[property=og:title]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
            ?.trim() ?: return null

        val poster = document
            .selectFirst("meta[property=og:image], .poster img, .movie-poster img, .thumbnail img")
            ?.let {
                val src = if (it.tagName() == "meta") it.attr("content")
                else it.attr("data-src").ifEmpty { it.attr("src") }
                fixUrlNull(src)
            }

        val plot = document
            .selectFirst("meta[property=og:description], .description, .synopsis, .entry-content p, .overview")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
            ?.trim()

        val year = document
            .selectFirst(".year, .release-year, .date")
            ?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        // Try to extract TMDB ID from page HTML
        val pageHtml = document.html()
        val tmdbId = Regex("""tmdbId[\"'\s:=]+(\d+)""")
            .find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""tmdb[_-]?id[\"'\s:=]+(\d+)""", RegexOption.IGNORE_CASE)
            .find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""/(?:movie|tv)/(\d+)""")
            .find(pageHtml)?.groupValues?.get(1)

        // Determine type from URL or page content
        val isTv = url.contains("/tv/") || url.contains("/series/") ||
                pageHtml.contains("\"type\":\"tv\"") || pageHtml.contains("type=tv")

        // Build embed data - store tmdbId and type for loadLinks
        val embedData = if (tmdbId != null) {
            "${tmdbId}|${if (isTv) "tv" else "movie"}"
        } else {
            // Fallback: collect embed URLs directly from page
            val embedUrls = mutableListOf<String>()
            document.select("iframe[src], iframe[data-src]").forEach { el ->
                val src = el.attr("src").ifEmpty { el.attr("data-src") }
                if (src.isNotEmpty()) fixUrlNull(src)?.let { embedUrls.add(it) }
            }
            embedUrls.distinct().joinToString(",")
        }

        return if (isTv) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                newEpisode("$embedData|1|1") { this.name = "Episode 1"; this.season = 1; this.episode = 1 }
            )) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, embedData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }
    }

    // -------------------------------------------------------------------------
    // 4. Load video links using NX embed player
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // Check if data contains TMDB ID
        if (data.contains("|")) {
            val parts = data.split("|")
            val tmdbId = parts.getOrNull(0) ?: ""
            val type = parts.getOrNull(1) ?: "movie"
            val season = parts.getOrNull(2)?.toIntOrNull() ?: 1
            val episode = parts.getOrNull(3)?.toIntOrNull() ?: 1

            // Build NX embed URL using TMDB ID
            val embedUrl = if (type == "tv") {
                "$mainUrl/embed?tmdbId=$tmdbId&type=tv&s=$season&e=$episode&autoplay=true"
            } else {
                "$mainUrl/embed?tmdbId=$tmdbId&type=movie&autoplay=true"
            }

            // Fetch embed page and extract stream
            found = extractFromEmbed(embedUrl, subtitleCallback, callback) || found

            // Also try backup embed providers from JS source
            val backupEmbeds = listOf(
                if (type == "tv")
                    "https://player.videasy.net/tv/$tmdbId/$season/$episode?autoplay=true"
                else
                    "https://player.videasy.net/movie/$tmdbId?autoplay=true",
                if (type == "tv")
                    "https://vidfast.pro/tv/$tmdbId/$season/$episode?autoplay=true"
                else
                    "https://vidfast.pro/movie/$tmdbId?autoplay=true"
            )

            backupEmbeds.forEach { backup ->
                if (!found) {
                    found = loadExtractor(backup, mainUrl, subtitleCallback, callback) || found
                }
            }
        } else {
            // Fallback: treat data as direct embed URLs
            val urls = data.split(",").filter { it.isNotEmpty() }
            urls.forEach { url ->
                found = loadExtractor(url, mainUrl, subtitleCallback, callback) || found
                if (!found) found = extractFromEmbed(url, subtitleCallback, callback) || found
            }
        }

        return found
    }

    // -------------------------------------------------------------------------
    // Helper: fetch embed page and extract m3u8/mp4 streams
    // -------------------------------------------------------------------------
    private suspend fun extractFromEmbed(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            // Try built-in extractor first
            if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) return true

            val html = app.get(
                embedUrl,
                referer = mainUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Origin" to mainUrl
                )
            ).text

            // Search for m3u8/mp4 URLs in page source
            Regex(
                """https?://[^\s"'<>\\]+\.(?:m3u8|mp4|txt)[^\s"'<>\\]*""",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { match ->
                val streamUrl = match.value
                    .replace("\\u0026", "&")
                    .replace("\\/", "/")
                    .trim()
                if (streamUrl.length > 20) {
                    val isM3u8 = streamUrl.contains(".m3u8", true) || streamUrl.contains(".txt", true)
                    callback(
    newExtractorLink(name, name, streamUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
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
@CloudstreamPlugin
class NXPlugin : Plugin() {
    override fun load(manager: ApiManager) {
        manager.registerMainAPI(NX())
    }
}
