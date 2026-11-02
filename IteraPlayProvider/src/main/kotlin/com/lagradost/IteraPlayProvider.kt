package com.lagradost

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
        TvType.Others
    )

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val PREFS_NAME = "iteraplay_prefs"
        private const val KEY_REMEMBER_ME = "remember_me_cookie"

        @Volatile var authCookie: String? = null
        @Volatile private var sessionCookies: String? = null

        fun getSavedCookie(context: Context? = null): String? {
            if (!authCookie.isNullOrBlank()) return authCookie
            val ctx = context ?: CommonActivity.activity ?: return null
            return runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val c = prefs.getString(KEY_REMEMBER_ME, null)
                authCookie = c
                c
            }.getOrNull()
        }

        fun saveCookie(context: Context? = null, cookie: String?) {
            authCookie = cookie
            val ctx = context ?: CommonActivity.activity ?: return
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

    private suspend fun getSessionCookies(): String {
        if (!sessionCookies.isNullOrBlank()) return sessionCookies!!
        return runCatching {
            val res = app.get("$mainUrl/", headers = mapOf("User-Agent" to USER_AGENT), timeout = 8)
            val cookies = res.headers
                .filter { it.first.equals("set-cookie", ignoreCase = true) }
                .map { it.second.substringBefore(";").trim() }
                .filter { it.isNotBlank() }
                .joinToString("; ")
            if (cookies.isNotBlank()) {
                sessionCookies = cookies
            }
            cookies
        }.getOrDefault("")
    }

    override val mainPage = mainPageOf(
        "latest"   to "Latest Uploads",
        "trending" to "Trending Viral Videos",
        "random"   to "Random Discover",
        "720p"     to "HD 720p Videos",
        "1080p"    to "Full HD 1080p"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sort = request.data
        val offset = (page - 1) * 20
        val quality = if (sort == "720p" || sort == "1080p") sort else ""
        val effectiveSort = if (sort == "720p" || sort == "1080p") "trending" else sort

        val cookie = getSavedCookie()
        val (targetUrl, reqHeaders) = if (!cookie.isNullOrBlank()) {
            "$mainUrl/api/home/search?q=&sort=$effectiveSort&limit=20&offset=$offset&quality=$quality&duration=" to mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/premium-videos",
                "Cookie" to "remember_me=$cookie",
                "Accept" to "application/json, text/plain, */*"
            )
        } else {
            "$altCatalogUrl/api/home/search?sort=$effectiveSort&offset=$offset&limit=20&quality=$quality&duration=" to mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$altCatalogUrl/",
                "Accept" to "application/json, text/plain, */*"
            )
        }

        val resText = runCatching {
            app.get(targetUrl, headers = reqHeaders, timeout = 10).text
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())

        val encryptedObj = runCatching { parseJson<IteraEncryptedResponse>(resText) }.getOrNull()
        val encryptedData = encryptedObj?.data ?: return newHomePageResponse(request.name, emptyList())

        val decryptedJson = IteraPlayCrypto.decrypt(encryptedData) ?: return newHomePageResponse(request.name, emptyList())
        val catalog = runCatching { parseJson<IteraDecryptedCatalog>(decryptedJson) }.getOrNull()
        val items = catalog?.list?.mapNotNull { it.toSearchResponse(mainUrl) } ?: emptyList()

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        // 1. Direct TeraBox Link Handling:
        if (cleanQuery.contains("terabox") || cleanQuery.contains("1024tera")) {
            val directLinkData = IteraLinkData(
                teraboxUrl = cleanQuery,
                title = "Direct TeraBox Stream"
            )
            return listOf(
                newMovieSearchResponse(
                    name = "TeraBox: ${cleanQuery.substringAfterLast("/")}",
                    url = directLinkData.toJson(),
                    type = TvType.Movie
                ) {
                    this.posterUrl = "https://raw.githubusercontent.com/Adiversion/csext/builds/IteraPlay_icon.png"
                }
            )
        }

        // 2. Keyword Search on Catalog API:
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val cookie = getSavedCookie()
        val (targetUrl, reqHeaders) = if (!cookie.isNullOrBlank()) {
            "$mainUrl/api/home/search?q=$encodedQuery&sort=&limit=20&offset=0&quality=&duration=" to mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/premium-videos",
                "Cookie" to "remember_me=$cookie",
                "Accept" to "application/json, text/plain, */*"
            )
        } else {
            "$altCatalogUrl/api/home/search?q=$encodedQuery&sort=&limit=20&offset=0&quality=&duration=" to mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$altCatalogUrl/",
                "Accept" to "application/json, text/plain, */*"
            )
        }

        val resText = runCatching {
            app.get(targetUrl, headers = reqHeaders, timeout = 10).text
        }.getOrNull() ?: return emptyList()

        val encryptedObj = runCatching { parseJson<IteraEncryptedResponse>(resText) }.getOrNull()
        val encryptedData = encryptedObj?.data ?: return emptyList()

        val decryptedJson = IteraPlayCrypto.decrypt(encryptedData) ?: return emptyList()
        val catalog = runCatching { parseJson<IteraDecryptedCatalog>(decryptedJson) }.getOrNull()

        return catalog?.list?.mapNotNull { it.toSearchResponse(mainUrl) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // A. Direct LinkData passed from search:
        if (url.startsWith("{") && url.endsWith("}")) {
            val linkData = runCatching { parseJson<IteraLinkData>(url) }.getOrNull()
            if (linkData != null) {
                return newMovieLoadResponse(
                    name = linkData.title ?: "TeraBox Media",
                    url = url,
                    type = TvType.Movie,
                    dataUrl = url
                ) {
                    this.posterUrl = linkData.poster ?: "https://raw.githubusercontent.com/Adiversion/csext/builds/IteraPlay_icon.png"
                    this.plot = "Direct TeraBox streaming media."
                }
            }
        }

        // B. Standard /video/{id} URL:
        val videoId = Regex("""/video/(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw ErrorLoadingException("Invalid video URL: $url")

        val cookie = getSavedCookie()
        val accessUrl = "$mainUrl/api/video/access/$videoId"
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )
        if (!cookie.isNullOrBlank()) {
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

        val teraboxUrl = accessRes?.url
        val linkData = IteraLinkData(
            videoId = videoId,
            teraboxUrl = teraboxUrl,
            title = "Video #$videoId"
        )

        return newMovieLoadResponse(
            name = "Video #$videoId",
            url = url,
            type = TvType.Movie,
            dataUrl = linkData.toJson()
        ) {
            this.posterUrl = "https://raw.githubusercontent.com/Adiversion/csext/builds/IteraPlay_icon.png"
            this.plot = if (teraboxUrl.isNullOrBlank() && cookie.isNullOrBlank()) {
                "Guest playback limit reached. To watch unlimited videos, add your iteraplay 'remember_me' cookie in plugin settings."
            } else {
                "iTeraPlay Video stream #$videoId"
            }
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

        // If terabox URL wasn't retrieved in load(), attempt access one more time:
        if (teraboxUrl.isNullOrBlank() && linkData.videoId != null) {
            val cookie = getSavedCookie()
            val accessUrl = "$mainUrl/api/video/access/${linkData.videoId}"
            val headers = mutableMapOf(
                "Content-Type" to "application/json",
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
            if (!cookie.isNullOrBlank()) {
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
            teraboxUrl = accessRes?.url
        }

        if (teraboxUrl.isNullOrBlank()) {
            return false
        }

        // Delegate to registered TeraBox extractors (supports Direct TeraBox, Custom Gateway, and Transcode Worker)
        var foundLinks = false
        val customCallback: (ExtractorLink) -> Unit = { link ->
            foundLinks = true
            callback(link)
        }

        runCatching {
            loadExtractor(teraboxUrl, subtitleCallback, customCallback)
        }

        if (foundLinks) {
            return true
        }

        // Fallback: Direct internal call to /api/stream if extractor was not invoked
        var cookies = getSessionCookies()
        val auth = getSavedCookie()
        val combinedCookie = listOfNotNull(
            cookies.ifBlank { null },
            if (!auth.isNullOrBlank()) "remember_me=$auth" else null
        ).joinToString("; ")

        val streamHeaders = mutableMapOf(
            "Content-Type" to "application/json",
            "User-Agent" to USER_AGENT,
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )
        if (combinedCookie.isNotBlank()) {
            streamHeaders["Cookie"] = combinedCookie
        }

        var streamRes = runCatching {
            app.post(
                "$mainUrl/api/stream",
                headers = streamHeaders,
                json = mapOf("url" to teraboxUrl),
                timeout = 15
            ).parsedSafe<IteraStreamResponse>()
        }.getOrNull()

        // Self-Healing token refresh if verification token expired
        if (streamRes?.status != "success") {
            CommonActivity.activity?.let { act ->
                act.runOnUiThread {
                    android.widget.Toast.makeText(
                        act,
                        "iTeraPlay: Guest limit (5 videos/6h) reached. Switch mobile data or login in settings.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            sessionCookies = null
            cookies = getSessionCookies()
            val retryCookie = listOfNotNull(
                cookies.ifBlank { null },
                if (!auth.isNullOrBlank()) "remember_me=$auth" else null
            ).joinToString("; ")
            if (retryCookie.isNotBlank()) {
                streamHeaders["Cookie"] = retryCookie
            }
            streamRes = runCatching {
                app.post(
                    "$mainUrl/api/stream",
                    headers = streamHeaders,
                    json = mapOf("url" to teraboxUrl),
                    timeout = 15
                ).parsedSafe<IteraStreamResponse>()
            }.getOrNull()
        }

        foundLinks = false
        streamRes?.list?.forEach { file ->
            val fastStreams = file.fastStreamUrl ?: return@forEach
            fastStreams.forEach { (qualityKey, m3u8Url) ->
                if (m3u8Url.isNotBlank() && m3u8Url.startsWith("http")) {
                    foundLinks = true
                    val qValue = when {
                        qualityKey.contains("1080") -> Qualities.P1080.value
                        qualityKey.contains("720")  -> Qualities.P720.value
                        qualityKey.contains("480")  -> Qualities.P480.value
                        qualityKey.contains("360")  -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "${file.name ?: "Video"} - [$qualityKey ⚡]",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = qValue
                        }
                    )
                }
            }

            if (!file.subtitleUrl.isNullOrBlank() && file.subtitleUrl.startsWith("http")) {
                subtitleCallback(
                    SubtitleFile(
                        lang = "English (AI)",
                        url = file.subtitleUrl
                    )
                )
            }
        }

        return foundLinks
    }

    private fun IteraVideoItem.toSearchResponse(baseUrl: String): SearchResponse? {
        val id = this.id ?: return null
        val title = this.fileName?.removeSuffix(".mp4")?.replace("_", " ")?.trim() ?: "Video #$id"
        val thumb = this.thumbnail ?: ""
        val teraboxKey = Regex("""thumbnails/(1[a-zA-Z0-9_\-]{22})_""").find(thumb)?.groupValues?.get(1)
        val teraboxUrl = if (teraboxKey != null) "https://terabox.app/s/$teraboxKey" else null

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

data class IteraStreamResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("total_files") val totalFiles: Int? = null,
    @JsonProperty("list") val list: List<IteraStreamItem>? = null
)

data class IteraStreamItem(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("size_formatted") val sizeFormatted: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("duration") val duration: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("fast_stream_url") val fastStreamUrl: Map<String, String>? = null,
    @JsonProperty("subtitle_url") val subtitleUrl: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null
)

data class IteraLinkData(
    val videoId: Long? = null,
    val teraboxUrl: String? = null,
    val title: String? = null,
    val poster: String? = null
)
