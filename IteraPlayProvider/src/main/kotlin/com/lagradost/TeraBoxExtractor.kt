package com.lagradost

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

        fun getSavedNdus(context: Context? = null): String? {
            if (!ndusCookie.isNullOrBlank()) return ndusCookie
            val ctx = context ?: CommonActivity.activity

            // 1. Check SharedPreferences
            val fromPrefs = runCatching {
                val prefs = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs?.getString(KEY_NDUS, null)
            }.getOrNull()

            if (!fromPrefs.isNullOrBlank()) {
                ndusCookie = fromPrefs
                return fromPrefs
            }

            // 2. Automatic Auto-Detection from Android CookieManager:
            val fromCookieManager = runCatching {
                val cookieManager = android.webkit.CookieManager.getInstance()
                val cookies = cookieManager.getCookie("https://www.terabox.app")
                    ?: cookieManager.getCookie("https://www.terabox.com")
                    ?: ""
                Regex("""\bndus=([^;]+)""").find(cookies)?.groupValues?.get(1)
            }.getOrNull()

            if (!fromCookieManager.isNullOrBlank()) {
                saveNdus(ctx, fromCookieManager)
                return fromCookieManager
            }

            return null
        }

        fun saveNdus(context: Context? = null, cookie: String?) {
            ndusCookie = cookie
            val ctx = context ?: CommonActivity.activity ?: return
            runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (cookie.isNullOrBlank()) {
                    prefs.edit().remove(KEY_NDUS).apply()
                } else {
                    prefs.edit().putString(KEY_NDUS, cookie).apply()
                }
            }
        }

        fun getSavedCustomWorker(context: Context? = null): String? {
            if (!customWorkerUrl.isNullOrBlank()) return customWorkerUrl
            val ctx = context ?: CommonActivity.activity ?: return null
            return runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val w = prefs.getString(KEY_CUSTOM_WORKER, null)
                customWorkerUrl = w
                w
            }.getOrNull()
        }

        fun saveCustomWorker(context: Context? = null, worker: String?) {
            customWorkerUrl = worker
            val ctx = context ?: CommonActivity.activity ?: return
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

        // 1. Direct Native TeraBox Engine (Highest priority, 100% direct official streaming with 0 third-party dependency):
        if (!ndus.isNullOrBlank()) {
            val resolvedDirect = resolveDirectTeraBox(cleanUrl, ndus, callback)
            if (resolvedDirect) return
        }

        // 2. Custom Gateway / Self-Hosted Worker Engine (0 third-party dependency):
        val customWorker = getSavedCustomWorker()
        if (!customWorker.isNullOrBlank()) {
            val resolvedCustom = resolveViaCustomWorker(cleanUrl, customWorker, callback)
            if (resolvedCustom) return
        }

        // 3. Parallel Redundant Native Engines (Zero Login, Zero Captcha):
        // Run both iTeraPlay.in and TeraVideo resolvers concurrently so all stream qualities are available
        // and playback NEVER fails if any single upstream server or worker experiences downtime.
        coroutineScope {
            val jobIn = async { resolveViaIteraPlayIn(cleanUrl, callback) }
            val jobTv = async { resolveViaTeraVideo(cleanUrl, callback) }
            jobIn.await()
            jobTv.await()
        }
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
                timeout = 15
            ).parsedSafe<IteraPlayInImportResponse>()

            val videos = res?.videos
            if (res?.success == true && !videos.isNullOrEmpty()) {
                var found = false
                for (video in videos) {
                    val title = video.title?.removeSuffix(".mp4")?.replace("_", " ")?.trim() ?: "TeraBox Video"
                    val fastStreams = video.fastStreamUrl
                    if (fastStreams != null && fastStreams.isNotEmpty()) {
                        for ((qName, hlsUrl) in fastStreams) {
                            if (hlsUrl.isBlank()) continue
                            val q = when (qName.lowercase()) {
                                "1080p" -> Qualities.P1080.value
                                "720p"  -> Qualities.P720.value
                                "480p"  -> Qualities.P480.value
                                "360p"  -> Qualities.P360.value
                                else    -> Qualities.Unknown.value
                            }
                            callback(
                                newExtractorLink(
                                    source = "iTeraPlay HLS",
                                    name = "$title - [$qName HLS ⚡]",
                                    url = hlsUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://iteraplay.in/"
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
                                name = "$title - [Direct MP4 ⚡]",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://iteraplay.in/"
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
            val listUrl = "https://www.terabox.app/share/list?app_id=250528&shorturl=$surl&root=1"
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Cookie" to "ndus=$ndus",
                "Referer" to "https://www.terabox.app/sharing/link?surl=$surl"
            )
            val res = app.get(listUrl, headers = headers, timeout = 10).parsedSafe<TeraBoxShareListResponse>() ?: return false
            val file = res.list?.firstOrNull() ?: return false
            val dlink = file.dlink

            if (!dlink.isNullOrBlank() && dlink.startsWith("http")) {
                callback(
                    newExtractorLink(
                        source = "$name Direct",
                        name = "${file.serverFilename ?: "TeraBox Video"} - [Direct 🚀]",
                        url = dlink,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://www.terabox.app/"
                        this.quality = Qualities.P1080.value
                    }
                )
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private suspend fun resolveViaTeraVideo(
        rawUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val surl = extractSurl(rawUrl) ?: return false
            val canonicalKey = if (surl.startsWith("1")) surl else "1$surl"
            val fullTeraBoxUrl = "https://terabox.app/s/$canonicalKey"
            val encodedUrl = URLEncoder.encode(fullTeraBoxUrl, "UTF-8")
            val target = "https://teravideo.in/terabox-api/api.php?action=resolve&url=$encodedUrl"
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://teravideo.in/terabox-player-online/",
                "Origin" to "https://teravideo.in"
            )
            val res = app.get(target, headers = headers, timeout = 12).parsedSafe<TeraVideoResponse>()
            val active = res?.data?.activeFile ?: res?.data?.file
            if (res?.success == true && active != null) {
                val videoName = active.name ?: res.data?.title ?: "TeraBox Video"
                var found = false

                val masterUrl = active.masterUrl
                if (!masterUrl.isNullOrBlank()) {
                    val fullMaster = if (masterUrl.startsWith("http")) masterUrl else "https://teravideo.in/terabox-api/$masterUrl"
                    callback(
                        newExtractorLink(
                            source = "TeraVideo HLS",
                            name = "$videoName - [TeraVideo Adaptive HLS ⚡]",
                            url = fullMaster,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://teravideo.in/terabox-player-online/"
                            this.quality = Qualities.P720.value
                        }
                    )
                    found = true
                }

                val hlsUrl = active.hlsUrl
                if (!hlsUrl.isNullOrBlank() && hlsUrl != masterUrl) {
                    val fullHls = if (hlsUrl.startsWith("http")) hlsUrl else "https://teravideo.in/terabox-api/$hlsUrl"
                    callback(
                        newExtractorLink(
                            source = "TeraVideo HLS",
                            name = "$videoName - [TeraVideo 480p HLS]",
                            url = fullHls,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://teravideo.in/terabox-player-online/"
                            this.quality = Qualities.P480.value
                        }
                    )
                    found = true
                }

                val streamUrl = active.streamUrl
                if (!streamUrl.isNullOrBlank()) {
                    val fullStream = if (streamUrl.startsWith("http")) streamUrl else "https://teravideo.in/terabox-api/$streamUrl"
                    callback(
                        newExtractorLink(
                            source = "TeraVideo Direct",
                            name = "$videoName - [TeraVideo Direct Stream ⚡]",
                            url = fullStream,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://teravideo.in/terabox-player-online/"
                            this.quality = Qualities.P720.value
                        }
                    )
                    found = true
                }

                found
            } else {
                false
            }
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

data class TeraVideoResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("data") val data: TeraVideoData? = null
)

data class TeraVideoData(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("active_file") val activeFile: TeraVideoFile? = null,
    @JsonProperty("file") val file: TeraVideoFile? = null
)

data class TeraVideoFile(
    @JsonProperty("fs_id") val fsId: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("duration") val duration: Long? = null,
    @JsonProperty("masterUrl") val masterUrl: String? = null,
    @JsonProperty("hlsUrl") val hlsUrl: String? = null,
    @JsonProperty("streamUrl") val streamUrl: String? = null
)

data class TeraBoxShareListResponse(
    @JsonProperty("errno") val errno: Int? = null,
    @JsonProperty("list") val list: List<TeraBoxFileInfo>? = null
)

data class TeraBoxFileInfo(
    @JsonProperty("server_filename") val serverFilename: String? = null,
    @JsonProperty("fs_id") val fsId: String? = null,
    @JsonProperty("dlink") val dlink: String? = null,
    @JsonProperty("size") val size: Long? = null
)
