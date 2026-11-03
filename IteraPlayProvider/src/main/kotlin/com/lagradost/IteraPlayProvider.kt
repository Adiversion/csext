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
        @Volatile var appContext: Context? = null

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

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "random"   to "For You",
        "popular"  to "Popular",
        "recent"   to "Latest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sort = request.data
        val offset = (page - 1) * 20
        val effectiveSort = sort

        val cookie = getSavedCookie()
        val (targetUrl, reqHeaders) = if (!cookie.isNullOrBlank()) {
            "$mainUrl/api/home/search?q=&sort=$effectiveSort&limit=20&offset=$offset&quality=&duration=" to mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/premium-videos",
                "Cookie" to "remember_me=$cookie",
                "Accept" to "application/json, text/plain, */*"
            )
        } else {
            "$altCatalogUrl/api/home/search?sort=$effectiveSort&offset=$offset&limit=20&quality=&duration=" to mapOf(
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
        if (cleanQuery.isEmpty()) return emptyList()

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
            "$mainUrl/api/home/search?q=$encodedQuery&sort=&limit=25&offset=0" to mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "Cookie" to "remember_me=$cookie",
                "Accept" to "application/json, text/plain, */*"
            )
        } else {
            "$altCatalogUrl/api/home/search?q=$encodedQuery&sort=&limit=25&offset=0" to mapOf(
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
        val linkData = if (url.startsWith("{") && url.endsWith("}")) {
            runCatching { parseJson<IteraLinkData>(url) }.getOrNull()
        } else null

        val videoId = linkData?.videoId ?: Regex("""/video/(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw ErrorLoadingException("Invalid video URL: $url")

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
            poster = poster
        )

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

        if (teraboxUrl.isNullOrBlank() || teraboxUrl.contains("thumb_")) {
            return false
        }

        // Delegate to registered TeraBox extractors (iTeraPlay.in Multi-HLS/MP4 + TeraVideo Adaptive HLS)
        var foundLinks = false
        val customCallback: (ExtractorLink) -> Unit = { link ->
            foundLinks = true
            callback(link)
        }

        runCatching {
            loadExtractor(teraboxUrl, subtitleCallback, customCallback)
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
            val key = Regex("""thumbnails/(1[a-zA-Z0-9_\-]{22})""").find(thumb)?.groupValues?.get(1)
                ?: Regex("""(?:\/s\/|surl=)(1[a-zA-Z0-9_\-]{22})""").find(thumb)?.groupValues?.get(1)
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
        val teraboxKey = Regex("""thumbnails/(1[a-zA-Z0-9_\-]{22})""").find(thumb)?.groupValues?.get(1)
            ?: Regex("""(?:\/s\/|surl=)(1[a-zA-Z0-9_\-]{22})""").find(thumb)?.groupValues?.get(1)
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
            if (url.contains("virtual_master_")) {
                val master = TeraBoxExtractor.getMasterPlaylist(url)
                if (master != null) {
                    return@Interceptor Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(master.toResponseBody("application/vnd.apple.mpegurl".toMediaTypeOrNull()))
                        .addHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .build()
                }
            }
            if (url.contains("terabox") || url.contains("1024tera") || extractorLink.source.contains("TeraBox")) {
                val reqBuilder = request.newBuilder()
                reqBuilder.header("User-Agent", TeraBoxExtractor.USER_AGENT)
                reqBuilder.header("Referer", "https://www.1024terabox.com/")
                reqBuilder.header("Accept", "*/*")
                reqBuilder.header("Connection", "keep-alive")
                if (!ndus.isNullOrBlank()) {
                    reqBuilder.header("Cookie", "ndus=$ndus")
                }
                chain.proceed(reqBuilder.build())
            } else if (url.contains("cdniteraplay") || url.contains("iteraplay") || extractorLink.source.contains("iTeraPlay")) {
                val reqBuilder = request.newBuilder()
                reqBuilder.header("User-Agent", TeraBoxExtractor.USER_AGENT)
                reqBuilder.header("Referer", "https://iteraplay.in/")
                reqBuilder.header("Origin", "https://iteraplay.in")
                chain.proceed(reqBuilder.build())
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
    val poster: String? = null
)
