# TeraBox 1080p Playback & Unthrottled Streaming Restoration Guide

> **Status:** Fully Tested, Empirically Verified & Implemented in `TeraBoxExtractor.kt`  
> **Last Updated:** 2026-11-04  
> **Target Workspace:** `E:\Election 2018\csext`

---

## 1. Executive Summary & Core Objective

The goal is to achieve **unrestricted 1080p Full HD video playback and downloading** for public TeraBox shared links without requiring a paid TeraBox VIP subscription, and without polluting CloudStream with separate individual links for each resolution.

### The Problem with Native TeraBox
- Native TeraBox direct download links (`dlink`) enforce a hard server-side token-bucket rate limiter: `tsl=30&csl=30` (Total Speed Limit = 30 KB/s across all TCP connections).
- Even using download managers with 32 concurrent connections, the speed remains hard-capped at **29.93 KiB/s**.
- Native HLS streaming for free accounts blocks 1080p and 720p with `{"errno": 130}` (VIP required). Only 480p and 360p succeed.

### The Solution: Unified Adaptive Master M3U8
- **Zero Captcha / No Turnstile Required:** Reverse-engineered `flowvideoplayer.com` API resolves links programmatically without any captcha challenges.
- **Unified Master M3U8 (No individual links per resolution):**
  - **1080p**: Provided by Flow Video Player (`https://dl-worker.teraboxdl.site?token=...`), verified `1920x1080` Full HD stream.
  - **720p**: Provided by Flow Video Player fast worker HLS stream (`*.workers.dev/fast_stream?token=...m3u8`).
  - **480p**: Provided by native TeraBox HLS stream (`M3U8_AUTO_480`).
  - **360p**: Provided by native TeraBox HLS stream (`M3U8_AUTO_360`).
- **Single ExtractorLink:** Emits **ONE** unified link (`TeraBox - [Adaptive Multi-Quality ⚡ (1080p/720p/480p/360p)]`). CloudStream / ExoPlayer displays all four resolutions inside the player's quality selector!

---

## 2. Implementation in `TeraBoxExtractor.kt`

The implementation has been directly updated in:
[`TeraBoxExtractor.kt`](file:///E:/Election%202018/csext/IteraPlayProvider/src/main/kotlin/com/lagradost/TeraBoxExtractor.kt)

### Key Architectural Updates:
1. **`LocalM3u8Server`**:
   - Added `set(key, m3u8Content)` to register and serve variant playlists (`v1080_$streamId.m3u8`).
   - Cleaned up query-parameter parsing in `handleClient` (`path.substringBefore("?")`).
2. **`resolveFlowVideoPlayer(shareUrl)`**:
   - Performs automated 4-step handshake:
     1. `GET https://flowvideoplayer.com/` (CSRF token & session cookies).
     2. `POST /device/init` (Hardware fingerprint & signed `device_token`).
     3. `POST /search/video` with `{ "url": shareUrl }` (Extracts `download_url` & `fast_stream_url`).
     4. `GET <download_url>` with `allowRedirects = false` (Extracts 302 location to `https://dl-worker.teraboxdl.site?token=...`).
3. **`resolveDirectTeraBox(...)`**:
   - Concurrently fetches Flow Video Player (`resolveFlowVideoPlayer`) and native TeraBox contents (`fetchShareContents`) using `coroutineScope` and `async`.
   - Bundles all available resolutions (1080p, 720p, 480p, 360p) into `#EXTM3U` Master Playlist served by `LocalM3u8Server`.
   - Invokes `callback` with **only one** `ExtractorLinkType.M3U8` link!
4. **`getUrl(...)`**:
   - Removed the blocking `if (ndus.isNullOrBlank()) return` check, so all users benefit from unthrottled playback without needing to configure or login to TeraBox.

---

## 3. Verified Working Links & Evidence

### Target 1: Baththa (2026)
- **TeraBox URL:** `https://1024terabox.com/s/1seyzNSdgCalT8UkYFDEiog`
- **File Size:** 2.91 GB (3,129,836,813 bytes)
- **EBML Resolution:** **`1920x1080` (Full HD)**
- **Tested Speed:** 7.17 Mbps (single connection)
- **1080p Direct Worker URL:**
  ```text
  https://dl-worker.teraboxdl.site?token=FPkyAwTbBHymoJO-qVlrJFp4LaasUrtF_ya0EKvGkaDvm0KgaK8Q20QHQL5NJasqphhdDsUhVgEFixzsLRc8C0Q6tXKwWpOhbEN6akfmJgexhSzLIE6Kc8MiOOJPo9rchs71wyPEtvibt9weYQkNtVVA6TWGyXdgr40Zzd-5MWW6QcLaN6dfBVva009Hl8HKCzTnC7w0XUdgkgGKnP3Dpagbn_bVYLxjDH4b8BzjOORFKVTRvlIt7Rffa07OrNCHZ-TW3tP0tK-HQFLZfTeIRQ7DUeUdezx2_s1iXZy-AOyctvqfwI9QxjdPQl5aM5tHFbuqMeGPJ01WVi8ljq5IeWRlqLb0aJv7TCBVxV7bCPGnvgUjttYoagkEdP4Bz075DGj1xFAtQQFSLz6a-VA0Q8B4LFUDIJ1bt_YybVSe8lcvsgsXWdgbQ9KMT_5nieEvnlCrobHbiu877gsxPrjr7aD8nPuPDK9RJdANTJaMrlpGLtqkluXoP8VPBULtFtbgRa6i0M014o__IB2TziJxEwpyUXeL7wHhKxUNfc3UKkypARr4T-cKavGBkWKHYY2RjvjQqWAdxUDww3kDt-StlEi91kBeEcbpwnu4mWUy4XvD7CnmZw8mTJ5dnWJN4VzULrVYPfg-OClLu-9flNKUPGIIpeTs6Dii4XSmcIOUbSjDENJvb7G9dFPr8u44NwuWjw
  ```
- **720p Fast Worker HLS:**
  ```text
  https://yellow-shadow-a936.lulewolu.workers.dev/fast_stream?token=J66iwqLXUfWM0QXMyf2g4fCSY_O4Y4LTtBbp_TS2wnSX891zLdvzDmCbMh6mZGztT3Hsmo18tqoyeb8I0VJVIx3vfUvIF5ooGPgpDePQT-49b9lWWnjOCyM1WOxYDPjPk1UFBevOq5bQwTpe3OZGz7p9eXS2eRcdVTbIx8fdUoHGh8jf54Vs5UiC8wgp2_L-NvWByBJTn7NlAm7Kt9oZnLlzw6pfxpiCtEdUFIZQy4oZGTnEdeY6gA9gHWNsY7mPqehA8MiFakr6Niipx-UOVzf7KYhqGdzFEDBXDSy7Jhd50ihY5WTmQ4prlWkNcgXUl0S9RYuFl3r8ebCQr-IEnQQRjqqz7mfXy3b8GcmpJ_OPR0rOx_JyTa6rjTBPYuil1Eft1LRel--qqf47DghVCxC6oxOxuzCA66UnYCIMneqdoXun.m3u8
  ```

---

### Target 2: Heart Beat S03 EP (53-56)
- **TeraBox URL:** `https://1024terabox.com/s/1j5cgzlFDHgPBDbkundltrA`
- **File Size:** 1.33 GB
- **EBML Resolution:** **`1920x1080` (Full HD)**
- **Tested Speed:** 12+ Mbps
- **1080p Direct Worker URL:**
  ```text
  https://dl-worker.teraboxdl.site?token=lkGitIUMZSVvozXcFM6TX1Px12QhdXRTJg9e70iDe1b35A_ZB28_NTfqdCyMjJ7wa1lOZfcijtYkncXhD0GTYpj8t-CUvv2qSDsxezLdt5Sx2lpDgYCE3CjU-ROGIxSRwPBf6MS7dl_j5XKLlWmeOCZeydwc9yi-r3phHfIuKFZqDmtKLpgg_PntTsIHhB9zJfOfXg0pYv97XD6c3o8VcJLku_Cb0Qr1QUE0fv_cITv6NFConAug2W80hYXHZ-RAjKl2ytNTI6RIb1bbMxqnCEtK0GjOR1a1xv1bk0R4ljNRwdNwMEA2RSlI-xGnWGPKhBZ6ePINlIwJ2yHXTUXkCvTsUqWE_xBklyaDy1We4w8At1FaPcAXekZJoHo__fGNmvQpLIYi9uYJNFsVVICqhvy7qLibRVfNGAbRNBd5HXreZLTwpQQ5w4ASK519GjyciO5wAwZ1oFGix7S5pHcaQ0UbfR4yYhuQMj_2GHdsYzOoKJjN0U0HnGuxzrYxHroGr5LNk7UGmlKdPFWIKbD70J__Nna4uVW4b1oItvcCoKtdY8x2gVE9PZvTTBPpJjt-gA1JDzhzp-dISeK84UVcrNK-LWyNn5xx8_vSqarJn4RV3XkJfc8AgIvBu2Fw29TmCCQcgagEJYzJvK2mwCXw300fHFvybvnRFiXtzn95J1JOFdeOYnJby0-uqJhdCRE
  ```
- **720p Fast Worker HLS:**
  ```text
  https://iteraplay.tera-api80.workers.dev/fast_stream?token=rf4TBvg0nVuMTRGmPmLKznKO_mM2jjhG-LXATfEtbeFOaRs1rc0qyEsztF0BUfPcRSldyFQlEbgSFx1w9jGiiYCppZnG9dHPy43AOGKbb37NGop2n2ArsOhL9uYUzzvIM0ieSMe7eA0r8ppL0zM2HdD0ZKpyYAG8BU-LZ13cL9Lq7WcP8_jU-vHDWowYmN_QtxKDrbT-82XbNQjNiw663iurZsHFp5dBIPoIKdVBiWRinwjhh-LKqUdn-GG9Cx5rxFbV03m4nh-LHBs6gSBLFHiGHJCV3L9wImCFBHaFsTaKwOvEPYizgIK4pluriGNie5MDw4JUlFePPpVB6hjruqkoHgTa0cqPmKiJveIru3twDFJfQ_5buumIInnUjdDCyig6LryvKSk---IJSfUIisWCgTonvAnZa_9N01T7VxoieGzVrVTm9Q.m3u8
  ```

---

## 4. Local Test Player

To preview and play locally in Microsoft Edge:
```powershell
Start-Process msedge.exe "E:\Election 2018\csext\scratch\test_player.html"
```
