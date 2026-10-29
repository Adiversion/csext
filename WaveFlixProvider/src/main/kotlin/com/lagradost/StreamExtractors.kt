package com.lagradost

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup

fun parseStreamQuality(qualityStr: String?): Int {
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
            qualityStr.contains("4k", ignoreCase = true) || qualityStr.contains("uhd", ignoreCase = true) -> Qualities.P2160.value
            qualityStr.contains("1080", ignoreCase = true) || qualityStr.contains("fhd", ignoreCase = true) -> Qualities.P1080.value
            qualityStr.contains("720", ignoreCase = true) || qualityStr.contains("hd", ignoreCase = true) -> Qualities.P720.value
            qualityStr.contains("480", ignoreCase = true) || qualityStr.contains("sd", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}

fun unpackDeanEdwards(script: String): String {
    val match = Regex("""eval\(function\(p,a,c,k,e,[rd]\)\{.*?return p\}\s*\(\s*['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\(['"]\|['"]\)""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return script
    val (p, aStr, cStr, kStr) = match.destructured
    val a = aStr.toIntOrNull() ?: 10
    val c = cStr.toIntOrNull() ?: 0
    val k = kStr.split("|")
    val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    fun baseN(num: Int, base: Int): String {
        var n = num
        var res = ""
        while (n > 0) {
            res = chars[n % base] + res
            n /= base
        }
        return if (res.isEmpty()) "0" else res
    }
    val dict = HashMap<String, String>()
    for (i in 0 until c) {
        val key = baseN(i, a)
        val word = if (i < k.size && k[i].isNotEmpty()) k[i] else key
        dict[key] = word
    }
    return Regex("""\b\w+\b""").replace(p) { m ->
        dict[m.value] ?: m.value
    }
}

fun extractResolution(vararg sources: String?): Int {
    for (s in sources) {
        if (s.isNullOrBlank()) continue
        val decoded = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        val lower = decoded.lowercase()

        val has2160 = lower.contains("2160p") || lower.contains("4k") || lower.contains("uhd")
        val has1440 = lower.contains("1440p") || lower.contains("2k")
        val has1080 = lower.contains("1080p") || lower.contains("fhd")
        val has720 = lower.contains("720p")
        val has480 = lower.contains("480p") || lower.contains("sd")
        val has360 = lower.contains("360p")

        val distinctCount = listOf(has2160, has1440, has1080, has720, has480, has360).count { it }
        // If a string contains multiple resolutions (e.g. 480p, 720p, 1080p), it is an ambiguous multi-pack title
        if (distinctCount > 1) continue

        when {
            has2160 -> return Qualities.P2160.value
            has1440 -> return Qualities.P1440.value
            has1080 -> return Qualities.P1080.value
            has720 || (lower.contains("hd") && !lower.contains("hdtv") && !lower.contains("hdhub")) -> return Qualities.P720.value
            has480 -> return Qualities.P480.value
            has360 -> return Qualities.P360.value
        }
    }
    return Qualities.Unknown.value
}

fun formatQualityTag(q: Int): String {
    return when (q) {
        Qualities.P2160.value -> "4K"
        Qualities.P1440.value -> "1440p"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value -> "720p"
        Qualities.P480.value -> "480p"
        Qualities.P360.value -> "360p"
        else -> ""
    }
}

fun extractAudioTag(vararg sources: String?): String {
    for (s in sources) {
        if (s.isNullOrBlank()) continue
        val decoded = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        val lower = decoded.lowercase()

        // 1. Detect Multi-Audio / Dual-Audio flags
        val hasMulti = Regex("""\b(multi|multiaudio|multi-audio|multi\.audio|multiple|all\.audio)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        val hasDual = Regex("""\b(dual|dualaudio|dual-audio|dual\.audio)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)

        // 2. Detect Specific Language Tags with positional appearance ordering
        val langPatterns = listOf(
            "Hindi" to Regex("""\b(hindi|hind|hin\b(?=[-+._/\]\s]))""", RegexOption.IGNORE_CASE),
            "English" to Regex("""\b(english|eng\b(?=[-+._/\]\s]))""", RegexOption.IGNORE_CASE),
            "Tamil" to Regex("""\b(tamil|tam\b(?=[-+._/\]\s]))""", RegexOption.IGNORE_CASE),
            "Telugu" to Regex("""\b(telugu|tel\b(?=[-+._/\]\s]))""", RegexOption.IGNORE_CASE),
            "Malayalam" to Regex("""\b(malayalam|mal\b(?=[-+._/\]\s]))""", RegexOption.IGNORE_CASE),
            "Kannada" to Regex("""\b(kannada|kan\b(?=[-+._/\]\s]))""", RegexOption.IGNORE_CASE),
            "Bengali" to Regex("""\b(bengali|bangla)\b""", RegexOption.IGNORE_CASE),
            "Marathi" to Regex("""\b(marathi)\b""", RegexOption.IGNORE_CASE),
            "Punjabi" to Regex("""\b(punjabi)\b""", RegexOption.IGNORE_CASE),
            "Korean" to Regex("""\b(korean|kor\b(?=[-+._/\]\s]))""", RegexOption.IGNORE_CASE),
            "Japanese" to Regex("""\b(japanese|jap\b(?=[-+._/\]\s]))""", RegexOption.IGNORE_CASE),
            "Spanish" to Regex("""\b(spanish|esp)\b""", RegexOption.IGNORE_CASE),
            "French" to Regex("""\b(french|fra)\b""", RegexOption.IGNORE_CASE)
        )

        val found = mutableListOf<Pair<Int, String>>()
        for ((langName, pattern) in langPatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                found.add(match.range.first to langName)
            }
        }
        found.sortBy { it.first }
        val distinctLangs = found.map { it.second }.distinct()

        if (hasMulti || distinctLangs.size >= 3) {
            return if (distinctLangs.isNotEmpty()) {
                val shortLangs = distinctLangs.map { it.take(3) }.joinToString("+")
                "[Multi-Audio: $shortLangs 🌐]"
            } else {
                "[Multi-Audio 🌐]"
            }
        }
        if (distinctLangs.size == 2) {
            val l1 = distinctLangs[0].take(3)
            val l2 = distinctLangs[1].take(3)
            return "[Dual Audio: $l1+$l2 ⚡]"
        }
        if (hasDual) {
            return if (distinctLangs.isNotEmpty()) {
                val l1 = distinctLangs[0].take(3)
                "[Dual Audio: $l1 ⚡]"
            } else {
                "[Dual Audio: Hin+Eng ⚡]"
            }
        }
        if (distinctLangs.size == 1) {
            return "[${distinctLangs[0]}]"
        }
    }
    return ""
}


open class VCloudExtractor : ExtractorApi() {
    override val name = "V-Cloud"
    override val mainUrl = "https://vcloud.beer"
    override val requiresReferer = false

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: mainUrl))).text
        }.getOrNull() ?: return

        val tokenUrl = runCatching {
            val match = Regex("""atob\(atob\(['"]([A-Za-z0-9+/=]+)['"]\)\)""").find(html)?.groupValues?.get(1)
            if (match != null) {
                val step1 = String(Base64.decode(match, Base64.DEFAULT), Charsets.UTF_8)
                String(Base64.decode(step1, Base64.DEFAULT), Charsets.UTF_8)
            } else null
        }.getOrNull()

        val finalDoc = if (!tokenUrl.isNullOrBlank()) {
            runCatching {
                val tHtml = app.get(
                    tokenUrl,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to url),
                    timeout = 8
                ).text
                Jsoup.parse(tHtml)
            }.getOrNull() ?: Jsoup.parse(html)
        } else {
            Jsoup.parse(html)
        }

        val detectedQuality = extractResolution(
            url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
            finalDoc.select(".card-title, .card-header, h5, h4").firstOrNull()?.text(),
            tokenUrl,
            url
        )
        val detectedAudio = extractAudioTag(
            url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
            finalDoc.select(".card-title, .card-header, h5, h4").firstOrNull()?.text(),
            tokenUrl,
            url
        )
        val audioTag = if (detectedAudio.isNotBlank()) " $detectedAudio" else ""

        val fslLinks = finalDoc.select("a[href*='r2.dev'], a[href*='r2.cloudflarestorage.com'], a:contains(FSL)")
        fslLinks.forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http")) {
                val isFsl2 = a.text().contains("FSLv2", ignoreCase = true)
                val linkQ = extractResolution(
                    url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
                    href,
                    a.text(),
                    a.parent()?.text(),
                    formatQualityTag(detectedQuality)
                )
                val linkA = extractAudioTag(a.text(), a.parent()?.text(), href, detectedAudio)
                val finalA = if (linkA.isNotBlank()) " $linkA" else audioTag
                val finalQ = if (linkQ != Qualities.Unknown.value) linkQ else detectedQuality
                val qTag = formatQualityTag(finalQ)
                val displayQ = if (qTag.isNotBlank()) " $qTag" else ""

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - ${if (isFsl2) "FSLv2" else "FSL"} [Fast R2 Stream ⚡]$displayQ$finalA",
                        url = href,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = ""
                        this.quality = finalQ
                    }
                )
            }
        }

        // Pixeldrain mirror resolution (supports HTTP Range seeking)
        finalDoc.select("a[href*='pixeldrain.dev'], a[href*='pixeldrain.com']").forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http")) {
                loadExtractor(href, subtitleCallback, callback)
            }
        }
    }
}

class VCloudLolExtractor : VCloudExtractor() {
    override val mainUrl = "https://vcloud.lol"
}

class VCloudCxExtractor : VCloudExtractor() {
    override val mainUrl = "https://vcloud.cx"
}

open class NexdriveExtractor : ExtractorApi() {
    override val name = "Nexdrive"
    override val mainUrl = "https://nexdrive.beer"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: mainUrl))).text
        }.getOrNull() ?: return

        val doc = Jsoup.parse(html)
        doc.select("a[href*='vcloud.'], a[href*='fast-dl.'], a[href*='fastdl.'], a[href*='zee-dl.'], a[href*='zeedl.'], a[href*='gofile.io'], a[href*='filebee.'], a[href*='filepress.'], a[href*='one.vegadrive']").forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http")) {
                loadExtractor(href, url, subtitleCallback, callback)
            }
        }
    }
}

class NexdriveYouExtractor : NexdriveExtractor() {
    override val mainUrl = "https://nexdrive.you"
}

class NexdriveStoreExtractor : NexdriveExtractor() {
    override val mainUrl = "https://nexdrive.store"
}

open class FastDLExtractor : ExtractorApi() {
    override val name = "FastDL"
    override val mainUrl = "https://fast-dl.one"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: mainUrl))).text
        }.getOrNull() ?: return

        val detectedQuality = extractResolution(
            url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
            url
        )
        val detectedAudio = extractAudioTag(
            url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
            url
        )
        val audioTag = if (detectedAudio.isNotBlank()) " $detectedAudio" else ""

        // 1. Direct reurl script detection (exclude non-seekable Google download links)
        val reurlMatch = Regex("""var\s+reurl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!reurlMatch.isNullOrBlank() && !reurlMatch.contains("video-downloads.googleusercontent.com")) {
            val cdnMatch = Regex("""link=(https?://[^"'\s&]+)""").find(reurlMatch)
            val streamTarget = cdnMatch?.groupValues?.get(1) ?: reurlMatch
            if (streamTarget.startsWith("http") && !streamTarget.contains("video-downloads.googleusercontent.com")) {
                val q1 = extractResolution(
                    url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
                    streamTarget,
                    formatQualityTag(detectedQuality)
                )
                val a1 = extractAudioTag(streamTarget, detectedAudio)
                val qTag1 = formatQualityTag(q1)
                val displayQ1 = if (qTag1.isNotBlank()) " $qTag1" else ""
                val displayA1 = if (a1.isNotBlank()) " $a1" else audioTag
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - Instant Stream [⚡]$displayQ1$displayA1",
                        url = streamTarget,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = ""
                        this.quality = q1
                    }
                )
                return
            }
        }

        // 2. Direct download button extraction (exclude non-seekable Google download links)
        val btnMatch = Regex("""href=["'](dl\.php\?code=[^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!btnMatch.isNullOrBlank()) {
            val cleanBtn = btnMatch.replace("&amp;", "&")
            val prepUrl = "$mainUrl/$cleanBtn&prepare=1"
            runCatching {
                val jsonRes = app.get(
                    prepUrl,
                    headers = mapOf(
                        "User-Agent" to VCloudExtractor.USER_AGENT,
                        "Referer" to url,
                        "Accept" to "application/json"
                    ),
                    timeout = 6
                ).parsedSafe<Map<String, Any?>>()
                val target = jsonRes?.get("url") as? String
                if (!target.isNullOrBlank() && target.startsWith("http") && !target.contains("video-downloads.googleusercontent.com")) {
                    val q2 = extractResolution(
                        url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
                        target,
                        formatQualityTag(detectedQuality)
                    )
                    val a2 = extractAudioTag(target, detectedAudio)
                    val qTag2 = formatQualityTag(q2)
                    val displayQ2 = if (qTag2.isNotBlank()) " $qTag2" else ""
                    val displayA2 = if (a2.isNotBlank()) " $a2" else audioTag
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - Direct Stream [⚡]$displayQ2$displayA2",
                            url = target,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = ""
                            this.quality = q2
                        }
                    )
                }
            }
        }
    }
}

class ZeeDlExtractor : FastDLExtractor() {
    override val name = "Zee-DL"
    override val mainUrl = "https://zee-dl.lol"
}

class FastDlBeerExtractor : FastDLExtractor() {
    override val mainUrl = "https://fastdl.beer"
}

class FastDlLolExtractor : FastDLExtractor() {
    override val mainUrl = "https://fastdl.lol"
}

class FastDlServerExtractor : FastDLExtractor() {
    override val mainUrl = "https://dl.fastdlserver.site"
}

class FastDlSiteExtractor : FastDLExtractor() {
    override val mainUrl = "https://fastdlserver.site"
}

open class DriveSeedExtractor : ExtractorApi() {
    override val name = "DriveSeed"
    override val mainUrl = "https://driveseed.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT)).text
        }.getOrNull() ?: return

        val modproMatch = Regex("""https?://modpro\.[a-z]+/drive/[0-9a-zA-Z]+""").find(html)?.value
        if (!modproMatch.isNullOrBlank()) {
            val modHtml = runCatching {
                app.get(modproMatch, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to url)).text
            }.getOrNull() ?: return

            val gofileMatch = Regex("""https?://gofile\.io/d/[0-9a-zA-Z]+""").find(modHtml)?.value
            if (!gofileMatch.isNullOrBlank()) {
                loadExtractor(gofileMatch, subtitleCallback, callback)
            }
        }
    }
}

open class HubdriveExtractor : ExtractorApi() {
    override val name = "HubDrive"
    override val mainUrl = "https://hubdrive.bar"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT)).text
        }.getOrNull() ?: return

        val hubcloudMatch = Regex("""href=["'](https?://hubcloud\.[a-z]+/drive/[^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!hubcloudMatch.isNullOrBlank()) {
            val hashIdx = url.indexOf('#')
            val hashTag = if (hashIdx != -1) url.substring(hashIdx) else ""
            val docTitle = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1) ?: ""
            val qTag = formatQualityTag(extractResolution(url, docTitle))
            val aTag = extractAudioTag(url, docTitle)
            val tagBuilder = StringBuilder()
            if (qTag.isNotBlank() && !hashTag.contains(qTag)) tagBuilder.append("#$qTag")
            if (aTag.isNotBlank() && !hashTag.contains(aTag)) tagBuilder.append("#$aTag")
            val finalTag = hashTag + tagBuilder.toString()
            val forwardUrl = if (finalTag.isNotBlank() && !hubcloudMatch.contains("#")) "$hubcloudMatch$finalTag" else hubcloudMatch
            loadExtractor(forwardUrl, subtitleCallback, callback)
        }
    }
}

class HubdrivePicsExtractor : HubdriveExtractor() {
    override val mainUrl = "https://hubdrive.pics"
}

class HubdriveLolExtractor : HubdriveExtractor() {
    override val mainUrl = "https://hubdrive.lol"
}

class HubdriveMeExtractor : HubdriveExtractor() {
    override val mainUrl = "https://hubdrive.me"
}

class HubdriveProExtractor : HubdriveExtractor() {
    override val mainUrl = "https://hubdrive.pro"
}

class HubdriveInExtractor : HubdriveExtractor() {
    override val mainUrl = "https://hubdrive.in"
}

class HubdriveClubExtractor : HubdriveExtractor() {
    override val mainUrl = "https://hubdrive.club"
}

open class HubcloudExtractor : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.ist"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val driveDoc = runCatching {
            Jsoup.parse(app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT)).text)
        }.getOrNull() ?: return

        val phpBtn = driveDoc.selectFirst("a[href*='hubcloud.php']")?.attr("href") ?: return
        val phpDoc = runCatching {
            Jsoup.parse(
                app.get(
                    phpBtn,
                    headers = mapOf(
                        "User-Agent" to VCloudExtractor.USER_AGENT,
                        "Referer" to url
                    )
                ).text
            )
        }.getOrNull() ?: return

        val detectedQuality = extractResolution(
            url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
            phpDoc.select(".card-title, .card-header, h5, h4").firstOrNull()?.text(),
            driveDoc.select(".card-title, .card-header, h5, h4").firstOrNull()?.text(),
            phpBtn,
            url
        )
        val detectedAudio = extractAudioTag(
            url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
            phpDoc.select(".card-title, .card-header, h5, h4").firstOrNull()?.text(),
            driveDoc.select(".card-title, .card-header, h5, h4").firstOrNull()?.text(),
            phpBtn,
            url
        )

        phpDoc.select("a.btn, a[href*='download'], div.card-body a").forEach { btn ->
            val href = btn.attr("href").trim()
            val linkQ = extractResolution(
                url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
                href,
                btn.text(),
                btn.parent()?.text(),
                formatQualityTag(detectedQuality)
            )
            val finalQ = if (linkQ != Qualities.Unknown.value) linkQ else detectedQuality
            val qTag = formatQualityTag(finalQ)
            val displayQ = if (qTag.isNotBlank()) " $qTag" else ""

            val linkAudio = extractAudioTag(btn.text(), btn.parent()?.text(), href, detectedAudio)
            val audioTag = if (linkAudio.isNotBlank()) " $linkAudio" else if (detectedAudio.isNotBlank()) " $detectedAudio" else ""

            when {
                href.contains("cloudflarestorage.com") || href.contains(".r2.dev") -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - [Fast CDN : Cloudflare R2 ⚡]$displayQ$audioTag",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = ""
                            this.quality = finalQ
                        }
                    )
                }
                href.contains("pixel.hubcloud") -> {
                    val directParam = Regex("""[?&]link=(https?://[^"'\s&]+)""").find(href)?.groupValues?.get(1)
                    if (!directParam.isNullOrBlank() && !directParam.contains(".zip", ignoreCase = true)) {
                        val dAudio = extractAudioTag(directParam, audioTag)
                        val pixelAudio = if (dAudio.isNotBlank()) " $dAudio" else audioTag
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name - [10Gbps PixelServer ⚡]$displayQ$pixelAudio",
                                url = directParam,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = phpBtn
                                this.quality = finalQ
                            }
                        )
                    } else {
                        // PixelServer is an ad-gate landing page if no link parameter is present.
                        // Resolve the underlying direct download link safely without crashing ExoPlayer:
                        runCatching {
                            val pixelRes = app.get(href, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to phpBtn))
                            val resolved = Regex("""[?&]link=(https?://[^"'\s&]+)""").find(pixelRes.url)?.groupValues?.get(1)
                                ?: Regex("""id=["']downloadBtn["'][^>]*href=["'](https?://[^"']+)["']""").find(pixelRes.text)?.groupValues?.get(1)
                                ?: Regex("""(?:window\.location|location\.href)\s*=\s*["'](https?://[^"']+)["']""").find(pixelRes.text)?.groupValues?.get(1)
                            if (!resolved.isNullOrBlank() && !resolved.contains(".zip", ignoreCase = true) && resolved.startsWith("http")) {
                                val rAudio = extractAudioTag(resolved, audioTag)
                                val resolvedAudio = if (rAudio.isNotBlank()) " $rAudio" else audioTag
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name - [10Gbps PixelServer ⚡]$displayQ$resolvedAudio",
                                        url = resolved,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = phpBtn
                                        this.quality = finalQ
                                    }
                                )
                            }
                        }
                    }
                }
                href.contains("pixeldrain") -> {
                    runCatching {
                        loadExtractor(href, subtitleCallback, callback)
                    }
                }
                href.contains("fuckingfast.net") -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - [BuzzServer Fast]$displayQ$audioTag",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = ""
                            this.quality = finalQ
                        }
                    )
                }
                href.contains("hbplay.pages.dev") && href.contains("?u=") -> {
                    val encoded = href.substringAfter("?u=").substringBefore("&")
                    val decoded = runCatching { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
                    if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name - [WebPlayer CDN ⚡]$displayQ$audioTag",
                                url = decoded,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = ""
                                this.quality = finalQ
                            }
                        )
                    }
                }
                (href.contains("workers.dev") || href.contains("cloudserver")) && !href.contains("gpdl.") -> {
                    val isZip = href.endsWith(".zip", ignoreCase = true) || href.contains(".zip?", ignoreCase = true) ||
                                href.contains(".mkv.zip", ignoreCase = true) || href.contains(".mp4.zip", ignoreCase = true) ||
                                href.contains(".rar", ignoreCase = true) || href.contains(".7z", ignoreCase = true)
                    if (!isZip) {
                        val wAudio = extractAudioTag(href, audioTag)
                        val workerAudio = if (wAudio.isNotBlank()) " $wAudio" else audioTag
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name - [ZipDisk Server ⚡]$displayQ$workerAudio",
                                url = href,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = ""
                                this.quality = finalQ
                            }
                        )
                    }
                }
                href.contains("gpdl.") || btn.text().contains("10Gbps", ignoreCase = true) -> {
                    // Google Drive 10Gbps links are download-only gates (quota limits/browser interstitials, not streamable)
                    // Discard to ensure only genuine, seekable media streams are dispatched to ExoPlayer
                }
            }
        }
    }
}

class HubcloudCxExtractor : HubcloudExtractor() {
    override val mainUrl = "https://hubcloud.cx"
}

class HubcloudClubExtractor : HubcloudExtractor() {
    override val mainUrl = "https://hubcloud.club"
}

class HubcloudLolExtractor : HubcloudExtractor() {
    override val mainUrl = "https://hubcloud.lol"
}

class HubcloudPicsExtractor : HubcloudExtractor() {
    override val mainUrl = "https://hubcloud.pics"
}

class HubcloudBarExtractor : HubcloudExtractor() {
    override val mainUrl = "https://hubcloud.bar"
}

open class CineverseExtractor : ExtractorApi() {
    override val name = "Cineverse"
    override val mainUrl = "https://rozgarlelo.modiplay.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("Referer" to (referer ?: "https://m1.multimovies.courses/"))).text
        }.getOrNull() ?: return

        // 1. Direct Minochinos embed detection
        val minoUrl = Regex("""https?://minochinos\.[a-z]+/embed/[a-zA-Z0-9]+""").find(html)?.value
        if (!minoUrl.isNullOrBlank()) {
            runCatching {
                loadExtractor(minoUrl, url, subtitleCallback, callback)
            }
        }

        // 2. Proxy or direct player resolution
        val iframeSrc = Regex("""<iframe[^>]*src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        val targetUrl = if (!iframeSrc.isNullOrBlank()) {
            if (iframeSrc.startsWith("http")) iframeSrc else "https://rozgarlelo.modiplay.xyz$iframeSrc"
        } else url

        val proxyHtml = if (targetUrl != url) {
            runCatching {
                app.get(targetUrl, headers = mapOf("Referer" to url)).text
            }.getOrNull() ?: html
        } else html

        val embedUrl = Regex("""EMBED_URL\s*=\s*['"]([^'"]+)['"]""").find(proxyHtml)?.groupValues?.get(1)
            ?: Regex("""https?://hanerix\.[a-z]+/e/[a-zA-Z0-9]+""").find(proxyHtml)?.value
            ?: Regex("""https?://vidara\.[a-z]+/e/[a-zA-Z0-9]+""").find(proxyHtml)?.value

        if (!embedUrl.isNullOrBlank()) {
            runCatching {
                loadExtractor(embedUrl, targetUrl, subtitleCallback, callback)
            }
        }
    }
}

open class FilmuExtractor : ExtractorApi() {
    override val name = "Filmu"
    override val mainUrl = "https://embed.filmu.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("Referer" to (referer ?: "https://m1.multimovies.courses/"))).text
        }.getOrNull() ?: return

        val rawM3u8 = Regex("""https?://[^\s"'<>\)\\]+\.m3u8[^\s"'<>\)\\]*""").find(html)?.value
        val m3u8Url = rawM3u8?.replace("\\", "")?.trimEnd('/', ' ')
        val detectedQ = extractResolution(
            url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
            m3u8Url
        )
        val qTag = formatQualityTag(detectedQ)
        val displayQ = if (qTag.isNotBlank()) " $qTag" else ""
        if (!m3u8Url.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - HLS [MultiMovies ⚡]$displayQ",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://embed.filmu.in/"
                    this.quality = detectedQ
                }
            )
        }
    }
}

// ─── FuckingFast (ffast_res) Extractor ───────────────────────────────────────

open class FuckingfastExtractor : ExtractorApi() {
    override val name = "FuckingFast"
    override val mainUrl = "https://fuckingfast.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: mainUrl))).text
        }.getOrNull() ?: return

        // Direct download URL embedded in page or button href
        val directMatch = Regex("""href=["'](https?://[^"']+(?:\.mp4|\.mkv|\.m3u8)[^"']*)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""window\.location\.href\s*=\s*["'](https?://[^"']+)["']""").find(html)?.groupValues?.get(1)
        val detectedQ = extractResolution(
            url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
            directMatch
        )
        val qTag = formatQualityTag(detectedQ)
        val displayQ = if (qTag.isNotBlank()) " $qTag" else ""
        if (!directMatch.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - Fast CDN [⚡]$displayQ",
                    url = directMatch,
                    type = if (directMatch.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = detectedQ
                }
            )
        }
    }
}

// ─── Katdrive Extractor ───────────────────────────────────────────────────────

open class KatdriveExtractor : ExtractorApi() {
    override val name = "Katdrive"
    override val mainUrl = "https://katdrive.click"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = Regex("""/(?:file|stream)/([0-9a-zA-Z]+)""").find(url)?.groupValues?.get(1) ?: return
        val playUrl = "https://katdrive.click/play.php?id=$fileId"
        val playHtml = runCatching {
            app.get(playUrl, headers = mapOf("Referer" to "https://katdrive.click/stream/$fileId")).text
        }.getOrNull() ?: return

        val rawM3u8 = Regex("""["']?(?:file|src)["']?\s*:\s*["'](https?://[^"'\\]+\.m3u8[^"'\\]*)["']""").find(playHtml)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s"'<>\)\\]+\.m3u8[^\s"'<>\)\\]*""").find(playHtml)?.value
        val m3u8Url = rawM3u8?.replace("\\", "")?.trimEnd('/', ' ')

        val detectedQ = extractResolution(
            url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
            m3u8Url
        )
        val detectedAudio = extractAudioTag(
            url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
            m3u8Url,
            url
        )
        val qTag = formatQualityTag(detectedQ)
        val displayQ = if (qTag.isNotBlank()) " $qTag" else ""
        val audioTag = if (detectedAudio.isNotBlank()) " $detectedAudio" else ""

        if (!m3u8Url.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - HLS [KatMovieHD ⚡]$displayQ$audioTag",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://katdrive.click/"
                    this.quality = detectedQ
                }
            )
        }
    }
}

// ─── Greenmotors Extractor ───────────────────────────────────────────────────

data class GreenmotorsPayload(
    val o: String? = null,
    val l: String? = null,
    val w: Int? = null
)

open class GreenmotorsExtractor : ExtractorApi() {
    override val name = "Greenmotors"
    override val mainUrl = "https://greenmotors.cc"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: "https://new8.hdhub4u.cl/"))).text
        }.getOrNull() ?: return

        // Extract inline s('o', '<ENCRYPTED_STRING>', 180*1000);
        val match = Regex("""s\(['"]o['"],\s*['"]([A-Za-z0-9+/=]+)['"]""").find(html)?.groupValues?.get(1) ?: return
        val s1 = runCatching { String(Base64.decode(match, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return
        val s2 = runCatching { String(Base64.decode(s1, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return
        val s3 = rot13(s2)
        val s4 = runCatching { String(Base64.decode(s3, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return
        val json = runCatching { parseJson<GreenmotorsPayload>(s4) }.getOrNull() ?: return
        val encO = json.o ?: return
        val targetUrl = runCatching { String(Base64.decode(encO, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return

        val detectedQuality = extractResolution(url.substringAfter("#", ""), targetUrl, url)
        val qTag = formatQualityTag(detectedQuality)
        val displayQ = if (qTag.isNotBlank()) " $qTag" else ""

        val detectedAudio = extractAudioTag(url.substringAfter("#", ""), targetUrl, url)
        val audioTag = if (detectedAudio.isNotBlank()) " $detectedAudio" else ""

        if (targetUrl.startsWith("http")) {
            val isZip = targetUrl.endsWith(".zip", ignoreCase = true) || targetUrl.contains(".zip?", ignoreCase = true) ||
                        targetUrl.contains(".mkv.zip", ignoreCase = true) || targetUrl.contains(".rar", ignoreCase = true)
            if (!isZip && (targetUrl.endsWith(".mkv") || targetUrl.endsWith(".mp4") || (targetUrl.contains("workers.dev") && !targetUrl.contains(".zip")))) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - Direct Stream [⚡]$displayQ$audioTag",
                        url = targetUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = ""
                        this.quality = detectedQuality
                    }
                )
            } else if (!isZip) {
                val hashIdx = url.indexOf('#')
                val hashTag = if (hashIdx != -1) url.substring(hashIdx) else ""
                val forwardUrl = if (hashTag.isNotEmpty() && !targetUrl.contains("#")) "$targetUrl$hashTag" else targetUrl
                loadExtractor(forwardUrl, url, subtitleCallback, callback)
            }
        }
    }

    private fun rot13(str: String): String {
        return str.map { c ->
            when (c) {
                in 'a'..'z' -> if (c <= 'm') c + 13 else c - 13
                in 'A'..'Z' -> if (c <= 'M') c + 13 else c - 13
                else -> c
            }
        }.joinToString("")
    }
}

class GreenmotorsClubExtractor : GreenmotorsExtractor() {
    override val mainUrl = "https://greenmotors.club"
}

// ─── HBLinks Extractor ───────────────────────────────────────────────────────

open class HblinksExtractor : ExtractorApi() {
    override val name = "HBLinks"
    override val mainUrl = "https://hblinks.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: mainUrl))).text
        }.getOrNull() ?: return

        val hashIdx = url.indexOf('#')
        val hashTag = if (hashIdx != -1) url.substring(hashIdx) else ""

        val doc = Jsoup.parse(html)
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http") && !href.contains("hblinks.lol/category") && !href.contains("wp-admin")) {
                val forwardUrl = if (hashTag.isNotEmpty() && !href.contains("#")) "$href$hashTag" else href
                when {
                    href.contains("hubcloud.") || href.contains("hubdrive.") || href.contains("hubcdn.") ||
                    href.contains("pixeldrain.") || href.contains("fastdl.") || href.contains("fuckingfast.") ||
                    href.contains("gdtot.") || href.contains(".workers.dev") -> {
                        loadExtractor(forwardUrl, url, subtitleCallback, callback)
                    }
                }
            }
        }
    }
}

// ─── HubCDN Extractor ────────────────────────────────────────────────────────

class HubcdnWikiExtractor : HubcdnExtractor() {
    override val mainUrl = "https://hubcdn.wiki"
}

open class HubcdnExtractor : ExtractorApi() {
    override val name = "HubCDN"
    override val mainUrl = "https://hubcdn.club"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: "https://hblinks.lol/"))).text
        }.getOrNull() ?: return

        val reurlParam = Regex("""[?&]r=([A-Za-z0-9+/=]+)""").find(html)?.groupValues?.get(1)
        val decodedParam = if (!reurlParam.isNullOrBlank()) {
            runCatching { String(Base64.decode(reurlParam, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        } else null

        val streamUrl = (decodedParam ?: html).let { text ->
            Regex("""[?&]link=(https?://[^"'\s&]+)""").find(text)?.groupValues?.get(1)
                ?: Regex("""https?://[a-zA-Z0-9.-]+\.r2\.dev/[a-zA-Z0-9]+""").find(text)?.value
                ?: Regex("""https?://[a-zA-Z0-9.-]+\.cloudflarestorage\.com/[^\s"'<>]+""").find(text)?.value
        }

        val detectedQuality = extractResolution(
            url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
            streamUrl,
            decodedParam,
            url
        )
        val detectedAudio = extractAudioTag(
            url.substringAfter("#", "").takeIf { it.isNotBlank() && it != url },
            streamUrl,
            decodedParam,
            url
        )
        val qTag = formatQualityTag(detectedQuality)
        val displayQ = if (qTag.isNotBlank()) " $qTag" else ""
        val audioTag = if (detectedAudio.isNotBlank()) " $detectedAudio" else ""

        if (!streamUrl.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - Direct [R2 Dev ⚡]$displayQ$audioTag",
                    url = streamUrl,
                    type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = ""
                    this.quality = detectedQuality
                }
            )
        }
    }
}

// ─── Vidbolt Direct Resolver & Extractor ──────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidboltResponse(
    @JsonProperty("stream") val stream: VidboltStream? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidboltStream(
    @JsonProperty("server") val server: String? = null,
    @JsonProperty("sources") val sources: List<VidboltSource>? = null,
    @JsonProperty("tracks") val tracks: List<VidboltTrack>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidboltSource(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("language") val language: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidboltTrack(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("lang") val lang: String? = null
)

open class VidboltExtractor : ExtractorApi() {
    override val name = "Vidbolt"
    override val mainUrl = "https://vidbolt.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        val isMovie = !cleanUrl.contains("/tv/")
        val id = if (isMovie) {
            cleanUrl.substringAfter("/movie/").substringBefore("?").substringBefore("/")
        } else {
            cleanUrl.substringAfter("/tv/").substringBefore("?").substringBefore("/")
        }
        val season = if (!isMovie) {
            cleanUrl.substringAfter("/tv/$id/").substringBefore("/").toIntOrNull() ?: 1
        } else 1
        val episode = if (!isMovie) {
            cleanUrl.substringAfter("/tv/$id/$season/").substringBefore("?").substringBefore("/").toIntOrNull() ?: 1
        } else 1

        val servers = listOf("orion", "callisto", "fastva", "vega", "quantum", "quasar")
        val baseApi = "$mainUrl/api/resolve?type=${if (isMovie) "movie" else "tv"}&id=$id${if (!isMovie) "&season=$season&episode=$episode" else ""}"

        coroutineScope {
            val jobs = servers.map { srv ->
                async {
                    runCatching {
                        val apiUrl = "$baseApi&only=$srv"
                        val json = app.get(
                            apiUrl,
                            headers = mapOf(
                                "User-Agent" to VCloudExtractor.USER_AGENT,
                                "Referer" to cleanUrl
                            ),
                            timeout = 6
                        ).parsedSafe<VidboltResponse>()

                        val stream = json?.stream ?: return@runCatching
                        val srvName = stream.server?.replaceFirstChar { it.uppercase() } ?: srv.replaceFirstChar { it.uppercase() }

                        val hasHls = stream.sources?.any { it.kind?.lowercase() == "hls" || it.url?.contains(".m3u8") == true } == true

                        stream.sources?.forEach { src ->
                            val sUrl = src.url?.trim() ?: return@forEach
                            val qLabel = src.quality ?: "Auto"
                            val isHls = src.kind?.lowercase() == "hls" || sUrl.contains(".m3u8")
                            val rawLang = src.language?.trim() ?: ""
                            val lowerLang = rawLang.lowercase()

                            // If this server already provides an adaptive multi-audio HLS stream,
                            // skip single-language foreign dubbed MP4s (e.g. Russian sub, Arabic sub, Italian, Spanish, Portuguese)
                            // Only include HLS or explicit Hindi/English/Dual-Audio streams
                            if (hasHls && !isHls) {
                                val isPreferredAudio = lowerLang.contains("hindi") || lowerLang.contains("english") ||
                                                       lowerLang.contains("orig") || lowerLang.contains("dual") ||
                                                       lowerLang.contains("multi")
                                if (!isPreferredAudio) return@forEach
                            }

                            val qInt = parseStreamQuality(qLabel)
                            val audioLabel = when {
                                isHls -> "[Multi-Audio ⚡ Direct]"
                                rawLang.isNotBlank() -> "[$rawLang]"
                                else -> "[Direct]"
                            }

                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "$name [$srvName] - $qLabel $audioLabel",
                                    url = sUrl,
                                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.headers = mapOf(
                                        "User-Agent" to VCloudExtractor.USER_AGENT,
                                        "Referer" to "$mainUrl/"
                                    )
                                    this.quality = qInt
                                }
                            )
                        }

                        stream.tracks?.forEach { trk ->
                            val trkUrl = trk.url?.trim() ?: return@forEach
                            val lang = trk.label ?: trk.lang ?: "English"
                            subtitleCallback(
                                newSubtitleFile(
                                    lang = lang,
                                    url = trkUrl
                                )
                            )
                        }
                    }
                }
            }
            jobs.forEach { it.await() }
        }
    }
}

// ─── Vidrift Multi-Audio Master HLS Extractor ─────────────────────────────────

open class VidriftExtractor : ExtractorApi() {
    override val name = "Vidrift"
    override val mainUrl = "https://embed.vidrift.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to VCloudExtractor.USER_AGENT,
                    "Referer" to (referer ?: "https://m1.multimovies.courses/")
                ),
                timeout = 7
            ).text
        }.getOrNull() ?: return

        // 1. Extract stream sources from embedded JSON
        val matches = Regex("""\{"index":\d+,"url":"([^"]+)".*?"name":"([^"]+)"""").findAll(html)
        matches.forEach { m ->
            val relUrl = m.groupValues[1]
            val streamName = m.groupValues[2]
            val fullUrl = if (relUrl.startsWith("http")) relUrl else "https://embed.vidrift.net$relUrl"
            val isHls = fullUrl.contains(".m3u8")
            val qualityInt = when {
                streamName.contains("1080") -> Qualities.P1080.value
                streamName.contains("720") -> Qualities.P720.value
                streamName.contains("480") -> Qualities.P480.value
                else -> Qualities.P1080.value
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - $streamName [⚡ Multi-Audio]",
                    url = fullUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://embed.vidrift.net/"
                    this.headers = mapOf(
                        "User-Agent" to VCloudExtractor.USER_AGENT,
                        "Referer" to "https://embed.vidrift.net/"
                    )
                    this.quality = qualityInt
                }
            )
        }

        // 2. Extract subtitles from subtitleTracks
        val subMatches = Regex("""\{"code":"[^"]*","label":"([^"]+)","lang":"[^"]*","url":"([^"]+)"""").findAll(html)
        subMatches.forEach { sm ->
            val sLabel = sm.groupValues[1]
            val sPath = sm.groupValues[2]
            val sUrl = if (sPath.startsWith("http")) sPath else "https://embed.vidrift.net$sPath"
            subtitleCallback(
                newSubtitleFile(
                    lang = sLabel,
                    url = sUrl
                )
            )
        }
    }
}

// ─── Hanerix & Minochinos Packed Extractors ───────────────────────────────────

open class HanerixExtractor : ExtractorApi() {
    override val name = "Hanerix"
    override val mainUrl = "https://hanerix.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to VCloudExtractor.USER_AGENT,
                    "Referer" to (referer ?: "https://rozgarlelo.modiplay.xyz/")
                ),
                timeout = 7
            ).text
        }.getOrNull() ?: return

        val unpacked = unpackDeanEdwards(html)
        val m3u8Match = Regex("""https?://[^\s"'<>\)\\]+\.m3u8[^\s"'<>\)\\]*""").find(unpacked)?.value
        if (!m3u8Match.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - Direct HLS ⚡",
                    url = m3u8Match,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }
}

open class MinochinosExtractor : ExtractorApi() {
    override val name = "Minochinos"
    override val mainUrl = "https://minochinos.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to VCloudExtractor.USER_AGENT,
                    "Referer" to (referer ?: "https://rozgarlelo.modiplay.xyz/")
                ),
                timeout = 7
            ).text
        }.getOrNull() ?: return

        val unpacked = unpackDeanEdwards(html)
        val m3u8Match = Regex("""https?://[^\s"'<>\)\\]+\.m3u8[^\s"'<>\)\\]*""").find(unpacked)?.value
        if (!m3u8Match.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - Direct HLS ⚡",
                    url = m3u8Match,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }
}

