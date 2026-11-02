const crypto = require("crypto");
const https = require("https");
const fs = require("fs");

function evpKDF(password, salt, keyLen = 32, ivLen = 16) {
    let d = Buffer.alloc(0);
    let totalLen = keyLen + ivLen;
    let prev = Buffer.alloc(0);
    while (d.length < totalLen) {
        let hash = crypto.createHash("md5");
        hash.update(prev);
        hash.update(password);
        hash.update(salt);
        prev = hash.digest();
        d = Buffer.concat([d, prev]);
    }
    return { key: d.subarray(0, keyLen), iv: d.subarray(keyLen, keyLen + ivLen) };
}

function decrypt(cipherBase64, secret) {
    let raw = Buffer.from(cipherBase64, "base64");
    if (raw.subarray(0, 8).toString("utf-8") !== "Salted__") throw new Error("Not OpenSSL");
    let salt = raw.subarray(8, 16);
    let ciphertext = raw.subarray(16);
    let { key, iv } = evpKDF(Buffer.from(secret, "utf-8"), salt, 32, 16);
    let decipher = crypto.createDecipheriv("aes-256-cbc", key, iv);
    return Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString("utf-8");
}

async function getJSON(url, headers = {}) {
    return new Promise((resolve, reject) => {
        https.get(url, { headers: { "User-Agent": "Mozilla/5.0", ...headers } }, (res) => {
            let data = "";
            res.on("data", c => data += c);
            res.on("end", () => {
                try {
                    resolve({ statusCode: res.statusCode, headers: res.headers, data: JSON.parse(data) });
                } catch (e) {
                    resolve({ statusCode: res.statusCode, headers: res.headers, rawText: data, error: e.message });
                }
            });
        }).on("error", reject);
    });
}

async function getText(url, headers = {}) {
    return new Promise((resolve, reject) => {
        https.get(url, { headers: { "User-Agent": "Mozilla/5.0", ...headers } }, (res) => {
            let data = "";
            res.on("data", c => data += c);
            res.on("end", () => resolve({ statusCode: res.statusCode, headers: res.headers, text: data }));
        }).on("error", reject);
    });
}

async function getBuffer(url, headers = {}) {
    return new Promise((resolve, reject) => {
        https.get(url, { headers: { "User-Agent": "Mozilla/5.0", ...headers } }, (res) => {
            let chunks = [];
            res.on("data", c => chunks.push(c));
            res.on("end", () => resolve({ statusCode: res.statusCode, headers: res.headers, buffer: Buffer.concat(chunks) }));
        }).on("error", reject);
    });
}

async function runTestSuite() {
    console.log("==================================================================");
    console.log("      AUTOMATED TEST SUITE: iTeraPlay Provider Verification       ");
    console.log("==================================================================");

    let passed = 0;
    let failed = 0;

    function assert(name, condition, details = "") {
        if (condition) {
            passed++;
            console.log(`  [PASS] ${name} ${details}`);
        } else {
            failed++;
            console.log(`  [FAIL] ${name} ${details}`);
        }
    }

    // 1. Latest Uploads API
    console.log("\n--- TEST 1: Latest Uploads Catalog & AES Decryption ---");
    let res = await getJSON("https://teraviralhub.com/api/home/search?sort=latest&offset=0&limit=10");
    assert("API success flag", res.data?.success === true);
    let decrypted = JSON.parse(decrypt(res.data.data, "iteraplay_super_secret_enc_key_2026_v2"));
    assert("AES Decryption & Catalog Parsing", decrypted.list && decrypted.list.length > 0, `(${decrypted.list.length} items found)`);

    // 2. Thumbnail-to-TeraBox Key Extraction
    console.log("\n--- TEST 2: Thumbnail-to-TeraBox Key Extraction ---");
    let thumbRegex = /thumbnails\/(1[a-zA-Z0-9_\-]{22})/;
    let matchedCount = 0;
    let extractedKeys = [];
    decrypted.list.forEach((item) => {
        let m = (item.thumbnail || "").match(thumbRegex);
        if (m) {
            matchedCount++;
            extractedKeys.push(m[1]);
        }
    });
    assert("100% TeraBox Key Extraction from Thumbnails", matchedCount === decrypted.list.length, `(${matchedCount}/${decrypted.list.length} matched)`);

    // 3. TeraVideo Native HLS Resolver (No Captcha, No 5-Video Limit, Full Duration)
    console.log("\n--- TEST 3: TeraVideo Instant HLS Stream Resolution ---");
    let sampleKey = extractedKeys[0] || "1AJbS72yZ6tw0f4VxkfsKfw";
    let sampleTbUrl = `https://terabox.app/s/${sampleKey}`;
    let resolveEndpoint = `https://teravideo.in/terabox-api/api.php?action=resolve&url=${encodeURIComponent(sampleTbUrl)}`;
    
    let tvRes = await getJSON(resolveEndpoint, {
        "Referer": "https://teravideo.in/terabox-player-online/",
        "Origin": "https://teravideo.in"
    });

    assert("TeraVideo API HTTP 200", tvRes.statusCode === 200);
    assert("TeraVideo Success Response", tvRes.data?.success === true);
    let activeFile = tvRes.data?.data?.active_file || tvRes.data?.data?.file;
    assert("TeraVideo File Resolved", activeFile !== undefined && activeFile !== null, `(${activeFile?.name || "Unknown"})`);
    assert("Full Duration Video (> 60s, Not 10s Preview)", (activeFile?.duration || 0) > 60, `(Duration: ${activeFile?.durationFormatted || activeFile?.duration + "s"})`);
    assert("Adaptive Bitrate masterUrl Present", !!activeFile?.masterUrl);

    if (activeFile?.masterUrl) {
        let masterFull = activeFile.masterUrl.startsWith("http") ? activeFile.masterUrl : `https://teravideo.in/terabox-api/${activeFile.masterUrl}`;
        let masterM3u8 = await getText(masterFull, { "Referer": "https://teravideo.in/terabox-player-online/" });
        assert("Master M3U8 Playlist Fetched", masterM3u8.statusCode === 200 && masterM3u8.text.includes("#EXTM3U"));

        let lines = masterM3u8.text.split("\n");
        let subPlaylistRel = lines.find(l => l.includes("action=m3u8"));
        assert("Master Contains Sub-Playlists (480p/360p)", !!subPlaylistRel);

        if (subPlaylistRel) {
            let subFull = `https://teravideo.in/terabox-api/${subPlaylistRel.trim()}`;
            let subM3u8 = await getText(subFull, { "Referer": "https://teravideo.in/terabox-player-online/" });
            assert("Sub M3U8 Playlist Fetched", subM3u8.statusCode === 200 && subM3u8.text.includes("#EXTINF"));

            let segLines = subM3u8.text.split("\n");
            let segCount = segLines.filter(l => l.startsWith("#EXTINF")).length;
            assert("Full VOD Playlist Segment Count (> 10 segments)", segCount > 10, `(${segCount} segments found)`);

            let segRel = segLines.find(l => l.includes("action=segment"));
            if (segRel) {
                let segFull = `https://teravideo.in/terabox-api/${segRel.trim()}`;
                let segProbe = await getBuffer(segFull, { "Referer": "https://teravideo.in/terabox-player-online/" });
                assert("Segment HTTP 200 OK", segProbe.statusCode === 200);
                assert("Segment Content-Type is video/mp2t", (segProbe.headers["content-type"] || "").includes("mp2t"));
                assert("Valid MPEG-TS Sync Byte (0x47)", segProbe.buffer.length > 0 && segProbe.buffer[0] === 0x47, `(Length: ${segProbe.buffer.length} bytes)`);
            }
        }
    }

    // 4. LinkPlay 1080p Contract Verification
    console.log("\n--- TEST 4: LinkPlay 1080p MP4 Architecture Verification ---");
    let linkplayRecordPath = "C:/Users/Anadi/Downloads/returnpilot_v6_5_records_linkplay_in_1793634581555.json";
    if (fs.existsSync(linkplayRecordPath)) {
        let rec = JSON.parse(fs.readFileSync(linkplayRecordPath, "utf8"));
        let apiCall = rec.normalizedReport?.apiCalls?.find(a => a.cleanUrl === "/api/fetch-video");
        let video = apiCall?.responseParsed?.video;
        assert("LinkPlay Evidence Extracted", video !== undefined);
        assert("LinkPlay Full File Size (> 700MB)", parseInt(video?.size || "0") > 700 * 1024 * 1024, `(${(parseInt(video?.size || 0)/(1024*1024)).toFixed(2)} MB)`);
        assert("LinkPlay Direct Stream URL Present", !!video?.secure_stream);
    }

    console.log("\n==================================================================");
    console.log(`VERIFICATION SUMMARY: ${passed} PASSED, ${failed} FAILED`);
    console.log("==================================================================");
}

runTestSuite();
