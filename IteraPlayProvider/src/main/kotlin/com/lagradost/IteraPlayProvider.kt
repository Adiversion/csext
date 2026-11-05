package com.lagradost

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLDecoder
import java.net.URLEncoder

class IteraPlayProvider : MainAPI() {
    override var mainUrl = "https://iteraplay.com"
    private val altCatalogUrl = "https://teraviralhub.com"
    override var name = "iTeraPlay"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Others
    )

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val PREFS_NAME = "iteraplay_prefs"
        private const val KEY_REMEMBER_ME = "remember_me_cookie"

        @Volatile var authCookie: String? = null
        @Volatile var appContext: Context? = null

        // In-memory catalog cache (5-minute TTL) to avoid redundant network queries & Cloudflare rate-limits
        private val catalogCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<SearchResponse>>>()
        private const val CATALOG_CACHE_TTL_MS = 5 * 60 * 1000L

        fun getSavedCookie(context: Context? = null): String? {
            if (!authCookie.isNullOrBlank()) return authCookie
            val ctx = context ?: appContext ?: CommonActivity.activity ?: return null
            return runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val c = prefs.getString(KEY_REMEMBER_ME, null)
                authCookie = c
                c
            }.getOrNull()
        }

        fun saveCookie(context: Context? = null, cookie: String?) {
            authCookie = cookie
            val ctx = context ?: appContext ?: CommonActivity.activity ?: return
            runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (cookie.isNullOrBlank()) {
                    prefs.edit().remove(KEY_REMEMBER_ME).apply()
                } else {
                    prefs.edit().putString(KEY_REMEMBER_ME, cookie).apply()
                }
            }
        }
    }

    // Streamlined to 2 primary sections to eliminate Cloudflare 4-connection burst rate-limiting & TCP resets
    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "recent"   to "Latest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sort = request.data
        val offset = (page - 1) * 20
        val effectiveSort = sort

        val cacheKey = "main_${sort}_page_${page}"
        val now = System.currentTimeMillis()
        val cached = catalogCache[cacheKey]
        if (cached != null && (now - cached.first) < CATALOG_CACHE_TTL_MS) {
            return newHomePageResponse(request.name, cached.second)
        }

        val cookie = getSavedCookie()
        var resText: String? = null

        // If remember_me cookie exists, attempt iteraplay.com with a short 3s timeout
        if (!cookie.isNullOrBlank()) {
            val iteraUrl = "$mainUrl/api/home/search?q=&sort=$effectiveSort&limit=20&offset=$offset&quality=&duration="
            val iteraHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/premium-videos",
                "Cookie" to "remember_me=$cookie",
                "Accept" to "application/json, text/plain, */*"
            )
            resText = runCatching { app.get(iteraUrl, headers = iteraHeaders, timeout = 3).text }.getOrNull()
            if (resText != null && (resText.contains("Unauthorized") || resText.contains("401"))) {
                resText = null
            }
        }

        // Direct, fast route to alt catalog (teraviralhub) if iteraplay unauthenticated
        if (resText.isNullOrBlank()) {
            val altUrl = "$altCatalogUrl/api/home/search?sort=$effectiveSort&offset=$offset&limit=20&quality=&duration="
            val altHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$altCatalogUrl/",
                "Accept" to "application/json, text/plain, */*"
            )
            resText = runCatching { app.get(altUrl, headers = altHeaders, timeout = 8).text }.getOrNull()
        }

        if (resText.isNullOrBlank()) return newHomePageResponse(request.name, emptyList())

        val encryptedObj = runCatching { parseJson<IteraEncryptedResponse>(resText) }.getOrNull()
        val encryptedData = encryptedObj?.data ?: return newHomePageResponse(request.name, emptyList())

        val decryptedJson = IteraPlayCrypto.decrypt(encryptedData) ?: return newHomePageResponse(request.name, emptyList())
        val catalog = runCatching { parseJson<IteraDecryptedCatalog>(decryptedJson) }.getOrNull()
        val items = catalog?.list?.mapNotNull { it.toSearchResponse(mainUrl) } ?: emptyList()

        if (items.isNotEmpty()) {
            catalogCache[cacheKey] = now to items
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        // 1. Direct TeraBox / TeraDL Link Handling:
        if (cleanQuery.contains("terabox") || cleanQuery.contains("1024tera") || cleanQuery.contains("teradl") || cleanQuery.contains("teraboxdl")) {
            var filename: String? = null
            var poster: String? = null
            var directStream: String? = null
            var hlsStream: String? = null
            var teraboxShareUrl: String? = null

            if (cleanQuery.contains("?")) {
                val queryPart = cleanQuery.substringAfter("?")
                for (param in queryPart.split("&")) {
                    val key = param.substringBefore("=")
                    val rawVal = param.substringAfter("=", "")
                    val decodedVal = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
                    when (key) {
                        "filename" -> filename = decodedVal
                        "poster" -> poster = decodedVal
                        "direct" -> directStream = decodedVal
                        "start" -> hlsStream = decodedVal
                        "terabox_url" -> teraboxShareUrl = decodedVal
                    }
                }
            }

            val title = filename?.removeSuffix(".mp4")?.removeSuffix(".mkv")?.replace("_", " ")?.trim()
                ?: if (cleanQuery.contains("/")) cleanQuery.substringAfterLast("/").substringBefore("?").ifEmpty { "Direct TeraBox Stream" } else "Direct TeraBox Stream"
            val displayPoster = poster ?: "https://raw.githubusercontent.com/Adiversion/csext/builds/IteraPlay_icon.png"

            val directLinkData = IteraLinkData(
                teraboxUrl = teraboxShareUrl ?: cleanQuery,
                title = title,
                poster = displayPoster,
                directStreamUrl = directStream,
                hlsStreamUrl = hlsStream
            )
            return listOf(
                newMovieSearchResponse(
                    name = title,
                    url = directLinkData.toJson(),
                    type = TvType.Movie
                ) {
                    this.posterUrl = displayPoster
                }
            )
        }

        // 2. Keyword Search on Catalog API:
        val cacheKey = "search_${cleanQuery.lowercase()}"
        val now = System.currentTimeMillis()
        val cached = catalogCache[cacheKey]
        if (cached != null && (now - cached.first) < CATALOG_CACHE_TTL_MS) {
            return cached.second
        }

        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val cookie = getSavedCookie()
        var resText: String? = null

        if (!cookie.isNullOrBlank()) {
            val iteraUrl = "$mainUrl/api/home/search?q=$encodedQuery&sort=&limit=25&offset=0"
            val iteraHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "Cookie" to "remember_me=$cookie",
                "Accept" to "application/json, text/plain, */*"
            )
            resText = runCatching { app.get(iteraUrl, headers = iteraHeaders, timeout = 3).text }.getOrNull()
            if (resText != null && (resText.contains("Unauthorized") || resText.contains("401"))) {
                resText = null
            }
        }

        if (resText.isNullOrBlank()) {
            val altUrl = "$altCatalogUrl/api/home/search?q=$encodedQuery&sort=&limit=25&offset=0"
            val altHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$altCatalogUrl/",
                "Accept" to "application/json, text/plain, */*"
            )
            resText = runCatching { app.get(altUrl, headers = altHeaders, timeout = 8).text }.getOrNull()
        }

        if (resText.isNullOrBlank()) return emptyList()

        val encryptedObj = runCatching { parseJson<IteraEncryptedResponse>(resText) }.getOrNull()
        val encryptedData = encryptedObj?.data ?: return emptyList()

        val decryptedJson = IteraPlayCrypto.decrypt(encryptedData) ?: return emptyList()
        val catalog = runCatching { parseJson<IteraDecryptedCatalog>(decryptedJson) }.getOrNull()
        val items = catalog?.list?.mapNotNull { it.toSearchResponse(mainUrl) } ?: emptyList()

        if (items.isNotEmpty()) {
            catalogCache[cacheKey] = now to items
        }

        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val linkData = if (url.startsWith("{") && url.endsWith("}")) {
            runCatching { parseJson<IteraLinkData>(url) }.getOrNull()
        } else if (url.contains("terabox") || url.contains("1024tera") || url.contains("teradl") || url.contains("teraboxdl")) {
            var filename: String? = null
            var poster: String? = null
            var directStream: String? = null
            var hlsStream: String? = null
            var teraboxShareUrl: String? = null

            if (url.contains("?")) {
                val queryPart = url.substringAfter("?")
                for (param in queryPart.split("&")) {
                    val key = param.substringBefore("=")
                    val rawVal = param.substringAfter("=", "")
                    val decodedVal = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
                    when (key) {
                        "filename" -> filename = decodedVal
                        "poster" -> poster = decodedVal
                        "direct" -> directStream = decodedVal
                        "start" -> hlsStream = decodedVal
                        "terabox_url" -> teraboxShareUrl = decodedVal
                    }
                }
            }
            val title = filename?.removeSuffix(".mp4")?.removeSuffix(".mkv")?.replace("_", " ")?.trim()
                ?: if (url.contains("/")) url.substringAfterLast("/").substringBefore("?").ifEmpty { "Direct TeraBox Stream" } else "Direct TeraBox Stream"
            val displayPoster = poster ?: "https://raw.githubusercontent.com/Adiversion/csext/builds/IteraPlay_icon.png"

            IteraLinkData(
                teraboxUrl = teraboxShareUrl ?: url,
                title = title,
                poster = displayPoster,
                directStreamUrl = directStream,
                hlsStreamUrl = hlsStream
            )
        } else null

        val videoId = linkData?.videoId
            ?: Regex("""/video/(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: (if (!linkData?.teraboxUrl.isNullOrBlank() || !linkData?.directStreamUrl.isNullOrBlank() || !linkData?.hlsStreamUrl.isNullOrBlank()) 0L else throw ErrorLoadingException("Invalid video URL: $url"))

        var teraboxUrl = linkData?.teraboxUrl
        val title = linkData?.title ?: "Video #$videoId"
        val poster = linkData?.poster ?: "https://raw.githubusercontent.com/Adiversion/csext/builds/IteraPlay_icon.png"

        // 1. If terabox URL wasn't validly embedded in thumbnail, attempt catalog alternative search by title
        if (teraboxUrl.isNullOrBlank() || teraboxUrl.contains("thumb_")) {
            val altUrl = resolveAlternativeTeraBoxUrl(title)
            if (!altUrl.isNullOrBlank()) {
                teraboxUrl = altUrl
            }
        }

        // 2. If still blank, fallback to iteraplay video access API:
        if (teraboxUrl.isNullOrBlank() || teraboxUrl.contains("thumb_")) {
            val cookie = getSavedCookie()
            val accessUrl = "$mainUrl/api/video/access/$videoId"
            val headers = mutableMapOf(
                "Content-Type" to "application/json",
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
            if (!cookie.isNullOrBlank()) {
                headers["Authorization"] = "Bearer $cookie"
                headers["Cookie"] = "remember_me=$cookie"
            }
            val accessRes = runCatching {
                app.post(
                    accessUrl,
                    headers = headers,
                    json = mapOf(
                        "type" to "play",
                        "fingerprint" to "1bb52e836da743a0bad8bfcdd8020526"
                    ),
                    timeout = 10
                ).parsedSafe<IteraAccessResponse>()
            }.getOrNull()

            if (!accessRes?.url.isNullOrBlank() && accessRes?.url?.contains("thumb_") != true) {
                teraboxUrl = accessRes?.url
            }
        }

        val resolvedLinkData = IteraLinkData(
            videoId = videoId,
            teraboxUrl = teraboxUrl,
            title = title,
            poster = poster,
            directStreamUrl = linkData?.directStreamUrl,
            hlsStreamUrl = linkData?.hlsStreamUrl
        )

        if (!teraboxUrl.isNullOrBlank()) {
            val folderRes = runCatching { TeraBoxExtractor.fetchShareContents(teraboxUrl) }.getOrNull()
            if (folderRes != null && folderRes.videos.size > 1) {
                val episodes = folderRes.videos.mapIndexed { idx, v ->
                    val epTitle = v.serverFilename?.removeSuffix(".mp4")?.removeSuffix(".mkv")?.replace("_", " ")?.trim()
                        ?: "Episode ${idx + 1}"
                    val epData = IteraLinkData(
                        videoId = videoId,
                        teraboxUrl = teraboxUrl,
                        title = epTitle,
                        poster = poster,
                        fsId = v.fsId?.toString(),
                        filePath = v.path,
                        episodeIndex = idx
                    )
                    newEpisode(epData.toJson()) {
                        this.name = epTitle
                        this.episode = idx + 1
                        this.posterUrl = poster
                    }
                }

                return newTvSeriesLoadResponse(
                    name = title,
                    url = url,
                    type = TvType.TvSeries,
                    episodes = episodes
                ) {
                    this.posterUrl = poster
                    this.plot = "TeraBox folder containing ${folderRes.videos.size} episodes."
                }
            } else if (folderRes == null || folderRes.videos.isEmpty()) {
                // Only if native TeraBox failed, query FlowVideoPlayer for folder items
                val flowVideos = runCatching { TeraBoxExtractor.fetchFlowVideos(teraboxUrl) }.getOrNull() ?: emptyList()
                if (flowVideos.size > 1) {
                    val episodes = flowVideos.mapIndexed { idx, v ->
                        val epTitle = v.fileName?.removeSuffix(".mp4")?.removeSuffix(".mkv")?.replace("_", " ")?.trim()
                            ?: "Episode ${idx + 1}"
                        val epData = IteraLinkData(
                            videoId = videoId,
                            teraboxUrl = teraboxUrl,
                            title = epTitle,
                            poster = poster,
                            filePath = v.fileName,
                            episodeIndex = idx
                        )
                        newEpisode(epData.toJson()) {
                            this.name = epTitle
                            this.episode = idx + 1
                            this.posterUrl = poster
                        }
                    }

                    return newTvSeriesLoadResponse(
                        name = title,
                        url = url,
                        type = TvType.TvSeries,
                        episodes = episodes
                    ) {
                        this.posterUrl = poster
                        this.plot = "TeraBox folder containing ${flowVideos.size} episodes."
                    }
                }
            }
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = resolvedLinkData.toJson()
        ) {
            this.posterUrl = poster
            this.plot = "TeraBox streaming video."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = runCatching { parseJson<IteraLinkData>(data) }.getOrNull()
            ?: return false

        var teraboxUrl = linkData.teraboxUrl

        // 1. If terabox URL is missing or points to a non-video thumbnail slug, resolve via alternative search
        if (teraboxUrl.isNullOrBlank() || teraboxUrl.contains("thumb_")) {
            if (!linkData.title.isNullOrBlank()) {
                val altUrl = resolveAlternativeTeraBoxUrl(linkData.title)
                if (!altUrl.isNullOrBlank()) {
                    teraboxUrl = altUrl
                }
            }
        }

        // 2. If still missing, attempt video access API
        if (teraboxUrl.isNullOrBlank() || teraboxUrl.contains("thumb_")) {
            if (linkData.videoId != null) {
                val cookie = getSavedCookie()
                val accessUrl = "$mainUrl/api/video/access/${linkData.videoId}"
                val headers = mutableMapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
                if (!cookie.isNullOrBlank()) {
                    headers["Authorization"] = "Bearer $cookie"
                    headers["Cookie"] = "remember_me=$cookie"
                }
                val accessRes = runCatching {
                    app.post(
                        accessUrl,
                        headers = headers,
                        json = mapOf(
                            "type" to "play",
                            "fingerprint" to "1bb52e836da743a0bad8bfcdd8020526"
                        ),
                        timeout = 10
                    ).parsedSafe<IteraAccessResponse>()
                }.getOrNull()

                if (!accessRes?.url.isNullOrBlank() && accessRes?.url?.contains("thumb_") != true) {
                    teraboxUrl = accessRes?.url
                }
            }
        }

        var foundLinks = false
        val customCallback: (ExtractorLink) -> Unit = { link ->
            foundLinks = true
            callback(link)
        }

        // 0. If directStreamUrl or hlsStreamUrl were parsed from player.1024teradl.com or search:
        if (!linkData.hlsStreamUrl.isNullOrBlank()) {
            customCallback(
                newExtractorLink(
                    source = "TeraBox Fast Stream ⚡",
                    name = "TeraBox - Fast Stream ⚡ [480p] (1024TeraDL)",
                    url = linkData.hlsStreamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://player.1024teradl.com/"
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://player.1024teradl.com/"
                    )
                    this.quality = Qualities.P480.value
                }
            )
        }

        if (!linkData.directStreamUrl.isNullOrBlank()) {
            val streamId = "iteraplay_direct_" + (linkData.fsId ?: System.currentTimeMillis().toString())
            val proxiedUrl = LocalM3u8Server.proxy(
                streamId,
                linkData.directStreamUrl,
                referer = "https://player.1024teradl.com/",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://player.1024teradl.com/",
                    "Connection" to "keep-alive"
                )
            )

            // 1. Local Proxy Link (Smooth Seeking, external player / casting safe)
            customCallback(
                newExtractorLink(
                    source = "TeraBox Direct 1080p",
                    name = "TeraBox - Direct HD [1080p] (Local Proxy - Smooth Seeking)",
                    url = proxiedUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://player.1024teradl.com/"
                    this.quality = Qualities.P1080.value
                }
            )

            // 2. Direct CDN Link (Fallback / External Downloader)
            customCallback(
                newExtractorLink(
                    source = "TeraBox Direct 1080p",
                    name = "TeraBox - Direct HD [1080p] (1024TeraDL High-Speed)",
                    url = linkData.directStreamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://player.1024teradl.com/"
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://player.1024teradl.com/",
                        "Connection" to "keep-alive"
                    )
                    this.quality = Qualities.P1080.value
                }
            )
        }

        if (!teraboxUrl.isNullOrBlank() && !teraboxUrl.contains("thumb_")) {
            runCatching {
                TeraBoxExtractor.resolveDirectTeraBox(
                    teraboxUrl,
                    customCallback,
                    targetFsId = linkData.fsId,
                    targetPath = linkData.filePath,
                    targetTitle = linkData.title,
                    targetIndex = linkData.episodeIndex
                )
            }

            if (!foundLinks) {
                runCatching {
                    loadExtractor(teraboxUrl, subtitleCallback, customCallback)
                }
            }
        }

        return foundLinks
    }

    private suspend fun resolveAlternativeTeraBoxUrl(rawTitle: String): String? {
        val clean = rawTitle.removeSuffix(".mp4")
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\[[^\]]*\]"""), "")
            .replace(Regex("""[-_]"""), " ")
            .trim()
        if (clean.length < 3) return null

        val catalog = runCatching {
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val res = app.get(
                "$altCatalogUrl/api/home/search?q=$encoded&sort=&limit=15&offset=0",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$altCatalogUrl/"
                ),
                timeout = 10
            ).parsedSafe<IteraEncryptedResponse>()
            val encData = res?.data ?: return@runCatching null
            val decJson = IteraPlayCrypto.decrypt(encData) ?: return@runCatching null
            parseJson<IteraDecryptedCatalog>(decJson)
        }.getOrNull()

        val list = catalog?.list ?: return null
        for (item in list) {
            val thumb = item.thumbnail ?: continue
            val key = Regex("""thumbnails/(1[a-zA-Z0-9\-]{21,23})""").find(thumb)?.groupValues?.get(1)
                ?: Regex("""thumbnails/([a-zA-Z0-9\-]{21,24})(?:_|\.|\b)""").find(thumb)?.groupValues?.get(1)
                ?: Regex("""(?:\/s\/|surl=)(1?[a-zA-Z0-9\-]{21,23})""").find(thumb)?.groupValues?.get(1)
            if (!key.isNullOrBlank() && !key.startsWith("thumb_")) {
                return "https://terabox.app/s/$key"
            }
        }
        return null
    }

    private fun IteraVideoItem.toSearchResponse(baseUrl: String): SearchResponse? {
        val id = this.id ?: return null
        val title = this.fileName?.removeSuffix(".mp4")?.replace("_", " ")?.trim() ?: "Video #$id"
        val thumb = this.thumbnail ?: ""
        val teraboxKey = Regex("""thumbnails/(1[a-zA-Z0-9\-]{21,23})""").find(thumb)?.groupValues?.get(1)
            ?: Regex("""thumbnails/([a-zA-Z0-9\-]{21,24})(?:_|\.|\b)""").find(thumb)?.groupValues?.get(1)
            ?: Regex("""(?:\/s\/|surl=)(1?[a-zA-Z0-9\-]{21,23})""").find(thumb)?.groupValues?.get(1)
        val teraboxUrl = if (teraboxKey != null && !teraboxKey.startsWith("thumb_")) "https://terabox.app/s/$teraboxKey" else null

        val linkData = IteraLinkData(
            videoId = id,
            teraboxUrl = teraboxUrl,
            title = title,
            poster = thumb
        )

        return newMovieSearchResponse(
            name = title,
            url = linkData.toJson(),
            type = TvType.Movie
        ) {
            this.posterUrl = thumb
            this.quality = when (this@toSearchResponse.quality?.lowercase()) {
                "1080p" -> SearchQuality.HD
                "720p"  -> SearchQuality.HD
                "480p"  -> SearchQuality.SD
                else    -> null
            }
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        val ndus = TeraBoxExtractor.getSavedNdus()
        return Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            if (url.contains("127.0.0.1") || url.contains("master_")) {
                val content = LocalM3u8Server.get(url) ?: run {
                    val id = Regex("""master_([a-zA-Z0-9_\-]+)""").find(url)?.groupValues?.get(1)
                    if (id != null) LocalM3u8Server.get(id) else null
                }
                if (content != null) {
                    return@Interceptor Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(content.toResponseBody("application/vnd.apple.mpegurl".toMediaTypeOrNull()))
                        .addHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .addHeader("Access-Control-Allow-Origin", "*")
                        .build()
                }
            }
            if (url.contains("getindevice.com")) {
                val reqBuilder = request.newBuilder()
                reqBuilder.header("User-Agent", TeraBoxExtractor.USER_AGENT)
                reqBuilder.header("Referer", "https://getindevice.com/")
                reqBuilder.header("Accept", "*/*")
                return@Interceptor chain.proceed(reqBuilder.build())
            }
            if (url.contains("flowvideoplayer.com") || url.contains("workers.dev")) {
                val reqBuilder = request.newBuilder()
                reqBuilder.header("User-Agent", TeraBoxExtractor.USER_AGENT)
                reqBuilder.header("Referer", "https://flowvideoplayer.com/")
                reqBuilder.header("Accept", "*/*")
                return@Interceptor chain.proceed(reqBuilder.build())
            }
            if (url.contains("terabox") || url.contains("1024tera")) {
                val reqBuilder = request.newBuilder()
                reqBuilder.header("User-Agent", TeraBoxExtractor.USER_AGENT)
                reqBuilder.header("Referer", "https://www.1024terabox.com/")
                reqBuilder.header("Accept", "*/*")
                reqBuilder.header("Connection", "keep-alive")
                if (!ndus.isNullOrBlank()) {
                    reqBuilder.header("Cookie", "ndus=$ndus")
                }
                return@Interceptor chain.proceed(reqBuilder.build())
            } else {
                chain.proceed(request)
            }
        }
    }
}

data class IteraEncryptedResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("data") val data: String? = null
)

data class IteraDecryptedCatalog(
    @JsonProperty("list") val list: List<IteraVideoItem>? = null,
    @JsonProperty("total") val total: Int? = null
)

data class IteraVideoItem(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("file_name") val fileName: String? = null,
    @JsonProperty("file_size") val fileSize: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("duration") val duration: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("total_views") val totalViews: Long? = null,
    @JsonProperty("created_at") val createdAt: String? = null
)

data class IteraAccessResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("terabox_id") val teraboxId: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("error") val error: String? = null
)

data class IteraLinkData(
    val videoId: Long? = null,
    val teraboxUrl: String? = null,
    val title: String? = null,
    val poster: String? = null,
    val fsId: String? = null,
    val filePath: String? = null,
    val episodeIndex: Int? = null,
    val directStreamUrl: String? = null,
    val hlsStreamUrl: String? = null
)
