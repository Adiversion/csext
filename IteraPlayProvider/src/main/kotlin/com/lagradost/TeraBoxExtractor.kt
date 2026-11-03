package com.lagradost

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLDecoder
import java.net.URLEncoder

open class TeraBoxExtractor : ExtractorApi() {
    override val name = "TeraBox"
    override val mainUrl = "https://terabox.app"
    override val requiresReferer = false

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val PREFS_NAME = "terabox_prefs"
        private const val KEY_NDUS = "terabox_ndus_cookie"
        private const val KEY_CUSTOM_WORKER = "custom_worker_url"

        @Volatile var ndusCookie: String? = null
        @Volatile var customWorkerUrl: String? = null
        @Volatile var appContext: Context? = null

        val CANDIDATE_DOMAINS = listOf(
            "https://www.1024terabox.com",
            "https://1024terabox.com",
            "https://www.terabox.app",
            "https://terabox.app",
            "https://www.terabox.com",
            "https://terabox.com",
            "https://dm.terabox.com",
            "https://dm.1024tera.com",
            "https://www.1024tera.com",
            "https://1024tera.com",
            "https://www.freeterabox.com",
            "https://freeterabox.com",
            "https://www.teraboxlink.com",
            "https://teraboxlink.com",
            "https://passport.terabox.com",
            "https://passport.1024terabox.com",
            "https://pan.terabox.com",
            "https://pan.1024terabox.com",
            "https://accounts.google.com"
        )

        fun extractNdus(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val match = Regex("""(?i)\bndus=([^;\s"']+)""").find(raw)
            val token = match?.groupValues?.get(1)?.trim() ?: if (!raw.contains("=") && raw.trim().length >= 8) raw.trim() else null
            return if (!token.isNullOrBlank() && token.length >= 8) token else null
        }

        fun scanCookieManagerForNdus(extraUrl: String? = null): String? {
            return runCatching {
                val cm = android.webkit.CookieManager.getInstance()
                runCatching { cm.flush() }
                val targets = mutableListOf<String>()
                if (!extraUrl.isNullOrBlank()) {
                    targets.add(extraUrl)
                }
                for (domain in CANDIDATE_DOMAINS) {
                    targets.add(domain)
                    targets.add("$domain/")
                    targets.add("$domain/main")
                    targets.add("$domain/login")
                }
                for (t in targets) {
                    val c = cm.getCookie(t)
                    val token = extractNdus(c)
                    if (!token.isNullOrBlank()) {
                        return@runCatching token
                    }
                }
                null
            }.getOrNull()
        }

        fun getSavedNdus(context: Context? = null): String? {
            if (!ndusCookie.isNullOrBlank()) return ndusCookie
            val ctx = context ?: appContext ?: CommonActivity.activity

            // 1. Check SharedPreferences
            val fromPrefs = runCatching {
                val prefs = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs?.getString(KEY_NDUS, null)
            }.getOrNull()

            if (!fromPrefs.isNullOrBlank()) {
                ndusCookie = fromPrefs
                return fromPrefs
            }

            // 2. Automatic Auto-Detection from Android CookieManager across all candidate domains:
            val fromCookieManager = scanCookieManagerForNdus()
            if (!fromCookieManager.isNullOrBlank()) {
                saveNdus(ctx, fromCookieManager)
                return fromCookieManager
            }

            return null
        }

        fun saveNdus(context: Context? = null, cookie: String?) {
            val clean = extractNdus(cookie) ?: cookie?.trim()
            ndusCookie = if (!clean.isNullOrBlank()) clean else null
            val ctx = context ?: appContext ?: CommonActivity.activity
            runCatching {
                if (ctx != null) {
                    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    if (clean.isNullOrBlank()) {
                        prefs.edit().remove(KEY_NDUS).apply()
                    } else {
                        prefs.edit().putString(KEY_NDUS, clean).apply()
                    }
                }
                val cm = android.webkit.CookieManager.getInstance()
                if (!clean.isNullOrBlank()) {
                    for (domain in CANDIDATE_DOMAINS) {
                        val host = domain.removePrefix("https://").removePrefix("http://")
                        val rootDomain = host.substringAfter("www.")
                        cm.setCookie(domain, "ndus=$clean; domain=.$rootDomain; path=/")
                    }
                    cm.flush()
                }
            }
        }

        fun getSavedCustomWorker(context: Context? = null): String? {
            if (!customWorkerUrl.isNullOrBlank()) return customWorkerUrl
            val ctx = context ?: appContext ?: CommonActivity.activity ?: return null
            return runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val w = prefs.getString(KEY_CUSTOM_WORKER, null)
                customWorkerUrl = w
                w
            }.getOrNull()
        }

        fun saveCustomWorker(context: Context? = null, worker: String?) {
            customWorkerUrl = worker
            val ctx = context ?: appContext ?: CommonActivity.activity ?: return
            runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (worker.isNullOrBlank()) {
                    prefs.edit().remove(KEY_CUSTOM_WORKER).apply()
                } else {
                    prefs.edit().putString(KEY_CUSTOM_WORKER, worker).apply()
                }
            }
        }

        fun extractSurl(rawUrl: String): String? {
            var surl = Regex("""(?:/s/|surl=)([a-zA-Z0-9_\-]+)""").find(rawUrl)?.groupValues?.get(1) ?: return null
            if (surl.startsWith("1") && surl.length == 23) {
                surl = surl.substring(1)
            }
            return surl
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        val ndus = getSavedNdus()
        val customWorker = getSavedCustomWorker()

        // 1. Direct Official TeraBox (Fastest 1080p, bypasses all 3rd party sites)
        // If user is logged in with ndus, resolve directly via TeraBox CDN first!
        if (!ndus.isNullOrBlank()) {
            val directFound = resolveDirectTeraBox(cleanUrl, ndus, callback)
            if (directFound) return
        }

        // 2. Private Gateway Worker (if configured)
        if (!customWorker.isNullOrBlank()) {
            val workerFound = resolveViaCustomWorker(cleanUrl, customWorker, callback)
            if (workerFound) return
        }

        // 3. Fallback to free upstream (iTeraPlay.in Multi-Quality 1080p Engine)
        resolveViaIteraPlayIn(cleanUrl, callback)
    }

    private suspend fun resolveViaIteraPlayIn(
        rawUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val surl = extractSurl(rawUrl) ?: return false
            val canonicalKey = if (surl.startsWith("1")) surl else "1$surl"
            val fullTeraBoxUrl = "https://terabox.app/s/$canonicalKey"
            val target = "https://iteraplay.in/api/videos/import"
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Content-Type" to "application/json",
                "Origin" to "https://iteraplay.in",
                "Referer" to "https://iteraplay.in/"
            )
            val res = app.post(
                target,
                headers = headers,
                json = mapOf("url" to fullTeraBoxUrl),
                timeout = 35
            ).parsedSafe<IteraPlayInImportResponse>()

            val videos = res?.videos
            if (res?.success == true && !videos.isNullOrEmpty()) {
                var found = false
                for (video in videos) {
                    val title = video.title?.removeSuffix(".mp4")?.replace("_", " ")?.trim() ?: "TeraBox Video"
                    val fastStreams = video.fastStreamUrl
                    if (fastStreams != null && fastStreams.isNotEmpty()) {
                        val orderedKeys = listOf("1080p", "720p", "480p", "360p")
                        val sortedEntries = fastStreams.entries.sortedBy { entry ->
                            val idx = orderedKeys.indexOf(entry.key.lowercase())
                            if (idx != -1) idx else 99
                        }

                        for ((qName, rawHlsUrl) in sortedEntries) {
                            if (rawHlsUrl.isBlank()) continue
                            val q = when (qName.lowercase()) {
                                "1080p" -> Qualities.P1080.value
                                "720p"  -> Qualities.P720.value
                                "480p"  -> Qualities.P480.value
                                "360p"  -> Qualities.P360.value
                                else    -> Qualities.Unknown.value
                            }

                            // v.cdniteraplay.qzz.io/hls?token= returns the raw video segment URL, not an #EXTM3U playlist
                            val finalStreamUrl = if (rawHlsUrl.contains("/hls?token=")) {
                                runCatching {
                                    val body = app.get(
                                        rawHlsUrl,
                                        headers = mapOf("Referer" to "https://iteraplay.in/"),
                                        timeout = 10
                                    ).text.trim()
                                    if (body.startsWith("http")) body else rawHlsUrl
                                }.getOrDefault(rawHlsUrl)
                            } else rawHlsUrl

                            val isPlaylist = finalStreamUrl.contains(".m3u8") || finalStreamUrl.contains("#EXTM3U")
                            val linkType = if (isPlaylist) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                            callback(
                                newExtractorLink(
                                    source = "iTeraPlay Stream",
                                    name = "$title - [$qName Stream ⚡]",
                                    url = finalStreamUrl,
                                    type = linkType
                                ) {
                                    this.referer = "https://iteraplay.in/"
                                    this.headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to "https://iteraplay.in/",
                                        "Origin" to "https://iteraplay.in"
                                    )
                                    this.quality = q
                                }
                            )
                            found = true
                        }
                    }

                    val streamUrl = video.streamUrl
                    if (!streamUrl.isNullOrBlank()) {
                        callback(
                            newExtractorLink(
                                source = "iTeraPlay Direct",
                                name = "$title - [1080p Direct MP4 ⚡]",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://iteraplay.in/"
                                this.headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to "https://iteraplay.in/",
                                    "Origin" to "https://iteraplay.in"
                                )
                                this.quality = Qualities.P1080.value
                            }
                        )
                        found = true
                    }
                }
                found
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private suspend fun resolveViaCustomWorker(
        rawUrl: String,
        workerUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val surl = extractSurl(rawUrl) ?: return false
            val baseWorker = workerUrl.trimEnd('/')
            val streamUrl = "$baseWorker/?mode=stream&surl=$surl&type=M3U8_AUTO_720"
            callback(
                newExtractorLink(
                    source = "$name Gateway",
                    name = "TeraBox HD - [Private Gateway ⚡]",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://www.terabox.app/"
                    this.quality = Qualities.P720.value
                }
            )
            true
        }.getOrDefault(false)
    }

    private suspend fun resolveDirectTeraBox(
        rawUrl: String,
        ndus: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val surl = extractSurl(rawUrl) ?: return false
            val candidateHosts = listOf("dm.terabox.com", "1024tera.com", "www.1024terabox.com", "www.terabox.app", "www.terabox.com")
            
            var jsToken: String? = null
            var usedHost = "dm.terabox.com"

            for (host in candidateHosts) {
                val wapUrl = "https://$host/wap/share/filelist?surl=$surl"
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Cookie" to "ndus=$ndus",
                    "Referer" to "https://$host/sharing/link?surl=$surl"
                )
                val html = runCatching {
                    app.get(wapUrl, headers = headers, timeout = 10).text
                }.getOrNull() ?: continue

                // 1. Extract dynamic jsToken from WAP eval decodeURIComponent
                val evalMatch = Regex("""try\s*\{eval\(decodeURIComponent\([`"']([^`"']+)`\)""").find(html)
                if (evalMatch != null) {
                    val decoded = runCatching { URLDecoder.decode(evalMatch.groupValues[1], "UTF-8") }.getOrNull()
                    if (decoded != null) {
                        val tm = Regex("""fn\([`"']([a-zA-Z0-9_\-]+)[`"']\)""").find(decoded)
                            ?: Regex("""jsToken\s*=\s*[`"']([a-zA-Z0-9_\-]+)[`"']""").find(decoded)
                        jsToken = tm?.groupValues?.get(1)
                    }
                }
                // 2. Direct regex token fallback
                if (jsToken.isNullOrBlank()) {
                    val direct = Regex("""%22([A-F0-9]{64,128})%22""", RegexOption.IGNORE_CASE).find(html)
                        ?: Regex("""jsToken["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                    jsToken = direct?.groupValues?.get(1)
                }

                if (!jsToken.isNullOrBlank()) {
                    usedHost = host
                    break
                }
            }

            val tokenParam = if (!jsToken.isNullOrBlank()) "&jsToken=$jsToken" else ""
            val rootUrl = "https://$usedHost/share/list?app_id=250528${tokenParam}&shorturl=$surl&root=1"
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Cookie" to "ndus=$ndus",
                "Referer" to "https://$usedHost/wap/share/filelist?surl=$surl"
            )

            val rootRes = runCatching {
                app.get(rootUrl, headers = headers, timeout = 10).parsedSafe<TeraBoxShareListResponse>()
            }.getOrNull()

            val initialList = rootRes?.list
            if (initialList.isNullOrEmpty()) return false

            val queue = ArrayDeque<TeraBoxFileInfo>()
            queue.addAll(initialList)
            val foundVideos = mutableListOf<TeraBoxFileInfo>()
            var folderDepth = 0

            // Recursive folder traversal to extract video files from multi-file shares or subdirectories
            while (queue.isNotEmpty() && folderDepth < 10) {
                val item = queue.removeFirst()
                val isDir = item.isdir?.toString() == "1"
                if (isDir && !item.path.isNullOrBlank()) {
                    folderDepth++
                    val dirParam = URLEncoder.encode(item.path, "UTF-8")
                    val subUrl = "https://$usedHost/share/list?app_id=250528${tokenParam}&shorturl=$surl&root=0&dir=$dirParam"
                    val subRes = runCatching {
                        app.get(subUrl, headers = headers, timeout = 10).parsedSafe<TeraBoxShareListResponse>()
                    }.getOrNull()
                    val subList = subRes?.list
                    if (!subList.isNullOrEmpty()) {
                        queue.addAll(subList)
                    }
                } else if (!item.dlink.isNullOrBlank()) {
                    val name = item.serverFilename?.lowercase() ?: ""
                    val isVideo = name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") ||
                            name.endsWith(".mov") || name.endsWith(".avi") || name.endsWith(".m4v") ||
                            name.endsWith(".3gp") || (item.size ?: 0L) > 1_000_000L
                    if (isVideo) {
                        foundVideos.add(item)
                    }
                }
            }

            var found = false
            for (file in foundVideos) {
                val dlink = file.dlink ?: continue
                if (dlink.startsWith("http")) {
                    val name = file.serverFilename?.removeSuffix(".mp4")?.replace("_", " ")?.trim() ?: "TeraBox Video"
                    val headersMap = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://www.1024terabox.com/",
                        "Cookie" to "ndus=$ndus",
                        "Accept" to "*/*",
                        "Connection" to "keep-alive"
                    )
                    callback(
                        newExtractorLink(
                            source = "$name Direct",
                            name = "$name - [1080p Official 🚀]",
                            url = dlink,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://www.1024terabox.com/"
                            this.headers = headersMap
                            this.quality = Qualities.P1080.value
                        }
                    )
                    found = true
                }
            }
            found
        }.getOrDefault(false)
    }
}

class TeraBoxComExtractor : TeraBoxExtractor() {
    override val mainUrl = "https://terabox.com"
}

class TeraBox1024Extractor : TeraBoxExtractor() {
    override val mainUrl = "https://1024tera.com"
}

class TeraBox1024BoxExtractor : TeraBoxExtractor() {
    override val mainUrl = "https://1024terabox.com"
}

class TeraBoxLinkExtractor : TeraBoxExtractor() {
    override val mainUrl = "https://teraboxlink.com"
}

data class IteraPlayInImportResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("videos") val videos: List<IteraPlayInVideoItem>? = null,
    @JsonProperty("message") val message: String? = null,
    @JsonProperty("error") val error: String? = null
)

data class IteraPlayInVideoItem(
    @JsonProperty("_id") val id: String? = null,
    @JsonProperty("fileId") val fileId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("duration") val duration: String? = null,
    @JsonProperty("thumbnailUrl") val thumbnailUrl: String? = null,
    @JsonProperty("stream_url") val streamUrl: String? = null,
    @JsonProperty("fast_stream_url") val fastStreamUrl: Map<String, String>? = null,
    @JsonProperty("resolutions") val resolutions: List<String>? = null
)

data class TeraBoxShareListResponse(
    @JsonProperty("errno") val errno: Int? = null,
    @JsonProperty("list") val list: List<TeraBoxFileInfo>? = null
)

data class TeraBoxFileInfo(
    @JsonProperty("server_filename") val serverFilename: String? = null,
    @JsonProperty("fs_id") val fsId: Any? = null,
    @JsonProperty("dlink") val dlink: String? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("isdir") val isdir: Any? = null,
    @JsonProperty("path") val path: String? = null
)
