package com.lagradost

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class WaveFlixProvider : MainAPI() {
    override var name = "WaveFlix"
    override var mainUrl = "https://www.waveflix.online"
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val hasMainPage = true

    companion object {
        private val homeCache = mutableMapOf<String, Pair<Long, HomePageResponse>>()
        private const val CACHE_TTL_MS = 15 * 60 * 1000L // 15 minutes cache for smooth UI
    }

    override val mainPage = mainPageOf(
        "movie/trending" to "Trending Movies",
        "movie/now_playing" to "Now Playing Movies",
        "movie/top_rated" to "Top Rated Movies",
        "tv/trending" to "Trending TV Shows",
        "tv/on_the_air" to "TV Shows On The Air",
        "anime/trending" to "Anime"
    )

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cacheKey = "${request.data}/$page"
        val now = System.currentTimeMillis()
        synchronized(homeCache) {
            val cached = homeCache[cacheKey]
            if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
                return cached.second
            }
        }

        val parts = request.data.split("/")
        val mediaType = parts.getOrNull(0) ?: "movie"
        val category = parts.getOrNull(1) ?: "trending"

        val endpoint = if (mediaType == "anime") {
            "$mainUrl/api/discover?mediaType=anime&page=$page"
        } else {
            "$mainUrl/api/discover?mediaType=$mediaType&category=$category&page=$page"
        }

        val res = app.get(endpoint, headers = commonHeaders, timeout = 8)
            .parsedSafe<WaveSearchResponse>() ?: throw ErrorLoadingException("Failed to fetch WaveFlix discover catalog")

        val homeItems = res.results?.mapNotNull { item ->
            item.toSearchResponse()
        } ?: emptyList()

        val hasNext = page < (res.totalPages ?: 1)
        val response = newHomePageResponse(request.name, homeItems, hasNext)
        synchronized(homeCache) {
            homeCache[cacheKey] = now to response
        }
        return response
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/api/search?q=$encodedQuery"
        val res = app.get(searchUrl, headers = commonHeaders)
            .parsedSafe<WaveSearchResponse>() ?: return emptyList()

        return res.results?.mapNotNull { item ->
            item.toSearchResponse()
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val isMovie = url.contains("/movie/")
        val idMatch = Regex("""/(?:details|watch)/(?:movie|tv)/(\d+)""").find(url)
        val tmdbId = idMatch?.groupValues?.get(1)?.toLongOrNull()
            ?: throw ErrorLoadingException("Invalid WaveFlix media ID from URL: $url")

        // Fetch page HTML to retrieve canonical title, synopsis, and poster
        val doc = app.get(url, headers = commonHeaders).document
        val rawTitle = doc.selectFirst("meta[property=og:image:alt]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()
            ?: "Media #$tmdbId"

        val cleanTitle = rawTitle.removePrefix("Watch ").replace(Regex("""\s*\(\d{4}\).*"""), "").trim()
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() && !it.endsWith("/w500") && !it.endsWith("/w500/") }

        val yearMatch = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        // Resolve canonical IMDb ID via Cinemeta catalog search for 100% exact scraper matching (CSX methodology)
        val imdbId = runCatching {
            val qType = if (isMovie) "movie" else "series"
            val cUrl = "https://v3-cinemeta.strem.io/catalog/$qType/top/search=${URLEncoder.encode(cleanTitle, "UTF-8")}.json"
            val cRes = app.get(cUrl, timeout = 4).text
            val cData = tryParseJson<CinemetaSearchResponse>(cRes)
            cData?.metas?.firstOrNull()?.id?.takeIf { it.startsWith("tt") }
        }.getOrNull()

        if (isMovie) {
            val linkData = WaveLinkData(
                tmdbId = tmdbId,
                isMovie = true,
                title = cleanTitle,
                year = year,
                imdbId = imdbId
            )
            return newMovieLoadResponse(cleanTitle, url, TvType.Movie, linkData.toJson()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                if (!imdbId.isNullOrBlank()) addImdbId(imdbId)
            }
        } else {
            val episodes = mutableListOf<Episode>()

            fun addSeasonEpisodes(seasonNum: Int, epList: List<WaveEpisodeItem>) {
                epList.forEachIndexed { idx, ep ->
                    val epNum = ep.episodeNumber ?: (idx + 1)
                    val epData = WaveLinkData(
                        tmdbId = tmdbId,
                        isMovie = false,
                        season = seasonNum,
                        episode = epNum,
                        title = cleanTitle,
                        year = year,
                        imdbId = imdbId
                    )

                    val epPoster = if (!ep.stillPath.isNullOrBlank()) {
                        "https://image.tmdb.org/t/p/w300${ep.stillPath}"
                    } else poster

                    episodes.add(
                        newEpisode(epData.toJson()) {
                            this.name = ep.name ?: "Episode $epNum"
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = epPoster
                            this.description = ep.overview
                        }
                    )
                }
            }

            // 1. Fetch Season 1 first
            val s1Res = try {
                app.get("$mainUrl/api/season?tvId=$tmdbId&season=1", headers = commonHeaders, timeout = 7)
                    .parsedSafe<WaveSeasonResponse>()
            } catch (_: Throwable) {
                null
            }

            val s1List = s1Res?.episodes
            if (!s1List.isNullOrEmpty()) {
                addSeasonEpisodes(1, s1List)

                // 2. Fetch subsequent seasons in concurrent batches of 4
                coroutineScope {
                    var nextBatchStart = 2
                    var hasMore = true
                    while (hasMore && nextBatchStart <= 25) {
                        val batchRange = nextBatchStart until (nextBatchStart + 4)
                        val seasonJobs = batchRange.map { sNum ->
                            async {
                                val sRes = try {
                                    app.get("$mainUrl/api/season?tvId=$tmdbId&season=$sNum", headers = commonHeaders, timeout = 7)
                                        .parsedSafe<WaveSeasonResponse>()
                                } catch (_: Throwable) {
                                    null
                                }
                                sNum to (sRes?.episodes ?: emptyList())
                            }
                        }
                        val results = seasonJobs.awaitAll().sortedBy { it.first }
                        for ((sNum, eps) in results) {
                            if (eps.isNotEmpty()) {
                                addSeasonEpisodes(sNum, eps)
                            } else {
                                hasMore = false
                                break
                            }
                        }
                        nextBatchStart += 4
                    }
                }
            }

            return newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                if (!imdbId.isNullOrBlank()) addImdbId(imdbId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        // Self-Healing Link Interceptor: filters unplayable containers (3003) & dead streams before ExoPlayer ever sees them
        val seenUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val seenFileKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val seenLinkKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        val selfHealingCallback: (ExtractorLink) -> Unit = { link ->
            val rawUrl = link.url.trim()
            val lower = rawUrl.lowercase()

            // 1. Invariant: Discard non-streamable archives (PKZip, rar, 7z, tar, iso)
            // ExoPlayer UnrecognizedInputFormatException (3003) is 100% triggered by .zip archives
            val isArchive = lower.endsWith(".zip") || lower.contains(".zip?") ||
                            lower.contains(".mkv.zip") || lower.contains(".mp4.zip") ||
                            lower.endsWith(".rar") || lower.contains(".rar?") ||
                            lower.endsWith(".7z") || lower.endsWith(".tar") ||
                            lower.endsWith(".iso") || lower.endsWith(".exe")

            // 2. Invariant: Discard ad-gate landing pages returning text/html
            val isLandingPage = (lower.contains("pixel.hubcloud") && !lower.contains("link=")) ||
                                lower.contains("gamerxyt.com/hubcloud.php") ||
                                lower.contains("hubcloud.ist/drive/") ||
                                lower.contains("tinyurl.com")

            // 3. Deduplication: Prevent flooding the player queue with identical dead links & duplicate worker mirrors
            // Strip dynamic signed query parameters (AWS S3 CloudFront Policy/Signature/Key-Pair-Id, token timestamps)
            val cleanUrlKey = rawUrl.substringBefore("&Policy=")
                .substringBefore("&Signature=")
                .substringBefore("?token=")
                .substringBefore("&token=")
            val isExactDuplicate = !seenUrls.add(cleanUrlKey)

            // Check media file duplication across mirrors (e.g. 4 workers serving the exact same MKV file)
            val fileName = rawUrl.substringAfterLast("/").substringBefore("?").lowercase()
            val isMediaFile = fileName.endsWith(".mkv") || fileName.endsWith(".mp4")
            val fileKey = if (isMediaFile && fileName.length > 10) fileName else null
            val isFileDuplicate = fileKey != null && !seenFileKeys.add(fileKey)

            // Prevent duplicate links with the exact same display name and source from cluttering the selector
            val linkIdentity = "${link.source}::${link.name}"
            val isNameDuplicate = !seenLinkKeys.add(linkIdentity)

            val isZipInName = link.name.contains(".zip", ignoreCase = true) || link.name.contains(".rar", ignoreCase = true)

            if (!isArchive && !isZipInName && !isLandingPage && !isExactDuplicate && !isFileDuplicate && !isNameDuplicate && rawUrl.startsWith("http")) {
                synchronized(callback) {
                    callback(link)
                }
            }
        }

        val linkData = parseJson<WaveLinkData>(data)
        val tmdbId = linkData.tmdbId
        val isMovie = linkData.isMovie
        val season = linkData.season ?: 1
        val episode = linkData.episode ?: 1

        val nxshaReferer = if (isMovie) {
            "https://nxsha.space/embed/movie/$tmdbId"
        } else {
            "https://nxsha.space/embed/tv/$tmdbId/$season/$episode"
        }

        val browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val nxshaHeaders = mapOf(
            "User-Agent" to browserUserAgent,
            "Referer" to nxshaReferer
        )

        // 1. Fetch Multi-Language Subtitles from Nxsha Engine & Stremio OpenSubtitles v3 Engine
        val seenSubUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val subtitleJob = async {
            // A. Nxsha Subtitles Engine (Proxied & Re-encoded to UTF-8 CDN)
            val nxshaSubJob = async {
                try {
                    val subPayload = """{"tmdbId":"$tmdbId","type":"${if (isMovie) "movie" else "tv"}","season":$season,"episode":$episode}"""
                    val encSubQuery = NxshaCrypto.encrypt(subPayload)
                    val subUrl = "https://nxsha.space/api/subtitles?q=${URLEncoder.encode(encSubQuery, "UTF-8")}"

                    val subRes = app.get(subUrl, headers = nxshaHeaders, timeout = 7).text
                    val subHash = parseJson<NxshaHashResponse>(subRes).hash
                    if (!subHash.isNullOrBlank()) {
                        val decryptedSubJson = NxshaCrypto.decrypt(subHash)
                        if (!decryptedSubJson.isNullOrBlank()) {
                            val subObj = parseJson<NxshaSubtitlesResponse>(decryptedSubJson)
                            subObj.subtitles?.forEach { sub ->
                                val trackUrl = sub.url ?: sub.uri ?: return@forEach
                                val trimmedUrl = trackUrl.trim()
                                val fileIdMatch = Regex("""/file/(\d+)""").find(trimmedUrl)?.groupValues?.get(1)
                                val cleanUrl = if (!fileIdMatch.isNullOrBlank()) {
                                    "https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/$fileIdMatch#.srt"
                                } else if (!trimmedUrl.contains(".srt") && !trimmedUrl.contains(".vtt")) {
                                    "$trimmedUrl#.srt"
                                } else {
                                    trimmedUrl
                                }
                                if (seenSubUrls.add(cleanUrl)) {
                                    val langLabel = sub.title ?: sub.label ?: sub.language ?: sub.srclang ?: "English"
                                    subtitleCallback(
                                        newSubtitleFile(
                                            lang = langLabel,
                                            url = cleanUrl
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }

            // B. Stremio OpenSubtitles v3 Engine (IMDb Anchored, UTF-8 CDN, 50+ languages)
            val targetImdb = linkData.imdbId
            val stremioSubJob = if (!targetImdb.isNullOrBlank()) {
                async {
                    try {
                        val subUrl = if (isMovie) {
                            "https://opensubtitles-v3.strem.io/subtitles/movie/$targetImdb.json"
                        } else {
                            "https://opensubtitles-v3.strem.io/subtitles/series/$targetImdb:$season:$episode.json"
                        }
                        val res = app.get(subUrl, timeout = 6).text
                        val subResponse = parseJson<StremioSubtitleResponse>(res)
                        subResponse.subtitles?.forEach { sub ->
                            val sUrl = sub.url?.trim() ?: return@forEach
                            val fileIdMatch = Regex("""/file/(\d+)""").find(sUrl)?.groupValues?.get(1)
                            val cdnUrl = if (!fileIdMatch.isNullOrBlank()) {
                                "https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/$fileIdMatch"
                            } else {
                                sUrl
                            }
                            val cleanUrl = if (!cdnUrl.contains(".srt") && !cdnUrl.contains(".vtt")) "$cdnUrl#.srt" else cdnUrl
                            if (seenSubUrls.add(cleanUrl)) {
                                val cleanLang = mapSubtitleLanguage(sub.lang)
                                subtitleCallback(
                                    newSubtitleFile(
                                        lang = cleanLang,
                                        url = cleanUrl
                                    )
                                )
                            }
                        }
                    } catch (_: Throwable) {}
                }
            } else null

            nxshaSubJob.await()
            stremioSubJob?.await()
        }

        // 2. Query Nxsha Multi-Server Playback Engine
        val nxshaJob = async {
            try {
                val serverPayload = """{"tmdbId":"$tmdbId","imdb_id":"${linkData.imdbId ?: ""}","type":"${if (isMovie) "movie" else "tv"}","season":$season,"episode":$episode}"""
                val encServerQuery = NxshaCrypto.encrypt(serverPayload)
                val serverUrl = "https://nxsha.space/api/servers?q=${URLEncoder.encode(encServerQuery, "UTF-8")}"

                val serverRes = app.get(serverUrl, headers = nxshaHeaders, timeout = 10).text
                val serverHash = parseJson<NxshaHashResponse>(serverRes).hash

                if (!serverHash.isNullOrBlank()) {
                    val decryptedServerJson = NxshaCrypto.decrypt(serverHash)
                    if (!decryptedServerJson.isNullOrBlank()) {
                        val serverList = parseJson<NxshaServerList>(decryptedServerJson)
                        // Query all 33 available servers concurrently (including 4K UHD & direct cloud scrapers)
                        val activeServers = serverList.servers?.filter {
                            !it.scraper.isNullOrBlank() && it.status != "offline"
                        } ?: emptyList()

                        val serverJobs = activeServers.map { srv ->
                            async {
                                try {
                                    val sourcePayload = """{"ex_lang":false,"provider":"${srv.scraper}","tmdbId":"$tmdbId","imdb_id":"${linkData.imdbId ?: ""}","type":"${if (isMovie) "movie" else "tv"}","season":$season,"episode":$episode}"""
                                    val encSourceQuery = NxshaCrypto.encrypt(sourcePayload)
                                    val sourceUrl = "https://nxsha.space/api/sources?q=${URLEncoder.encode(encSourceQuery, "UTF-8")}"

                                    val sourceRes = app.get(sourceUrl, headers = nxshaHeaders, timeout = 8).text
                                    val sourceHash = parseJson<NxshaHashResponse>(sourceRes).hash ?: return@async
                                    val decryptedSourceJson = NxshaCrypto.decrypt(sourceHash) ?: return@async
                                    val sourcesObj = parseJson<NxshaSourcesResponse>(decryptedSourceJson)

                                    val serverDisplayName = srv.name ?: srv.scraper ?: "Server"

                                    val streamJobs = sourcesObj.sources?.mapNotNull { src ->
                                        val streamUrl = src.url?.trim()
                                        if (streamUrl.isNullOrBlank()) return@mapNotNull null
                                        val qualityLabel = src.label ?: src.quality ?: "Auto"

                                        // Skip only unusable Google drive download quota warning links
                                        if (streamUrl.contains("video-downloads.googleusercontent.com")) {
                                            return@mapNotNull null
                                        }

                                        val qualityInt = parseQuality(qualityLabel)
                                        val isHls = src.type?.lowercase() == "m3u8" || src.type?.lowercase() == "hls" || streamUrl.contains(".m3u8")
                                        val isMpd = src.type?.lowercase() == "mpd" || streamUrl.contains(".mpd")
                                        val streamReferer = src.headers?.get("Referer") ?: "https://nxsha.space/"

                                        val streamHeaders = mapOf(
                                            "User-Agent" to browserUserAgent,
                                            "Referer" to streamReferer,
                                            "Origin" to "https://nxsha.space"
                                        )

                                        val isExtractorHost = src.isEmbed == true ||
                                            src.type?.lowercase() == "embed" ||
                                            streamUrl.contains("hubcloud") ||
                                            streamUrl.contains("hubdrive") ||
                                            streamUrl.contains("katdrive") ||
                                            streamUrl.contains("pixeldrain") ||
                                            streamUrl.contains("vidbolt") ||
                                            streamUrl.contains("vidrift") ||
                                            streamUrl.contains("filmu") ||
                                            streamUrl.contains("modiplay") ||
                                            streamUrl.contains("hanerix") ||
                                            streamUrl.contains("minochinos")

                                        if (isExtractorHost) {
                                            if (!streamUrl.contains("filepress") && !streamUrl.contains("filebee")) {
                                                async {
                                                    try {
                                                        loadExtractor(streamUrl, streamReferer, subtitleCallback, selfHealingCallback)
                                                    } catch (_: Throwable) {}
                                                }
                                            } else null
                                        } else {
                                            async {
                                                val isPlayable = try {
                                                    val lowerUrl = streamUrl.lowercase()
                                                    if (lowerUrl.contains(".zip") || lowerUrl.contains(".rar") || lowerUrl.contains(".7z")) {
                                                        false
                                                    } else if (streamUrl.contains("hscow.com") || streamUrl.contains("cloudflarestorage.com") || streamUrl.contains("kkphimplayer") || streamUrl.contains("remoteconsultinggroup")) {
                                                        true
                                                    } else {
                                                        val probeHeaders = if (isHls || isMpd) streamHeaders else streamHeaders + ("Range" to "bytes=0-1024")
                                                        val check = app.get(streamUrl, headers = probeHeaders, timeout = 4)
                                                        val cType = (check.headers["Content-Type"] ?: check.headers["content-type"])?.lowercase() ?: ""
                                                        val isNotHtml = !cType.contains("text/html") && !cType.contains("application/zip") && !cType.contains("application/json")
                                                        (check.isSuccessful || check.code in 200..299) && check.code != 400 && check.code != 403 && check.code != 429 && isNotHtml
                                                    }
                                                } catch (_: Throwable) {
                                                    isHls || isMpd || streamUrl.contains(".mp4")
                                                }

                                                if (isPlayable) {
                                                    val linkType = when {
                                                        isMpd -> ExtractorLinkType.DASH
                                                        isHls -> ExtractorLinkType.M3U8
                                                        else -> ExtractorLinkType.VIDEO
                                                    }
                                                    val audioTag = extractAudioTag(streamUrl, qualityLabel, src.label, src.provider, serverDisplayName)
                                                    val displayAudio = if (audioTag.isNotBlank() && !qualityLabel.contains(audioTag)) " $audioTag" else ""
                                                    val link = newExtractorLink(
                                                        source = "WaveFlix [$serverDisplayName]",
                                                        name = "$serverDisplayName - $qualityLabel$displayAudio",
                                                        url = streamUrl,
                                                        type = linkType
                                                    ) {
                                                        this.referer = streamReferer
                                                        this.headers = streamHeaders
                                                        this.quality = qualityInt
                                                    }
                                                    selfHealingCallback(link)
                                                }
                                            }
                                        }
                                    }
                                    streamJobs?.awaitAll()
                                } catch (_: Throwable) {
                                    // Skip individual server failure silently
                                }
                            }
                        }

                        serverJobs.awaitAll()
                    }
                }
            } catch (_: Throwable) {
                // Nxsha backbone error handled
            }
        }

        // 3. Direct NetMirror Stream Provider (Multi-Resolution Direct MP4 + Subtitles & NewTV M3U8 Engine)
        val netMirrorJob = async {
            try {
                val cleanTitle = (linkData.title ?: "").trim()
                if (cleanTitle.isNotBlank()) {
                    val apiBase = NetMirrorResolver.resolveApiBase(app)
                    var userToken = NetMirrorResolver.getUserToken(app, apiBase)

                    // Helper to fetch player and emit links
                    suspend fun tryPlayer(targetId: String, ott: String): Boolean {
                        val currentToken = userToken ?: return false
                        val playerHeaders = NetMirrorResolver.baseHeaders + mapOf(
                            "Usertoken" to currentToken,
                            "Ott" to ott
                        )
                        val playerUrl = "$apiBase/newtv/player.php?id=$targetId&hd=on"
                        var playerRaw: String? = null
                        try {
                            playerRaw = app.get(playerUrl, headers = playerHeaders, timeout = 8).text
                        } catch (_: Throwable) {}

                        // If token expired or blank, refresh token once and retry
                        if (playerRaw.isNullOrBlank() || playerRaw.contains("Invalid User") || playerRaw.contains("error")) {
                            userToken = NetMirrorResolver.getUserToken(app, apiBase, force = true)
                            if (userToken != null) {
                                val retryHeaders = NetMirrorResolver.baseHeaders + mapOf(
                                    "Usertoken" to userToken!!,
                                    "Ott" to ott
                                )
                                try {
                                    playerRaw = app.get(playerUrl, headers = retryHeaders, timeout = 8).text
                                } catch (_: Throwable) {}
                            }
                        }

                        if (!playerRaw.isNullOrBlank()) {
                            // A. Try parsing NewTvPlayerResponse (video_link)
                            val playerObj = tryParseJson<NewTvPlayerResponse>(playerRaw)
                            val videoLink = playerObj?.video_link?.trim()
                            if (!videoLink.isNullOrBlank()) {
                                val streamReferer = playerObj.referer?.trim() ?: "https://net52.cc"
                                val streamHeaders = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0",
                                    "Referer" to streamReferer
                                )
                                val link = newExtractorLink(
                                    source = "WaveFlix [NetMirror]",
                                    name = "NetMirror - 1080p [Fast CDN ⚡]",
                                    url = videoLink,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = streamReferer
                                    this.headers = streamHeaders
                                    this.quality = Qualities.P1080.value
                                }
                                selfHealingCallback(link)
                                return true
                            }

                            // B. Try parsing PlayList array (multi-res MP4 + Tracks)
                            val playList = tryParseJson<List<NewTvPlayListItem>>(playerRaw)
                            var foundPlayListLink = false
                            playList?.forEach { item ->
                                item.sources?.forEach { s ->
                                    val sFile = s.file?.trim()
                                    if (!sFile.isNullOrBlank()) {
                                        foundPlayListLink = true
                                        val resLabel = s.label ?: "1080p"
                                        val isM3u8 = sFile.contains(".m3u8")
                                        val link = newExtractorLink(
                                            source = "WaveFlix [NetMirror]",
                                            name = "NetMirror - $resLabel [Fast CDN ⚡]",
                                            url = sFile,
                                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = "https://net52.cc"
                                            this.headers = NetMirrorResolver.baseHeaders
                                            this.quality = parseQuality(resLabel)
                                        }
                                        selfHealingCallback(link)
                                    }
                                }
                                item.tracks?.forEach { t ->
                                    val tFile = t.file?.trim()
                                    if (!tFile.isNullOrBlank()) {
                                        val cleanTUrl = if (!tFile.contains(".srt") && !tFile.contains(".vtt")) "$tFile#.srt" else tFile
                                        if (seenSubUrls.add(cleanTUrl)) {
                                            subtitleCallback(
                                                newSubtitleFile(
                                                    lang = t.label ?: "English",
                                                    url = cleanTUrl
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            if (foundPlayListLink) return true
                        }
                        return false
                    }

                    // Search across NetMirror Prime (pv) & OTT endpoints
                    val ottEngines = listOf("pv")
                    for (ott in ottEngines) {
                        try {
                            val searchUrl = "https://net52.cc/mobile/$ott/search.php?s=${URLEncoder.encode(cleanTitle, "UTF-8")}"
                            val searchRes = app.get(searchUrl, headers = NetMirrorResolver.baseHeaders, timeout = 6).text
                            val searchData = tryParseJson<NetMirrorSearchResponse>(searchRes)
                            val items = searchData?.searchResult ?: emptyList()

                            // Match title
                            val match = items.firstOrNull { item ->
                                val itT = item.t?.trim() ?: ""
                                val itR = item.r?.trim() ?: ""
                                val titleMatches = itT.equals(cleanTitle, ignoreCase = true) ||
                                        itT.contains(cleanTitle, ignoreCase = true) ||
                                        cleanTitle.contains(itT, ignoreCase = true)
                                if (!titleMatches) return@firstOrNull false
                                if (isMovie) {
                                    !itR.contains("Series", ignoreCase = true)
                                } else {
                                    itR.contains("Series", ignoreCase = true)
                                }
                            } ?: items.firstOrNull { item ->
                                val itT = item.t?.trim() ?: ""
                                itT.equals(cleanTitle, ignoreCase = true)
                            }

                            if (match?.id != null) {
                                if (tryPlayer(match.id, ott)) break
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // Fallback: Legacy NetMirror embed-tmdb endpoint on active mirror
                if (tmdbId != null) {
                    val mirrorHosts = listOf("https://net52.cc", "https://net22.cc", "https://net77.cc")
                    for (host in mirrorHosts) {
                        try {
                            val netMirrorUrl = if (isMovie) {
                                "$host/api/embed-tmdb/$tmdbId"
                            } else {
                                "$host/api/embed-tmdb/$tmdbId?type=tv&se=$season&ep=$episode"
                            }
                            val netHeaders = mapOf(
                                "User-Agent" to browserUserAgent,
                                "Referer" to "$host/"
                            )
                            val netRes = app.get(netMirrorUrl, headers = netHeaders, timeout = 4).text
                            val netObj = tryParseJson<NetMirrorResponse>(netRes)
                            if (netObj?.ok == true && !netObj.streams.isNullOrEmpty()) {
                                netObj.streams.forEach { s ->
                                    val streamUrl = s.url?.trim()
                                    if (!streamUrl.isNullOrBlank()) {
                                        val resLabel = s.resolution?.toString() ?: "720"
                                        val streamHeaders = mapOf(
                                            "User-Agent" to browserUserAgent,
                                            "Referer" to "https://videodownloader.site/"
                                        )
                                        val link = newExtractorLink(
                                            source = "WaveFlix [NetMirror]",
                                            name = "NetMirror - ${resLabel}p [Fast CDN ⚡]",
                                            url = streamUrl,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = "https://videodownloader.site/"
                                            this.headers = streamHeaders
                                            this.quality = parseQuality("${resLabel}p")
                                        }
                                        selfHealingCallback(link)
                                    }
                                }
                                netObj.captions?.forEach { cap ->
                                    val capUrl = cap.url?.trim()
                                    if (!capUrl.isNullOrBlank()) {
                                        val cleanCapUrl = if (!capUrl.contains(".srt") && !capUrl.contains(".vtt")) "$capUrl#.srt" else capUrl
                                        if (seenSubUrls.add(cleanCapUrl)) {
                                            subtitleCallback(
                                                newSubtitleFile(
                                                    lang = cap.name ?: cap.lang ?: "English",
                                                    url = cleanCapUrl
                                                )
                                            )
                                        }
                                    }
                                }
                                break
                            }
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        // 4. Direct Vidlink Pro Stream Provider
        val vidlinkJob = async {
            try {
                val encUrl = "https://enc-dec.app/api/enc-vidlink?text=$tmdbId"
                val encRes = app.get(encUrl, headers = mapOf("User-Agent" to browserUserAgent), timeout = 6).text
                val encHash = parseJson<VidlinkEncResponse>(encRes).result
                if (!encHash.isNullOrBlank()) {
                    val vidUrl = if (isMovie) {
                        "https://vidlink.pro/api/b/movie/$encHash?multiLang=1"
                    } else {
                        "https://vidlink.pro/api/b/tv/$encHash/$season/$episode?multiLang=1"
                    }
                    val vidHeaders = mapOf(
                        "User-Agent" to browserUserAgent,
                        "Referer" to "https://vidlink.pro/"
                    )
                    val vidRes = app.get(vidUrl, headers = vidHeaders, timeout = 7).text
                    val vidObj = parseJson<VidlinkResponse>(vidRes)
                    vidObj.stream?.qualities?.forEach { (qKey, entry) ->
                        val streamUrl = entry.url?.trim()
                        if (!streamUrl.isNullOrBlank()) {
                            val streamHeaders = mapOf(
                                "User-Agent" to browserUserAgent,
                                "Referer" to "https://vidlink.pro/"
                            )
                            val link = newExtractorLink(
                                source = "WaveFlix [Vidlink]",
                                name = "Vidlink - ${qKey}p [Direct Stream ⚡ Multi-Audio]",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://vidlink.pro/"
                                this.headers = streamHeaders
                                this.quality = parseQuality("${qKey}p")
                            }
                            selfHealingCallback(link)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        val resolvedTitle = linkData.title
        val imdbId = linkData.imdbId
        val (coreTitle, coreTokens) = extractSearchTokens(resolvedTitle)
        val searchTitle = if (coreTitle.isNotBlank()) coreTitle else (resolvedTitle ?: "")

        // 5. Direct AllMovieLand Multi-Audio HLS & Krico Integration (Movies ONLY)
        val allMovieLandJob = async {
            if (isMovie && searchTitle.isNotBlank()) {
                try {
                    queryAllMovieLand(searchTitle, coreTokens, linkData.year, isMovie, season, episode, subtitleCallback, selfHealingCallback)
                } catch (_: Throwable) {}
            }
        }

        // 6. Direct RogMovies / VegaMovies Indian Cloud Integration (CSX IMDb Anchored)
        val rogMoviesJob = async {
            if (searchTitle.isNotBlank() || !imdbId.isNullOrBlank()) {
                try {
                    queryRogMovies(searchTitle, coreTokens, linkData.year, isMovie, season, episode, imdbId, subtitleCallback, selfHealingCallback)
                } catch (_: Throwable) {}
            }
        }

        // 7. Direct MultiMovies Integration (Cineverse / Filmu / Nexus / VidBolt / Vidrift)
        val multiMoviesJob = async {
            try {
                queryMultiMovies(tmdbId.toString(), imdbId, isMovie, season, episode, subtitleCallback, selfHealingCallback)
            } catch (_: Throwable) {}
        }

        // 8. Direct Bollyflix Indian Cloud Integration (CSX IMDb Anchored)
        val bollyflixJob = async {
            if (searchTitle.isNotBlank() || !imdbId.isNullOrBlank()) {
                try {
                    queryBollyflix(searchTitle, coreTokens, linkData.year, isMovie, season, episode, imdbId, subtitleCallback, selfHealingCallback)
                } catch (_: Throwable) {}
            }
        }

        // 9. KatMovieHD – Direct Indian cloud search via new.katmoviehd.top + links.kmhd.me
        val katMovieJob = async {
            if (searchTitle.isNotBlank()) {
                try {
                    queryKatMovieHD(searchTitle, linkData.year, isMovie, season, episode, subtitleCallback, selfHealingCallback)
                } catch (_: Throwable) {}
            }
        }

        // 10. HDHub4u – Direct Indian cloud search via Pingora Typesense + greenmotors
        val hdhub4uJob = async {
            if (searchTitle.isNotBlank() || !imdbId.isNullOrBlank()) {
                try {
                    queryHDHub4u(searchTitle, coreTokens, linkData.year, isMovie, season, episode, imdbId, subtitleCallback, selfHealingCallback)
                } catch (_: Throwable) {}
            }
        }

        // 11. MoviesDrive – Direct Indian Cloud & HubCloud Integration (CSX MoviesDrive Scraper)
        val moviesDriveJob = async {
            if (searchTitle.isNotBlank() || !imdbId.isNullOrBlank()) {
                try {
                    queryMoviesDrive(searchTitle, linkData.year, isMovie, season, episode, imdbId, subtitleCallback, selfHealingCallback)
                } catch (_: Throwable) {}
            }
        }

        // 12. Moviesmod – Direct Indian Cloud & HubCloud/FastDL Integration (CSX Scraper)
        val moviesModJob = async {
            if (searchTitle.isNotBlank() || !imdbId.isNullOrBlank()) {
                try {
                    queryMoviesmod(searchTitle, linkData.year, isMovie, season, episode, imdbId, subtitleCallback, selfHealingCallback)
                } catch (_: Throwable) {}
            }
        }

        // 13. UHDMovies – Direct 4K UHD & DriveLeech/HubCloud Integration (CSX Scraper)
        val uhdMoviesJob = async {
            if (searchTitle.isNotBlank()) {
                try {
                    queryUHDMovies(searchTitle, linkData.year, isMovie, season, episode, imdbId, subtitleCallback, selfHealingCallback)
                } catch (_: Throwable) {}
            }
        }

        // 14. 4K-HDHub – Direct 4K/1080p Multi/Dual-Audio & Single EP's Integration
        val fourKhubJob = async {
            if (searchTitle.isNotBlank()) {
                try {
                    query4kHdHub(searchTitle, linkData.year, isMovie, season, episode, subtitleCallback, selfHealingCallback)
                } catch (_: Throwable) {}
            }
        }

        nxshaJob.await()
        netMirrorJob.await()
        vidlinkJob.await()
        allMovieLandJob.await()
        rogMoviesJob.await()
        multiMoviesJob.await()
        bollyflixJob.await()
        katMovieJob.await()
        hdhub4uJob.await()
        moviesDriveJob.await()
        moviesModJob.await()
        uhdMoviesJob.await()
        fourKhubJob.await()
        subtitleJob.await()
        true
    }

    private suspend fun queryMultiMovies(
        tmdbId: String,
        imdbId: String?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val vidboltTarget = if (isMovie) "https://vidbolt.xyz/movie/$tmdbId" else "https://vidbolt.xyz/tv/$tmdbId/$season/$episode"
        val vidriftTarget = if (isMovie) "https://embed.vidrift.net/embed/movie/$tmdbId" else "https://embed.vidrift.net/embed/tv/$tmdbId/$season/$episode"
        val filmuTarget = if (isMovie) "https://embed.filmu.in/movie/$tmdbId" else "https://embed.filmu.in/tv/$tmdbId/$season/$episode"
        val modiplayTarget = if (isMovie) "https://rozgarlelo.modiplay.xyz/embed/tmdb/movie?id=$tmdbId" else "https://rozgarlelo.modiplay.xyz/embed/tmdb/tv?id=$tmdbId&s=$season&e=$episode"
        val nexusTarget = if (isMovie) "https://www.moviesnexus.fun/movie/$tmdbId?sv=multi-audio" else "https://www.moviesnexus.fun/tv/$tmdbId/$season/$episode"

        val targets = mutableListOf(vidboltTarget, vidriftTarget, filmuTarget, modiplayTarget, nexusTarget)
        if (!imdbId.isNullOrBlank()) {
            targets.add(if (isMovie) "https://streams.iqsmartgames.com/embed/movie/$imdbId?key=e11a7debaaa4f5d25b671706ffe4d2acb56efbd4" else "https://streams.iqsmartgames.com/embed/tv/$imdbId/$season/$episode?key=e11a7debaaa4f5d25b671706ffe4d2acb56efbd4")
        }

        val jobs = targets.map { sUrl ->
            async {
                try {
                    loadExtractor(sUrl, "https://m1.multimovies.courses/", subtitleCallback, callback)
                } catch (_: Throwable) {}
            }
        }
        jobs.forEach { it.await() }
    }

    private suspend fun queryKatMovieHD(
        query: String,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val base = DomainResolver.get("katmovie")
        val browserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val katHeaders = mapOf(
            "User-Agent" to browserUA,
            "Referer" to "$base/"
        )

        val cleanTitleKebab = query.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val candidateSlugs = mutableListOf<String>()

        // 1. Direct High-Probability Slug Guesses
        if (isMovie) {
            if (year != null) {
                candidateSlugs.add("$cleanTitleKebab-$year-hindi")
                candidateSlugs.add("$cleanTitleKebab-$year")
            }
            candidateSlugs.add("$cleanTitleKebab-hindi")
            candidateSlugs.add(cleanTitleKebab)
        } else {
            candidateSlugs.add("$cleanTitleKebab-s$season-hindi")
            candidateSlugs.add("$cleanTitleKebab-s0$season-hindi")
            candidateSlugs.add("$cleanTitleKebab-season-$season-hindi")
            candidateSlugs.add("$cleanTitleKebab-hindi")
        }

        // 2. Scan KatMovieHD recent posts & catalog SSR pages for matching title tokens
        val tokens = cleanTitleKebab.split("-").filter { it.length > 2 }
        if (tokens.isNotEmpty()) {
            val pagesToScan = listOf("$base/", "$base/?page=2")
            pagesToScan.forEach { pageUrl ->
                val pageHtml = runCatching {
                    app.get(pageUrl, headers = katHeaders, timeout = 7).text
                }.getOrNull()
                if (!pageHtml.isNullOrBlank()) {
                    Regex("""slug:"([a-z0-9\-]+)"""").findAll(pageHtml).forEach { m ->
                        val slug = m.groupValues[1]
                        val matchesTokens = if (tokens.size >= 2) {
                            tokens.count { slug.contains(it) } >= 2
                        } else {
                            tokens.any { slug.contains(it) }
                        }
                        if (matchesTokens) {
                            if (!isMovie) {
                                val sStr = season.toString()
                                val sPad = String.format("%02d", season)
                                if (slug.contains("s$sPad") || slug.contains("s$sStr") || slug.contains("season-$sStr") || (season == 1 && !slug.contains("season-2") && !slug.contains("s02"))) {
                                    candidateSlugs.add(slug)
                                }
                            } else {
                                candidateSlugs.add(slug)
                            }
                        }
                    }
                }
            }
        }

        val uniqueSlugs = candidateSlugs.distinct().take(4)
        if (uniqueSlugs.isEmpty()) return@coroutineScope

        // 3. For each candidate slug, fetch post HTML & resolve kmhd.me play/file/pack links
        val slugJobs = uniqueSlugs.map { slug ->
            async {
                try {
                    val postUrl = "$base/$slug/"
                    val postHtml = runCatching {
                        app.get(postUrl, headers = katHeaders, timeout = 8).text
                    }.getOrNull() ?: return@async

                    // Extract all kmhd.me play links, file links, and pack links
                    val playIds = Regex("""links\.kmhd\.me/play\?id=([a-zA-Z0-9_\-]+)""").findAll(postHtml)
                        .map { it.groupValues[1] }.distinct().toList()
                    val fileIds = Regex("""links\.kmhd\.me/file/([a-zA-Z0-9_\-]+)""").findAll(postHtml)
                        .map { it.groupValues[1] }.distinct().toList()
                    val packIds = Regex("""links\.kmhd\.me/pack/([a-zA-Z0-9_\-]+)""").findAll(postHtml)
                        .map { it.groupValues[1] }.distinct().toList()

                    // Resolve TV pack pages first if it's a TV show
                    if (!isMovie && packIds.isNotEmpty()) {
                        packIds.take(3).forEach { packId ->
                            try {
                                resolveKmhdPack(packId, season, episode, katHeaders, callback)
                            } catch (_: Throwable) {}
                        }
                    }

                    // Resolve play?id pages
                    val playJobs = playIds.take(4).map { packId ->
                        async {
                            try {
                                resolveKmhdPlay(packId, isMovie, season, episode, katHeaders, callback)
                            } catch (_: Throwable) {}
                        }
                    }

                    // Resolve individual file links directly (Movies only to prevent episode mismatch)
                    val fileJobs = if (isMovie) {
                        fileIds.take(6).map { fileId ->
                            async {
                                try {
                                    resolveKmhdFile(fileId, katHeaders, callback)
                                } catch (_: Throwable) {}
                            }
                        }
                    } else emptyList()

                    (playJobs + fileJobs).forEach { it.await() }
                } catch (_: Throwable) {}
            }
        }
        slugJobs.forEach { it.await() }
    }

    /**
     * Resolves a links.kmhd.me/pack/{packId} TV pack page.
     * Maps episodes to file IDs and queries touchme API for matching episode.
     */
    private suspend fun resolveKmhdPack(
        packId: String,
        season: Int,
        episode: Int,
        katHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val packUrl = "https://links.kmhd.me/pack/$packId"
        val packHtml = runCatching {
            app.get(packUrl, headers = katHeaders, timeout = 8).text
        }.getOrNull() ?: return

        // Pattern matching for target episode e.g. S03E01, 3x01, or Episode 01
        val epPattern = Regex("""(?:S0?${season}E0?${episode}|${season}x0?${episode}|E0?${episode}\b|Episode\s*0?${episode}\b)""", RegexOption.IGNORE_CASE)

        // Parse info object: info:{ FileId: { name: "..." }, ... }
        val infoMatches = Regex("""([a-zA-Z0-9_\-]+)\s*:\s*\{\s*name\s*:\s*"([^"]+)"\s*\}""").findAll(packHtml)
        var matchedFileId: String? = null
        for (m in infoMatches) {
            val fileId = m.groupValues[1]
            val fileName = m.groupValues[2]
            if (epPattern.containsMatchIn(fileName)) {
                matchedFileId = fileId
                break
            }
        }

        if (matchedFileId != null) {
            resolveKmhdFile(matchedFileId, katHeaders, callback)
        }
    }

    /**
     * Resolves a links.kmhd.me/play?id={playId} page.
     */
    private suspend fun resolveKmhdPlay(
        playId: String,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        katHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val playUrl = "https://links.kmhd.me/play?id=$playId"
        val playHtml = runCatching {
            app.get(playUrl, headers = katHeaders, timeout = 8).text
        }.getOrNull() ?: return

        // Direct Streamtape embed resolution from inline script
        Regex("""streamtape_res\s*:\s*"([a-zA-Z0-9_\-]+)"""").findAll(playHtml).forEach { m ->
            val tapeId = m.groupValues[1]
            if (tapeId != "None" && tapeId.isNotBlank()) {
                runCatching {
                    loadExtractor("https://streamtape.com/e/$tapeId", playUrl, { _ -> }, callback)
                }
            }
        }

        // Direct Streamwish embed resolution from inline script
        Regex("""streamwish_res\s*:\s*"([a-zA-Z0-9_\-]+)"""").findAll(playHtml).forEach { m ->
            val wishId = m.groupValues[1]
            if (wishId != "None" && wishId.isNotBlank()) {
                runCatching {
                    loadExtractor("https://hglink.to/e/$wishId", playUrl, { _ -> }, callback)
                }
            }
        }

        // Extract FileIds embedded in info block
        val fileIds = Regex("""([a-zA-Z0-9_\-]+)\s*:\s*\{[^}]*name""").findAll(playHtml)
            .map { it.groupValues[1] }
            .filter { it != "info" && it != "data" && it != "page_ads" }
            .distinct()
            .toList()

        fileIds.take(4).forEach { fileId ->
            resolveKmhdFile(fileId, katHeaders, callback)
        }
    }

    /**
     * Resolves a links.kmhd.me fileId via the touchme API.
     * Hubdrive (Hubcloud R2/PixelServer), Streamtape, Streamwish, and Katdrive.
     */
    private suspend fun resolveKmhdFile(
        fileId: String,
        katHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val resolvers = listOf(
            "hubdrive_res"   to "https://hubcloud.ist/drive/",
            "streamtape_res" to "https://streamtape.com/e/",
            "streamwish_res" to "https://hglink.to/e/",
            "katdrive_res"   to "https://katdrive.click/file/"
        )

        resolvers.forEach { (resolver, baseUrl) ->
            try {
                val touchmeUrl = "https://links.kmhd.me/api/touchme/$fileId?c=$resolver"
                val resp = app.post(
                    touchmeUrl,
                    headers = katHeaders + mapOf("Content-Type" to "application/json", "X-Requested-With" to "XMLHttpRequest"),
                    timeout = 7
                ).text

                if (Regex(""""status"\s*:\s*"Done"""").containsMatchIn(resp)) {
                    val linkId = Regex(""""linkId"\s*:\s*"([^"]+)"""").find(resp)?.groupValues?.get(1)
                    if (!linkId.isNullOrBlank() && linkId != "None") {
                        val fullUrl = if (linkId.startsWith("http")) linkId else baseUrl + linkId
                        runCatching {
                            loadExtractor(fullUrl, "https://links.kmhd.me/", { _ -> }, callback)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun findPrecedingQualityTag(a: org.jsoup.nodes.Element): String? {
        val btnText = a.text().lowercase()
        when {
            btnText.contains("2160p") || btnText.contains("4k") -> return "2160p"
            btnText.contains("1080p") -> return "1080p"
            btnText.contains("720p") -> return "720p"
            btnText.contains("480p") -> return "480p"
        }

        var curr: org.jsoup.nodes.Element? = a
        while (curr != null) {
            var prev = curr.previousElementSibling()
            while (prev != null) {
                val pText = prev.text().lowercase()
                val has2160 = pText.contains("2160p") || pText.contains("4k")
                val has1080 = pText.contains("1080p")
                val has720 = pText.contains("720p")
                val has480 = pText.contains("480p")
                val count = listOf(has2160, has1080, has720, has480).count { it }

                if (count == 1) {
                    return when {
                        has2160 -> "2160p"
                        has1080 -> "1080p"
                        has720 -> "720p"
                        has480 -> "480p"
                        else -> null
                    }
                }
                prev = prev.previousElementSibling()
            }
            val parent = curr.parent()
            if (parent == null || parent.tagName() in listOf("body", "html", "article", "main")) break
            curr = parent
        }
        return null
    }


    private suspend fun query4kHdHub(
        query: String,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val base = DomainResolver.get("fourkhdhub").ifBlank { "https://4khdhub.one" }
        val browserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val fourKHeaders = mapOf(
            "User-Agent" to browserUA,
            "Referer" to "$base/"
        )

        val cleanTitle = query.lowercase().replace(Regex("[^a-z0-9 ]+"), " ").trim()
        val tokens = cleanTitle.split(" ").filter { it.length > 2 }
        if (tokens.isEmpty()) return@coroutineScope

        val searchUrl = "$base/?s=${URLEncoder.encode(cleanTitle, "UTF-8")}"
        val searchRes = runCatching {
            app.get(searchUrl, headers = fourKHeaders, timeout = 8)
        }.getOrNull() ?: return@coroutineScope

        DomainResolver.reportRedirect("fourkhdhub", searchRes.url)
        val searchDoc = Jsoup.parse(searchRes.text)

        val matchingPosts = searchDoc.select("a[href]").mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val isTargetType = if (isMovie) href.contains("-movie-") else href.contains("-series-")
            if (!isTargetType) return@mapNotNull null

            val text = a.text().lowercase()
            val hrefLower = href.lowercase()
            val matchesTokens = tokens.all { hrefLower.contains(it) || text.contains(it) }
            if (matchesTokens) {
                if (href.startsWith("http")) href else "$base${if (href.startsWith("/")) "" else "/"}$href"
            } else null
        }.distinct().take(3)

        if (matchingPosts.isEmpty()) return@coroutineScope

        val postJobs = matchingPosts.map { postUrl ->
            async {
                try {
                    val postRes = runCatching {
                        app.get(postUrl, headers = fourKHeaders, timeout = 10)
                    }.getOrNull() ?: return@async
                    DomainResolver.reportRedirect("fourkhdhub", postRes.url)
                    val postDoc = Jsoup.parse(postRes.text)

                    if (isMovie) {
                        for (a in postDoc.select("a[href]")) {
                            val href = a.attr("href").trim()
                            if (href.contains("greenmotors.") || href.contains("hubdrive.") ||
                                href.contains("hubcloud.") || href.contains("hblinks.") ||
                                href.contains("katdrive.") || href.contains("fastdl.")) {

                                val precedingText = a.parents().firstOrNull { p ->
                                    val t = p.text()
                                    t.contains("2160p", ignoreCase = true) || t.contains("1080p", ignoreCase = true) ||
                                    t.contains("720p", ignoreCase = true) || t.contains("480p", ignoreCase = true)
                                }?.text() ?: a.text()

                                val postTitle = postUrl.substringAfterLast("/").replace("-", " ")
                                val audioTag = extractAudioTag(postTitle, precedingText, a.text())
                                val qualityTag = when {
                                    precedingText.contains("2160p", ignoreCase = true) || precedingText.contains("4k", ignoreCase = true) -> "2160p"
                                    precedingText.contains("1080p", ignoreCase = true) -> "1080p"
                                    precedingText.contains("720p", ignoreCase = true) -> "720p"
                                    precedingText.contains("480p", ignoreCase = true) -> "480p"
                                    else -> null
                                }

                                val tagBuilder = StringBuilder()
                                if (qualityTag != null) tagBuilder.append("#$qualityTag")
                                if (audioTag.isNotBlank()) tagBuilder.append("#$audioTag")
                                val taggedHref = if (tagBuilder.isNotEmpty() && !href.contains("#")) "$href$tagBuilder" else href

                                runCatching {
                                    loadExtractor(taggedHref, postUrl, subtitleCallback, callback)
                                }
                            }
                        }
                    } else {
                        // Series: Specifically target .episode-download-item (the "Single EP's" tab)
                        // Bypasses the "Zip/Pack" tab completely so .zip files are never scraped
                        val padEp = episode.toString().padStart(2, '0')
                        val rawEp = episode.toString()
                        val padS = season.toString().padStart(2, '0')
                        val rawS = season.toString()

                        val epPatterns = listOf(
                            Regex("""\bS0?$rawS[E.]0?$rawEp\b""", RegexOption.IGNORE_CASE),
                            Regex("""\b(?:Episode|EP)[-.\s]*0?$rawEp\b""", RegexOption.IGNORE_CASE)
                        )

                        val episodeItems = postDoc.select(".episode-download-item")
                        for (item in episodeItems) {
                            val titleEl = item.selectFirst(".episode-file-title, .file-title, h4, h5, div")
                            val titleText = titleEl?.text()?.trim() ?: item.text().trim()

                            val matchesEpisode = epPatterns.any { it.containsMatchIn(titleText) }
                            if (matchesEpisode) {
                                val qualityTag = when {
                                    titleText.contains("2160p", ignoreCase = true) || titleText.contains("4k", ignoreCase = true) -> "2160p"
                                    titleText.contains("1080p", ignoreCase = true) -> "1080p"
                                    titleText.contains("720p", ignoreCase = true) -> "720p"
                                    titleText.contains("480p", ignoreCase = true) -> "480p"
                                    else -> null
                                }
                                val audioTag = extractAudioTag(titleText, item.text())

                                val tagBuilder = StringBuilder()
                                if (qualityTag != null) tagBuilder.append("#$qualityTag")
                                if (audioTag.isNotBlank()) tagBuilder.append("#$audioTag")

                                for (a in item.select("a[href]")) {
                                    val href = a.attr("href").trim()
                                    if (href.contains("greenmotors.") || href.contains("hubdrive.") ||
                                        href.contains("hubcloud.") || href.contains("hblinks.") ||
                                        href.contains("katdrive.") || href.contains("fastdl.")) {
                                        val taggedUrl = if (tagBuilder.isNotEmpty() && !href.contains("#")) "$href$tagBuilder" else href
                                        runCatching {
                                            loadExtractor(taggedUrl, postUrl, subtitleCallback, callback)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        postJobs.awaitAll()
    }

    private suspend fun queryHDHub4u(
        query: String,
        coreTokens: List<String>,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        imdbId: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val base = DomainResolver.get("hdhub4u")
        val browserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val hdhubHeaders = mapOf(
            "User-Agent" to browserUA,
            "Referer" to "$base/"
        )

        val cleanQuery = query.replace(Regex("""[^a-zA-Z0-9\s]"""), " ").trim()
        val searchUrl = if (!imdbId.isNullOrBlank()) {
            "https://search.pingora.fyi/collections/post/documents/search?q=$imdbId&query_by=post_title%2Ccategory%2Cstars%2Cdirector%2Cimdb_id&query_by_weights=4%2C2%2C2%2C2%2C4&sort_by=sort_by_date%3Adesc&limit=15&highlight_fields=none&use_cache=true&page=1"
        } else {
            "$base/?s=${cleanQuery.replace(" ", "+")}"
        }

        val searchRes = runCatching {
            app.get(searchUrl, headers = hdhubHeaders, timeout = 8)
        }.getOrNull() ?: return@coroutineScope
        DomainResolver.reportRedirect("hdhub4u", searchRes.url)
        val searchBody = searchRes.text

        val posts = mutableListOf<Pair<String, String>>() // (url, title)

        if (searchUrl.contains("pingora.fyi")) {
            val pingoraObj = runCatching { parseJson<RogSearchResponse>(searchBody) }.getOrNull()
            pingoraObj?.hits?.forEach { hit ->
                val permalink = hit.document?.permalink ?: return@forEach
                val pTitle = hit.document.post_title ?: ""
                val fullUrl = if (permalink.startsWith("http")) permalink else "$base" + (if (permalink.startsWith("/")) permalink else "/$permalink")
                posts.add(fullUrl to pTitle)
            }
        }

        if (posts.isEmpty()) {
            val doc = Jsoup.parse(searchBody)
            doc.select("li.thumb").forEach { thumb ->
                val a = thumb.selectFirst("figure a, figcaption a")
                val href = a?.attr("href")?.trim() ?: return@forEach
                val title = a.attr("aria-label").ifBlank { thumb.selectFirst("figcaption p")?.text() ?: "" }.trim()
                if (href.isNotBlank() && title.isNotBlank()) {
                    val fullUrl = if (href.startsWith("http")) href else "$base" + (if (href.startsWith("/")) href else "/$href")
                    posts.add(fullUrl to title)
                }
            }
        }

        if (posts.isEmpty()) return@coroutineScope

        val queryTokens = if (coreTokens.isNotEmpty()) coreTokens else cleanQuery.lowercase().split(" ").filter { it.length > 2 }
        val significantWords = queryTokens.filter { it.length >= 3 && it.toIntOrNull() == null }
        val primaryWord = significantWords.firstOrNull() ?: cleanQuery.lowercase().split(" ").firstOrNull() ?: cleanQuery.lowercase()

        val matchingPosts = posts.filter { (_, title) ->
            val lower = title.lowercase()

            // 1. Invariant: Post title MUST contain the primary title keyword
            if (primaryWord.isNotBlank() && !lower.contains(primaryWord)) return@filter false

            val tokenMatches = queryTokens.count { lower.contains(it) }
            val minMatches = (queryTokens.size * 0.7).coerceAtLeast(1.0).toInt()
            val matchesTokens = lower.contains(cleanQuery) || (queryTokens.isNotEmpty() && tokenMatches >= minMatches)
            if (!matchesTokens) return@filter false

            if (isMovie) {
                val isTvTitle = lower.contains("season") || lower.contains("all episode") || Regex("""\bs\d{1,2}\b""").containsMatchIn(lower)
                if (isTvTitle) return@filter false
                if (year != null) {
                    val yearMatches = lower.contains(year.toString()) || lower.contains((year - 1).toString()) || lower.contains((year + 1).toString())
                    yearMatches || lower.contains(cleanQuery)
                } else true
            } else {
                val seasonPatterns = listOf(
                    "season $season",
                    "season 0$season",
                    "s0$season",
                    "s$season",
                    "s $season",
                    "season 1-"
                )
                seasonPatterns.any { lower.contains(it) } ||
                    (season == 1 && !lower.contains("season 2") && !lower.contains("s02"))
            }
        }.take(3)

        if (matchingPosts.isEmpty()) return@coroutineScope

        val postJobs = matchingPosts.map { (postUrl, _) ->
            async {
                try {
                    val postHtml = runCatching {
                        app.get(postUrl, headers = hdhubHeaders, timeout = 8).text
                    }.getOrNull() ?: return@async

                    if (isMovie) {
                        val docPost = Jsoup.parse(postHtml)
                        for (a in docPost.select("a[href]")) {
                            val href = a.attr("href").trim()
                            val btnText = a.text().trim()
                            val postTitle = postUrl.substringAfterLast("/").replace("-", " ")
                            val audioTag = extractAudioTag(postTitle, a.text())
                            val qualityTag = findPrecedingQualityTag(a)
                            val tagBuilder = StringBuilder()
                            if (qualityTag != null) tagBuilder.append("#$qualityTag")
                            if (audioTag.isNotBlank()) tagBuilder.append("#$audioTag")
                            val taggedHref = if (tagBuilder.isNotEmpty() && !href.contains("#")) "$href$tagBuilder" else href

                            if (href.contains("greenmotors.") || href.contains("hubdrive.") ||
                                href.contains("hubcloud.") || href.contains("hblinks.") ||
                                href.contains("hubcdn.")) {
                                runCatching {
                                    loadExtractor(taggedHref, postUrl, subtitleCallback, callback)
                                }
                            }
                        }
                    } else {
                        val docPost = Jsoup.parse(postHtml)
                        val epItems = docPost.select(".episode-download-item")
                        if (epItems.isNotEmpty()) {
                            val padEp = episode.toString().padStart(2, '0')
                            val rawEp = episode.toString()
                            val padS = season.toString().padStart(2, '0')
                            val rawS = season.toString()
                            val epPatterns = listOf(
                                Regex("""\bS0?$rawS[E.]0?$rawEp\b""", RegexOption.IGNORE_CASE),
                                Regex("""\b(?:Episode|EP)[-.\s]*0?$rawEp\b""", RegexOption.IGNORE_CASE)
                            )
                            for (item in epItems) {
                                val titleEl = item.selectFirst(".episode-file-title, .file-title, h4, h5, div")
                                val titleText = titleEl?.text()?.trim() ?: item.text().trim()
                                if (epPatterns.any { it.containsMatchIn(titleText) }) {
                                    val qualityTag = when {
                                        titleText.contains("2160p", ignoreCase = true) || titleText.contains("4k", ignoreCase = true) -> "2160p"
                                        titleText.contains("1080p", ignoreCase = true) -> "1080p"
                                        titleText.contains("720p", ignoreCase = true) -> "720p"
                                        titleText.contains("480p", ignoreCase = true) -> "480p"
                                        else -> null
                                    }
                                    val audioTag = extractAudioTag(titleText, item.text())
                                    val tagBuilder = StringBuilder()
                                    if (qualityTag != null) tagBuilder.append("#$qualityTag")
                                    if (audioTag.isNotBlank()) tagBuilder.append("#$audioTag")
                                    for (a in item.select("a[href]")) {
                                        val linkUrl = a.attr("href").trim()
                                        val taggedUrl = if (tagBuilder.isNotEmpty() && !linkUrl.contains("#")) "$linkUrl$tagBuilder" else linkUrl
                                        if (linkUrl.contains("greenmotors.") || linkUrl.contains("hubdrive.") ||
                                            linkUrl.contains("hubcloud.") || linkUrl.contains("hblinks.") ||
                                            linkUrl.contains("hubcdn.")) {
                                            runCatching {
                                                loadExtractor(taggedUrl, postUrl, subtitleCallback, callback)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val padEp = episode.toString().padStart(2, '0')
                        val rawEp = episode.toString()
                        val epRegex = Regex("""(?:E|Episode|EP)[\s.-]*(?:0?$rawEp|$padEp)\b[^<]*?(?:<a[\s\S]*?<\/h[1-6]>|<a[\s\S]*?<\/p>|<a[\s\S]*?<\/div>|$)""", RegexOption.IGNORE_CASE)
                        val epMatches = epRegex.findAll(postHtml).toList()

                        if (epMatches.isNotEmpty()) {
                            val aRegex = Regex("""href=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                            for (m in epMatches) {
                                val matchText = m.value
                                val postTitle = postUrl.substringAfterLast("/").replace("-", " ")
                                val audioTag = extractAudioTag(postTitle, matchText)
                                val qualityTag = when {
                                    matchText.contains("2160p", ignoreCase = true) || matchText.contains("4k", ignoreCase = true) -> "2160p"
                                    matchText.contains("1080p", ignoreCase = true) -> "1080p"
                                    matchText.contains("720p", ignoreCase = true) -> "720p"
                                    matchText.contains("480p", ignoreCase = true) -> "480p"
                                    else -> null
                                }
                                val tagBuilder = StringBuilder()
                                if (qualityTag != null) tagBuilder.append("#$qualityTag")
                                if (audioTag.isNotBlank()) tagBuilder.append("#$audioTag")
                                for (aMatch in aRegex.findAll(m.value)) {
                                    val linkUrl = aMatch.groupValues[1]
                                    val taggedUrl = if (tagBuilder.isNotEmpty() && !linkUrl.contains("#")) "$linkUrl$tagBuilder" else linkUrl
                                    if (linkUrl.contains("greenmotors.") || linkUrl.contains("hubdrive.") ||
                                        linkUrl.contains("hubcloud.") || linkUrl.contains("hblinks.") ||
                                        linkUrl.contains("hubcdn.")) {
                                        runCatching {
                                            loadExtractor(taggedUrl, postUrl, subtitleCallback, callback)
                                        }
                                    }
                                }
                            }
                        } else {
                            val epHrefRegex = Regex("""<a[^>]*href=["'](https?://[^"']+)["'][^>]*>[\s\S]*?(?:E|Episode|EP)[\s.-]*(?:0?$rawEp|$padEp)\b[\s\S]*?<\/a>""", RegexOption.IGNORE_CASE)
                            for (m in epHrefRegex.findAll(postHtml)) {
                                val linkUrl = m.groupValues[1]
                                val fullMatch = m.value
                                val postTitle = postUrl.substringAfterLast("/").replace("-", " ")
                                val audioTag = extractAudioTag(postTitle, fullMatch)
                                val qualityTag = when {
                                    fullMatch.contains("2160p", ignoreCase = true) || fullMatch.contains("4k", ignoreCase = true) -> "2160p"
                                    fullMatch.contains("1080p", ignoreCase = true) -> "1080p"
                                    fullMatch.contains("720p", ignoreCase = true) -> "720p"
                                    fullMatch.contains("480p", ignoreCase = true) -> "480p"
                                    else -> null
                                }
                                val tagBuilder = StringBuilder()
                                if (qualityTag != null) tagBuilder.append("#$qualityTag")
                                if (audioTag.isNotBlank()) tagBuilder.append("#$audioTag")
                                val taggedUrl = if (tagBuilder.isNotEmpty() && !linkUrl.contains("#")) "$linkUrl$tagBuilder" else linkUrl
                                if (linkUrl.contains("greenmotors.") || linkUrl.contains("hubdrive.") ||
                                    linkUrl.contains("hubcloud.") || linkUrl.contains("hblinks.") ||
                                    linkUrl.contains("hubcdn.")) {
                                    runCatching {
                                        loadExtractor(taggedUrl, postUrl, subtitleCallback, callback)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        postJobs.awaitAll()
    }


    private suspend fun queryMoviesDrive(
        query: String,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        imdbId: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = DomainResolver.get("moviesdrive")
        val searchUrl = if (!imdbId.isNullOrBlank()) {
            "$base/search.php?q=$imdbId"
        } else {
            "$base/search.php?q=${URLEncoder.encode(query, "UTF-8")}"
        }
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$base/"
        )
        val jsonResponse = runCatching {
            app.get(searchUrl, headers = headers, timeout = 7)
        }.getOrNull() ?: return
        DomainResolver.reportRedirect("moviesdrive", jsonResponse.url)
        val jsonRes = jsonResponse.text
        val data = runCatching { parseJson<RogSearchResponse>(jsonRes) }.getOrNull() ?: return
        val hits = data.hits ?: return

        for (hit in hits) {
            val doc = hit.document ?: continue
            if (!imdbId.isNullOrBlank() && !doc.imdb_id.isNullOrBlank() && doc.imdb_id != imdbId) continue
            val permalink = doc.permalink ?: continue
            val matchedItem = if (permalink.startsWith("http")) permalink else "$base$permalink"

            val document = runCatching { app.get(matchedItem, headers = headers, timeout = 8).document }.getOrNull() ?: continue
            if (isMovie) {
                document.select("h5 > a, p > a").forEach { a ->
                    val href = a.attr("href")
                    if (href.contains("hubcloud") || href.contains("gdflix") || href.contains("drive")) {
                        runCatching { loadExtractor(href, matchedItem, subtitleCallback, callback) }
                    } else if (href.startsWith("http") && !href.contains("trailer")) {
                        val intermediateDoc = runCatching { app.get(href, headers = headers, timeout = 7).document }.getOrNull()
                        intermediateDoc?.select("a[href*='hubcloud'], a[href*='gdflix'], a[href*='gdlink']")?.forEach { subA ->
                            runCatching { loadExtractor(subA.attr("href"), href, subtitleCallback, callback) }
                        }
                    }
                }
            } else {
                val sPad = String.format("%02d", season)
                val ePad = String.format("%02d", episode)
                val stag = "season $season|s$sPad|s$season"
                val sep = "ep$ePad|ep$episode|episode $episode|episode $ePad"
                val entries = document.select("h5, h4, h3").filter { el ->
                    el.text().contains(Regex("(?i)($stag)"))
                }
                entries.forEach { entry ->
                    val href = entry.nextElementSibling()?.selectFirst("a")?.attr("href") ?: ""
                    if (href.isNotBlank()) {
                        val epDoc = runCatching { app.get(href, headers = headers, timeout = 7).document }.getOrNull()
                        val fEp = epDoc?.selectFirst("h5:matches((?i)($sep)), h4:matches((?i)($sep)), p:matches((?i)($sep))")
                        val linklist = mutableListOf<String>()
                        fEp?.nextElementSibling()?.select("a")?.forEach { subA ->
                            val sHref = subA.attr("href")
                            if (sHref.isNotBlank()) linklist.add(sHref)
                        }
                        linklist.forEach { u ->
                            if (u.contains("hubcloud") || u.contains("gdflix") || u.contains("gdlink")) {
                                runCatching { loadExtractor(u, href, subtitleCallback, callback) }
                            } else {
                                val finalDoc = runCatching { app.get(u, headers = headers, timeout = 6).document }.getOrNull()
                                finalDoc?.select("a[href*='hubcloud'], a[href*='gdflix'], a[href*='gdlink']")?.forEach { directA ->
                                    runCatching { loadExtractor(directA.attr("href"), u, subtitleCallback, callback) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun queryMoviesmod(
        query: String,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        imdbId: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = DomainResolver.get("moviesmod")
        val searchParam = if (!imdbId.isNullOrBlank()) imdbId else {
            if (isMovie) query else "$query $season"
        }
        val searchUrl = "$base/search/${URLEncoder.encode(searchParam, "UTF-8")}"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$base/"
        )
        val res = runCatching { app.get(searchUrl, headers = headers, timeout = 7) }.getOrNull() ?: return
        DomainResolver.reportRedirect("moviesmod", res.url)
        val doc = res.document
        val firstPost = doc.selectFirst("#content_box article > a, div.post-cards article > a")?.attr("href") ?: return
        val postDoc = runCatching { app.get(firstPost, headers = headers, timeout = 7).document }.getOrNull() ?: return

        val hTag = if (isMovie) "h4" else "h3"
        val aTag = if (isMovie) "Download" else "Episode"
        val sTag = if (isMovie) "" else "(S0?$season|Season 0?$season)"
        val entries = postDoc.select("div.thecontent $hTag:matches((?i)$sTag.*(480p|720p|1080p|2160p))")
            .filter { element -> !element.text().contains("MoviesMod", true) }

        entries.forEach { entry ->
            val link = entry.nextElementSibling()?.selectFirst("a:contains($aTag)")?.attr("href")
                ?.substringAfter("=") ?: ""
            if (link.isNotBlank()) {
                val selector = if (isMovie) "p a.maxbutton, a[href*='hubcloud'], a[href*='gdflix']" else "h3 a:matches((?i)Episode 0?$episode)"
                val epDoc = runCatching { app.get(link, headers = headers, timeout = 7).document }.getOrNull()
                val source = epDoc?.selectFirst(selector)?.attr("href") ?: return@forEach
                val bypassedLink = if (source.contains("href.li/?")) source.substringAfter("href.li/?") else source
                runCatching {
                    loadExtractor(bypassedLink, firstPost, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun queryUHDMovies(
        query: String,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        imdbId: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = DomainResolver.get("uhdmovies")
        val cleanQuery = query.replace(Regex("[^a-zA-Z0-9\\s]"), " ").trim()
        val searchParam = if (year != null && isMovie) "$cleanQuery $year" else cleanQuery
        val searchUrl = "$base/search/${URLEncoder.encode(searchParam, "UTF-8")}"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$base/"
        )
        val res = runCatching { app.get(searchUrl, headers = headers, timeout = 7) }.getOrNull() ?: return
        DomainResolver.reportRedirect("uhdmovies", res.url)
        val postUrl = res.document.selectFirst("article div.entry-image a, article h2 a")?.attr("href") ?: return
        val doc = runCatching { app.get(postUrl, headers = headers, timeout = 7).document }.getOrNull() ?: return

        val selector = if (isMovie) {
            if (year != null) "div.entry-content p:matches($year)" else "div.entry-content p:matches((?i)(480p|720p|1080p|2160p|4k))"
        } else {
            "div.entry-content p:matches((?i)(S0?$season|Season 0?$season))"
        }
        val epSelector = if (isMovie) "a:matches((?i)(Download))" else "a:matches((?i)(Episode 0?$episode))"

        val links = doc.select(selector).mapNotNull { el ->
            el.nextElementSibling()?.select(epSelector)?.attr("href")
        }

        links.forEach { linkUrl ->
            if (linkUrl.isNotBlank()) {
                val driveLink = if (linkUrl.contains("driveleech") || linkUrl.contains("driveseed")) {
                    val baseUrl = linkUrl.substringBefore("/file/")
                    val text = runCatching { app.get(linkUrl, headers = headers, timeout = 6).text }.getOrNull() ?: ""
                    val regex = Regex("""window\\.location\\.replace\\(["'](.*?)["']\\)""")
                    val fileId = regex.find(text)?.groupValues?.get(1)
                    if (fileId != null) "$baseUrl$fileId" else linkUrl
                } else if (linkUrl.contains("href.li/?")) {
                    linkUrl.substringAfter("href.li/?")
                } else linkUrl

                runCatching {
                    loadExtractor(driveLink, postUrl, subtitleCallback, callback)
                }
            }
        }
    }

    private fun extractSearchTokens(rawTitle: String?): Pair<String, List<String>> {
        if (rawTitle.isNullOrBlank()) return "" to emptyList()
        var base = rawTitle

        base = base.replace(Regex("""(?i)\b(grand\s+finale|season\s+\d+|s\d+)\b"""), " ")
            .replace(Regex("""\s*\(\d{4}\).*"""), "")
            .replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

        val stopWords = setOf("with", "and", "the", "aur", "for", "ka", "ki", "ke", "all", "in", "of")
        val tokens = base.lowercase().split(" ").filter { it.length >= 3 && it !in stopWords }
        val core = if (tokens.isNotEmpty()) tokens.joinToString(" ") else base.lowercase()
        return core to tokens
    }

    private suspend fun queryAllMovieLand(
        query: String,
        coreTokens: List<String>,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = "https://mapi.elochkaigolochla.com/api/v1"
        val searchUrl = "$base/new-search/movies?title=${URLEncoder.encode(query, "UTF-8")}&page=1"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10)",
            "Referer" to "https://allmovielandapp.app/"
        )
        val jsonResponse = runCatching {
            app.get(searchUrl, headers = headers, timeout = 7)
        }.getOrNull() ?: return
        DomainResolver.reportRedirect("rogmovies", jsonResponse.url)
        val jsonRes = jsonResponse.text

        val searchObj = runCatching { parseJson<AllMovieSearchResponse>(jsonRes) }.getOrNull() ?: return
        val results = searchObj.results ?: return

        val significantWords = coreTokens.filter { it.length >= 3 && it.toIntOrNull() == null }
        val primaryWord = significantWords.firstOrNull() ?: query.lowercase().split(" ").firstOrNull() ?: query.lowercase()

        val target = results.firstOrNull { m ->
            val mTitle = (m.titleEn ?: m.titleRu ?: "").lowercase()
            if (primaryWord.isNotBlank() && !mTitle.contains(primaryWord)) return@firstOrNull false

            val tokenMatches = coreTokens.count { mTitle.contains(it) }
            val minMatches = (coreTokens.size * 0.7).coerceAtLeast(1.0).toInt()
            val nameMatch = mTitle.contains(query.lowercase()) || (coreTokens.isNotEmpty() && tokenMatches >= minMatches)
            val yearMatch = if (year != null && m.year != null) (m.year == year || m.year == year - 1 || m.year == year + 1) else true
            nameMatch && yearMatch
        } ?: return

        target.player?.forEach { p ->
            val pUrl = p.url?.trim() ?: return@forEach
            val translator = p.translator ?: "Stream"
            val qualityLabel = p.quality ?: "HD"

            if (p.source == "m3u8" || pUrl.contains(".m3u8") || pUrl.contains("/getm3u8/")) {
                val streamHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                    "Referer" to "https://allmovielandapp.app/"
                )
                callback(
                    newExtractorLink(
                        source = "WaveFlix [AllMovieLand]",
                        name = "AllMovieLand - $translator [$qualityLabel ⚡]",
                        url = pUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://allmovielandapp.app/"
                        this.headers = streamHeaders
                        this.quality = parseQuality(qualityLabel)
                    }
                )
            } else if (p.source == "iframe" && pUrl.startsWith("http")) {
                runCatching {
                    loadExtractor(pUrl, "https://allmovielandapp.app/", subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun queryBollyflix(
        query: String,
        coreTokens: List<String>,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        imdbId: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = DomainResolver.get("bollyflix")
        val cleanQuery = query.replace(Regex("[^a-zA-Z0-9 ]"), " ").trim()
        val searchUrl = if (!imdbId.isNullOrBlank()) "$base/search/$imdbId" else "$base/?s=${URLEncoder.encode(cleanQuery, "UTF-8")}"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$base/"
        )
        val searchResponse = runCatching {
            app.get(searchUrl, headers = headers, timeout = 7)
        }.getOrNull() ?: return
        DomainResolver.reportRedirect("bollyflix", searchResponse.url)
        val searchRes = searchResponse.text

        val doc = org.jsoup.Jsoup.parse(searchRes)
        val posts = doc.select("article, div.post-item, div.post-content, h2.entry-title a, a[href*='-movie/'], a[href*='-series/']")
        val postLinks = posts.mapNotNull { el ->
            if (el.tagName() == "a") el.attr("href") else el.selectFirst("a")?.attr("href")
        }.distinct().filter { it.isNotBlank() }

        val significantWords = coreTokens.filter { it.length >= 3 && it.toIntOrNull() == null }
        val primaryWord = significantWords.firstOrNull() ?: cleanQuery.lowercase().split(" ").firstOrNull() ?: cleanQuery.lowercase()

        val targetPost = postLinks.firstOrNull { link ->
            val lower = link.lowercase()
            if (!lower.contains(primaryWord)) return@firstOrNull false
            if (!isMovie) {
                val sStr = season.toString()
                val sPad = String.format("%02d", season)
                val isSeason = lower.contains("season-$sStr") || lower.contains("season$sStr") ||
                    lower.contains("season-$sPad") || lower.contains("season$sPad") ||
                    lower.contains("s$sStr") || lower.contains("s$sPad") ||
                    lower.contains("s0$season") || lower.contains("season-1-") || lower.contains("season-1-to-") ||
                    (season == 1 && !lower.contains("season-2") && !lower.contains("s02") && !lower.contains("s2"))
                if (!isSeason) return@firstOrNull false
            } else {
                if (lower.contains("season") || lower.contains("-s1-") || lower.contains("-s2-")) return@firstOrNull false
            }
            true
        } ?: return
        val postHtml = runCatching {
            app.get(targetPost, headers = headers + ("Referer" to searchUrl), timeout = 7).text
        }.getOrNull() ?: return

        val postDoc = org.jsoup.Jsoup.parse(postHtml)
        postDoc.select("a[href*='fastdl.'], a[href*='hubcloud.'], a[href*='vcloud.'], a[href*='gofile.'], a[href*='pixeldrain.']").forEach { a ->
            val href = a.attr("href").trim()
            val text = (a.text() + " " + a.parent()?.text().orEmpty()).lowercase()
            if (!isMovie) {
                val epPatterns = listOf("episode $episode", "episode 0$episode", "ep $episode", "ep 0$episode", "ep$episode", "ep0$episode", "e0$episode", "e$episode")
                val isSpecificOtherEp = (1..50).filter { it != episode }.any { otherEp ->
                    val otherPatterns = listOf("episode $otherEp", "episode 0$otherEp", "ep $otherEp", "ep 0$otherEp", "ep$otherEp", "ep0$otherEp", "e0$otherEp", "e$otherEp")
                    otherPatterns.any { text.contains(it) } && !epPatterns.any { text.contains(it) }
                }
                if (isSpecificOtherEp) return@forEach
            }
            val postSlug = targetPost.substringAfterLast("/").replace("-", " ")
            val audioTag = extractAudioTag(postSlug, a.text(), a.parent()?.text())
            val qualityTag = findPrecedingQualityTag(a)
            val tagBuilder = StringBuilder()
            if (qualityTag != null) tagBuilder.append("#$qualityTag")
            if (audioTag.isNotBlank()) tagBuilder.append("#$audioTag")
            val taggedHref = if (tagBuilder.isNotEmpty() && !href.contains("#")) "$href$tagBuilder" else href
            if (href.startsWith("http")) {
                runCatching {
                    loadExtractor(taggedHref, targetPost, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun queryRogMovies(
        query: String,
        coreTokens: List<String>,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        imdbId: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = DomainResolver.get("rogmovies")
        val searchUrl = if (!imdbId.isNullOrBlank()) {
            "$base/ts-search.php?q=$imdbId&page=1"
        } else {
            "$base/ts-search.php?q=${URLEncoder.encode(query, "UTF-8")}&page=1"
        }
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$base/?s="
        )
        val jsonResponse = runCatching {
            app.get(searchUrl, headers = headers, timeout = 7)
        }.getOrNull() ?: return
        DomainResolver.reportRedirect("rogmovies", jsonResponse.url)
        val jsonRes = jsonResponse.text

        val data = runCatching { parseJson<RogSearchResponse>(jsonRes) }.getOrNull() ?: return
        val hits = data.hits ?: return

        val cleanQuery = query.lowercase().trim()
        val queryTokens = if (coreTokens.isNotEmpty()) coreTokens else cleanQuery.split(" ").filter { it.length > 2 }
        val significantWords = queryTokens.filter { it.length >= 3 && it.toIntOrNull() == null }
        val primaryWord = significantWords.firstOrNull() ?: cleanQuery.split(" ").firstOrNull() ?: cleanQuery

        val relevantHits = hits.filter { hit ->
            val hitTitle = hit.document?.post_title?.lowercase() ?: ""
            val hitImdb = hit.document?.imdb_id

            // CSX Invariant: Strict IMDb check when available ensures 0 wrong movie/show collisions
            if (!imdbId.isNullOrBlank() && !hitImdb.isNullOrBlank() && hitImdb != imdbId) return@filter false

            if (imdbId.isNullOrBlank()) {
                if (primaryWord.isNotBlank() && !hitTitle.contains(primaryWord)) return@filter false
                val tokenMatches = queryTokens.count { hitTitle.contains(it) }
                val minMatches = (queryTokens.size * 0.7).coerceAtLeast(1.0).toInt()
                val isNameMatch = hitTitle.contains(cleanQuery) || (queryTokens.isNotEmpty() && tokenMatches >= minMatches)
                if (!isNameMatch) return@filter false
            }

            val isSeasonMatch = if (!isMovie) {
                val sStr = season.toString()
                val sPad = String.format("%02d", season)
                hitTitle.contains("s$sPad") || hitTitle.contains("s$sStr") ||
                hitTitle.contains("season $sStr") || hitTitle.contains("season $sPad") ||
                hitTitle.contains("season 1-") ||
                (season == 1 && (hitTitle.contains("complete") || (!hitTitle.contains("season 2") && !hitTitle.contains("s02"))))
            } else {
                // Reject TV shows when playing a movie
                !hitTitle.contains("season") && !hitTitle.contains("s01") && !hitTitle.contains("s02") && !hitTitle.contains("episode")
            }

            isSeasonMatch
        }

        val targetHits = relevantHits

        targetHits.take(2).forEach { hit ->
            val permalink = hit.document?.permalink ?: return@forEach
            val postUrl = if (permalink.startsWith("http")) permalink else "$base$permalink"
            val postHtml = runCatching {
                app.get(postUrl, headers = headers, timeout = 8).text
            }.getOrNull() ?: return@forEach

            val doc = org.jsoup.Jsoup.parse(postHtml)
            val currentImdb = doc.select("a[href*='imdb']").attr("href").substringAfter("title/").substringBefore("/")
            if (!imdbId.isNullOrBlank() && currentImdb.isNotBlank() && currentImdb != imdbId) {
                return@forEach
            }

            // CSX Exact Episode Selector for TV series
            if (!isMovie) {
                doc.select("h4:matches((?i)(Season $season)), h3:matches((?i)(Season $season)), h5:matches((?i)(Season $season))").forEach { h ->
                    h.nextElementSibling()?.select("a:matches((?i)(V-Cloud|Single|Episode|G-Direct|Instant|Resumable))")?.forEach { a ->
                        val href = a.attr("href").trim()
                        if (href.isNotBlank()) {
                            runCatching {
                                val epHtml = app.get(href, headers = headers, timeout = 7).text
                                val epDoc = org.jsoup.Jsoup.parse(epHtml)
                                val epLink = epDoc.selectFirst("h4:contains(Episode):contains($episode)")
                                    ?.nextElementSibling()
                                    ?.selectFirst("a:matches((?i)(V-Cloud|fastdl|G-Direct|Resumable))")
                                    ?.attr("href")
                                if (!epLink.isNullOrBlank()) {
                                    loadExtractor(epLink, href, subtitleCallback, callback)
                                }
                            }
                        }
                    }
                }
            }

            doc.select("a[href*='vcloud.'], a[href*='hubcloud.'], a[href*='fastdl.'], a[href*='fast-dl.'], a[href*='zee-dl.']").forEach { a ->
                val href = a.attr("href").trim()
                val btnText = a.text().trim()
                if (!isMovie) {
                    val contextText = (btnText + " " + a.parent()?.text().orEmpty() + " " + (a.previousElementSibling()?.text().orEmpty())).lowercase()
                    val epPatterns = listOf("episode $episode", "episode 0$episode", "ep $episode", "ep 0$episode", "ep$episode", "ep0$episode", "e0$episode", "e$episode")
                    val isSpecificOtherEp = (1..50).filter { it != episode }.any { otherEp ->
                        val otherPatterns = listOf("episode $otherEp", "episode 0$otherEp", "ep $otherEp", "ep 0$otherEp", "ep$otherEp", "ep0$otherEp", "e0$otherEp", "e$otherEp")
                        otherPatterns.any { contextText.contains(it) } && !epPatterns.any { contextText.contains(it) }
                    }
                    if (isSpecificOtherEp) return@forEach
                }
                val hitTitle = hit.document?.post_title ?: ""
                val audioTag = extractAudioTag(hitTitle, btnText)
                val qualityTag = findPrecedingQualityTag(a)
                val tagBuilder = StringBuilder()
                if (qualityTag != null) tagBuilder.append("#$qualityTag")
                if (audioTag.isNotBlank()) tagBuilder.append("#$audioTag")
                val taggedHref = if (tagBuilder.isNotEmpty() && !href.contains("#")) "$href$tagBuilder" else href

                if (href.startsWith("http") && !href.contains("filepress") && !href.contains("filebee") && !href.contains("nexdrive")) {
                    runCatching {
                        loadExtractor(taggedHref, postUrl, subtitleCallback, callback)
                    }
                }
            }
            // Follow nexdrive cloud dispenser pages for direct vcloud/hubcloud/fastdl links
            doc.select("a[href*='nexdrive.'][href*='/genxfm']").take(5).forEach { a ->
                val nexUrl = a.attr("href").trim()
                val postQualityTag = findPrecedingQualityTag(a)
                runCatching {
                    val nexHtml = app.get(nexUrl, headers = headers, timeout = 7).text
                    val nexDoc = org.jsoup.Jsoup.parse(nexHtml)
                    val candidateLinks = mutableListOf<String>()

                    if (!isMovie) {
                        for (h in nexDoc.select("h1, h2, h3, h4, h5, h6, p")) {
                            val hText = h.text().lowercase()
                            val isTargetEp = hText.contains("episode: $episode") ||
                                hText.contains("episode $episode") ||
                                hText.contains("ep: $episode") ||
                                hText.contains("ep $episode") ||
                                hText.contains("e0$episode") ||
                                hText.contains("e$episode")
                            if (isTargetEp) {
                                val nextEl = h.nextElementSibling()
                                if (nextEl != null) {
                                    nextEl.select("a[href]").forEach { subA ->
                                        val sHref = subA.attr("href").trim()
                                        if (sHref.contains("fastdl.") || sHref.contains("vcloud.") || sHref.contains("hubcloud.") || sHref.contains("fast-dl.")) {
                                            candidateLinks.add(sHref)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        nexDoc.select("a[href*='vcloud.'], a[href*='hubcloud.'], a[href*='fastdl.'], a[href*='fast-dl.']").forEach { subA ->
                            candidateLinks.add(subA.attr("href").trim())
                        }
                    }

                    if (candidateLinks.isEmpty()) {
                        val allLinks = nexDoc.select("a[href*='vcloud.'], a[href*='hubcloud.'], a[href*='fastdl.'], a[href*='fast-dl.']")
                            .map { it.attr("href").trim() }
                        if (!isMovie && episode in 1..allLinks.size) {
                            candidateLinks.add(allLinks[episode - 1])
                        } else if (allLinks.isNotEmpty()) {
                            candidateLinks.add(allLinks.first())
                        }
                    }

                    candidateLinks.distinct().forEach { targetLink ->
                        val tagged = if (postQualityTag != null && !targetLink.contains("#")) "$targetLink#$postQualityTag" else targetLink
                        loadExtractor(tagged, nexUrl, subtitleCallback, callback)
                    }
                }
            }
        }
    }

    private fun parseQuality(qualityStr: String?): Int {
        if (qualityStr.isNullOrBlank()) return Qualities.Unknown.value
        val match = Regex("""(\d{3,4})p?""").find(qualityStr)
        return when (match?.groupValues?.get(1)?.toIntOrNull()) {
            2160, 4 -> Qualities.P2160.value
            1440 -> Qualities.P1440.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            else -> when {
                qualityStr.contains("4k", ignoreCase = true) -> Qualities.P2160.value
                qualityStr.contains("1080", ignoreCase = true) || qualityStr.contains("fhd", ignoreCase = true) -> Qualities.P1080.value
                qualityStr.contains("720", ignoreCase = true) || qualityStr.contains("hd", ignoreCase = true) -> Qualities.P720.value
                else -> Qualities.Unknown.value
            }
        }
    }

    private fun WaveMediaItem.toSearchResponse(): SearchResponse? {
        val idVal = this.id ?: return null
        val isMovie = (this.mediaType?.lowercase() == "movie")
        val titleVal = this.title ?: this.name ?: return null
        val poster = if (!this.posterPath.isNullOrBlank()) {
            if (this.posterPath.startsWith("http")) this.posterPath else "https://image.tmdb.org/t/p/w342${this.posterPath}"
        } else null

        val targetUrl = "$mainUrl/details/${if (isMovie) "movie" else "tv"}/$idVal"

        return if (isMovie) {
            newMovieSearchResponse(titleVal, targetUrl, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(titleVal, targetUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    // JSON DTOs
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WaveSearchResponse(
        @JsonProperty("results") val results: List<WaveMediaItem>? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
        @JsonProperty("page") val page: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WaveMediaItem(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("mediaType") val mediaType: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("firstAirDate") val firstAirDate: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WaveSeasonResponse(
        @JsonProperty("episodes") val episodes: List<WaveEpisodeItem>? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WaveEpisodeItem(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null
    )

    data class WaveLinkData(
        val tmdbId: Long,
        val isMovie: Boolean,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val year: Int? = null,
        val imdbId: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RogSearchResponse(
        @JsonProperty("found") val found: Int? = null,
        @JsonProperty("hits") val hits: List<RogHitItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RogHitItem(
        @JsonProperty("document") val document: RogDocument? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CinemetaSearchResponse(
        @JsonProperty("metas") val metas: List<CinemetaMetaItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CinemetaMetaItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RogDocument(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("imdb_id") val imdb_id: String? = null,
        @JsonProperty("post_title") val post_title: String? = null,
        @JsonProperty("permalink") val permalink: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AllMovieSearchResponse(
        @JsonProperty("results") val results: List<AllMovieItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AllMovieItem(
        @JsonProperty("kinopoisk_id") val kinopoiskId: Long? = null,
        @JsonProperty("title_en") val titleEn: String? = null,
        @JsonProperty("title_ru") val titleRu: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("player") val player: List<AllMoviePlayerItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AllMoviePlayerItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("translator") val translator: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("source") val source: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaHashResponse(
        @JsonProperty("_hash") val hash: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaServerList(
        @JsonProperty("servers") val servers: List<NxshaServerItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaServerItem(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("scraper") val scraper: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("web_support") val webSupport: Boolean? = null,
        @JsonProperty("quality") val quality: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaSourcesResponse(
        @JsonProperty("sources") val sources: List<NxshaSourceItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaSourceItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("org_uri") val orgUri: String? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("isEmbed") val isEmbed: Boolean? = null,
        @JsonProperty("type") val type: String? = null
    )

    // Stremio OpenSubtitles v3 DTOs & Language Mapping
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StremioSubtitleResponse(
        @JsonProperty("subtitles") val subtitles: List<StremioSubtitleItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StremioSubtitleItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null
    )

    private fun mapSubtitleLanguage(code: String?): String {
        return when (code?.lowercase()) {
            "eng" -> "English"
            "hin" -> "Hindi"
            "spa" -> "Spanish"
            "fre", "fra" -> "French"
            "ger", "deu" -> "German"
            "ita" -> "Italian"
            "pob" -> "Portuguese (BR)"
            "por" -> "Portuguese"
            "ara" -> "Arabic"
            "rus" -> "Russian"
            "ind" -> "Indonesian"
            "ben" -> "Bengali"
            "tam" -> "Tamil"
            "tel" -> "Telugu"
            "mal" -> "Malayalam"
            "kan" -> "Kannada"
            "pan" -> "Punjabi"
            "kor" -> "Korean"
            "jpn" -> "Japanese"
            "chi", "zho" -> "Chinese"
            "tur" -> "Turkish"
            "pol" -> "Polish"
            "dut", "nld" -> "Dutch"
            "vie" -> "Vietnamese"
            "tha" -> "Thai"
            null, "" -> "English"
            else -> code.replaceFirstChar { it.uppercase() }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaSubtitlesResponse(
        @JsonProperty("subtitles") val subtitles: List<NxshaSubtitleItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaSubtitleItem(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("srclang") val srclang: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("uri") val uri: String? = null
    )

    // NetMirror / NewTV DTOs & Dynamic Engine
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewTvTokenResponse(
        @JsonProperty("token_hash") val token_hash: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewTvOtpResponse(
        @JsonProperty("usertoken") val usertoken: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("otp") val otp: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewTvPlayerResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("ott") val ott: String? = null,
        @JsonProperty("video_link") val video_link: String? = null,
        @JsonProperty("referer") val referer: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("ep") val ep: String? = null,
        @JsonProperty("ep_title") val ep_title: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewTvPlayListSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewTvPlayListTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewTvPlayListItem(
        @JsonProperty("sources") val sources: List<NewTvPlayListSource>? = null,
        @JsonProperty("tracks") val tracks: List<NewTvPlayListTrack>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NetMirrorSearchResponse(
        @JsonProperty("searchResult") val searchResult: List<NetMirrorSearchResultItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NetMirrorSearchResultItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("t") val t: String? = null,
        @JsonProperty("y") val y: String? = null,
        @JsonProperty("r") val r: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NetMirrorResponse(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("streams") val streams: List<NetMirrorStreamItem>? = null,
        @JsonProperty("captions") val captions: List<NetMirrorCaptionItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NetMirrorStreamItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolution") val resolution: Any? = null,
        @JsonProperty("size") val size: Long? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NetMirrorCaptionItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    object NetMirrorResolver {
        private val MOBIDETECT_DOMAINS = listOf(
            "https://mobidetect.cc",
            "https://mobidetects.xyz",
            "https://mobidetects.com",
            "https://mobidetect.app",
            "https://mobidetect.art",
            "https://mobidetect.click",
            "https://mobidetect.ink",
            "https://mobidetect.live",
            "https://mobidetect.pro",
            "https://mobidetect.shop",
            "https://mobidetect.site",
            "https://mobidetect.space",
            "https://mobidetect.store",
            "https://mobidetect.top",
            "https://mobidetect.vip",
            "https://mobidetect.wiki"
        )

        private const val DEFAULT_API_BASE = "https://tv.imgcdn.kim"
        private var cachedApiBase: String? = null
        private var apiBaseResolvedAt = 0L
        private const val API_BASE_TTL_MS = 60 * 60 * 1000L // 1 hour

        private var cachedUserToken: String? = null
        private var userTokenCreatedAt = 0L
        private const val USER_TOKEN_TTL_MS = 25 * 60 * 1000L // 25 minutes

        val baseHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.Gatu v1.0",
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "NetmirrorNewTV v1.0"
        )

        suspend fun resolveApiBase(client: com.lagradost.nicehttp.Requests): String {
            val now = System.currentTimeMillis()
            if (cachedApiBase != null && (now - apiBaseResolvedAt) < API_BASE_TTL_MS) {
                return cachedApiBase!!
            }

            for (domain in MOBIDETECT_DOMAINS) {
                try {
                    val res = client.get(
                        "$domain/checknewtv.php",
                        headers = baseHeaders + mapOf(
                            "Cache-Control" to "no-cache, no-store, must-revalidate",
                            "Pragma" to "no-cache"
                        ),
                        timeout = 5
                    ).text
                    val tokenObj = tryParseJson<NewTvTokenResponse>(res)
                    val tokenHash = tokenObj?.token_hash
                    if (!tokenHash.isNullOrBlank()) {
                        val decodedBytes = try {
                            Base64.decode(tokenHash, Base64.DEFAULT)
                        } catch (_: Throwable) {
                            java.util.Base64.getDecoder().decode(tokenHash)
                        }
                        val resolved = String(decodedBytes, Charsets.UTF_8).trimEnd('/')
                        if (resolved.startsWith("http")) {
                            cachedApiBase = resolved
                            apiBaseResolvedAt = now
                            return resolved
                        }
                    }
                } catch (_: Throwable) {}
            }

            cachedApiBase = DEFAULT_API_BASE
            apiBaseResolvedAt = now
            return DEFAULT_API_BASE
        }

        suspend fun getUserToken(client: com.lagradost.nicehttp.Requests, apiBase: String, force: Boolean = false): String? {
            val now = System.currentTimeMillis()
            if (!force && cachedUserToken != null && (now - userTokenCreatedAt) < USER_TOKEN_TTL_MS) {
                return cachedUserToken
            }

            val otpHeaders = baseHeaders + mapOf(
                "Connection" to "Keep-Alive",
                "expires" to "0",
                "otp" to "111111",
                "pragma" to "no-cache",
                "Cache-Control" to "no-cache, no-store, must-revalidate"
            )

            try {
                val res = client.get("$apiBase/newtv/otp.php", headers = otpHeaders, timeout = 7).text
                val otpObj = tryParseJson<NewTvOtpResponse>(res)
                if (otpObj?.status == "ok" && !otpObj.usertoken.isNullOrBlank()) {
                    cachedUserToken = otpObj.usertoken
                    userTokenCreatedAt = now
                    return otpObj.usertoken
                }
            } catch (_: Throwable) {}

            return cachedUserToken
        }
    }

    // Vidlink DTOs
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidlinkEncResponse(
        @JsonProperty("result") val result: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidlinkResponse(
        @JsonProperty("stream") val stream: VidlinkStreamObj? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidlinkStreamObj(
        @JsonProperty("qualities") val qualities: Map<String, VidlinkQualityEntry>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidlinkQualityEntry(
        @JsonProperty("url") val url: String? = null
    )

    // Nxsha AES-256-CBC Crypto Implementation (OpenSSL EVP_BytesToKey)
    object NxshaCrypto {
        private const val KEY = "f4488ab4da401203d23baa129fc546153898162524635d6776826d0c867ccaa3"

        private fun b64Encode(bytes: ByteArray): String {
            return try {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (_: Throwable) {
                java.util.Base64.getEncoder().encodeToString(bytes)
            }
        }

        private fun b64Decode(str: String): ByteArray {
            return try {
                Base64.decode(str, Base64.DEFAULT)
            } catch (_: Throwable) {
                java.util.Base64.getDecoder().decode(str)
            }
        }

        private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keyLen: Int = 32, ivLen: Int = 16): Pair<ByteArray, ByteArray> {
            val md5 = MessageDigest.getInstance("MD5")
            val totalLen = keyLen + ivLen
            val combined = ByteArray(totalLen)
            var currentLen = 0
            var d = ByteArray(0)

            while (currentLen < totalLen) {
                md5.reset()
                if (d.isNotEmpty()) {
                    md5.update(d)
                }
                md5.update(password)
                md5.update(salt)
                d = md5.digest()

                val copyLen = minOf(d.size, totalLen - currentLen)
                System.arraycopy(d, 0, combined, currentLen, copyLen)
                currentLen += copyLen
            }

            val key = combined.copyOfRange(0, keyLen)
            val iv = combined.copyOfRange(keyLen, totalLen)
            return Pair(key, iv)
        }

        fun encrypt(jsonString: String): String {
            val ts = System.currentTimeMillis()
            val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
            val saltRandom = (1..10).map { chars.random() }.joinToString("")
            val payload = if (jsonString.endsWith("}")) {
                jsonString.dropLast(1) + ""","_req_ts":$ts,"_req_salt":"$saltRandom"}"""
            } else {
                """{"_req_ts":$ts,"_req_salt":"$saltRandom"}"""
            }

            val salt = ByteArray(8)
            SecureRandom().nextBytes(salt)

            val (key, iv) = evpBytesToKey(KEY.toByteArray(Charsets.UTF_8), salt)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

            val header = "Salted__".toByteArray(Charsets.UTF_8)
            val total = ByteArray(header.size + salt.size + encrypted.size)
            System.arraycopy(header, 0, total, 0, header.size)
            System.arraycopy(salt, 0, total, header.size, salt.size)
            System.arraycopy(encrypted, 0, total, header.size + salt.size, encrypted.size)

            val base64 = b64Encode(total)
            return base64.replace('+', '-').replace('/', '_').trimEnd('=')
        }

        fun decrypt(encryptedBase64Url: String): String? {
            return try {
                var b64 = encryptedBase64Url.replace('-', '+').replace('_', '/')
                val remainder = b64.length % 4
                if (remainder != 0) {
                    b64 += "=".repeat(4 - remainder)
                }
                val raw = b64Decode(b64)
                if (raw.size < 16) return null
                val header = String(raw.copyOfRange(0, 8), Charsets.UTF_8)
                if (header != "Salted__") return null
                val salt = raw.copyOfRange(8, 16)
                val ciphertext = raw.copyOfRange(16, raw.size)

                val (key, iv) = evpBytesToKey(KEY.toByteArray(Charsets.UTF_8), salt)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val decryptedBytes = cipher.doFinal(ciphertext)
                String(decryptedBytes, Charsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
