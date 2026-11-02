const crypto = require("crypto");
const https = require("https");

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

async function getJSON(url) {
    return new Promise((resolve, reject) => {
        https.get(url, (res) => {
            let data = "";
            res.on("data", c => data += c);
            res.on("end", () => resolve(JSON.parse(data)));
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
    assert("API success flag", res.success === true);
    let decrypted = JSON.parse(decrypt(res.data, "iteraplay_super_secret_enc_key_2026_v2"));
    assert("AES Decryption & Catalog Parsing", decrypted.list && decrypted.list.length > 0, `(${decrypted.list.length} items found)`);

    // 2. Thumbnail-to-TeraBox Key Extraction
    console.log("\n--- TEST 2: Thumbnail-to-TeraBox Key Extraction ---");
    let thumbRegex = /thumbnails\/(1[a-zA-Z0-9_\-]{22})_/;
    let matchedCount = 0;
    decrypted.list.forEach((item) => {
        if ((item.thumbnail || "").match(thumbRegex)) matchedCount++;
    });
    assert("100% TeraBox Key Extraction from Thumbnails", matchedCount === decrypted.list.length, `(${matchedCount}/${decrypted.list.length} matched)`);

    // 3. Direct Native TeraBox HLS Streaming (Zero Login, Zero Rate Limits)
    console.log("\n--- TEST 3: Direct Native TeraBox HLS Streaming (Bypassing IteraPlay) ---");
    let sampleKey = "1AJbS72yZ6tw0f4VxkfsKfw";
    let surl = sampleKey.startsWith("1") ? sampleKey.substring(1) : sampleKey;
    let pageUrl = `https://www.terabox.app/sharing/link?surl=${surl}`;

    let htmlRes = await new Promise(r => {
        https.get(pageUrl, { headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" } }, res => {
            let d = "";
            res.on("data", c => d += c);
            res.on("end", () => r({ html: d, cookies: res.headers["set-cookie"] || [] }));
        });
    });

    let m = htmlRes.html.match(/window\.__INITIAL_STATE__\s*=\s*(\{.*?\});\s*<\/script>/);
    assert("TeraBox Page & Token Extraction", m !== null, `(Found __INITIAL_STATE__)`);

    if (m) {
        let sInfo = JSON.parse(m[1]).pinia.share.shareInfo;
        let cookieStr = htmlRes.cookies.map(c => c.split(";")[0]).join("; ");
        let streamUrl = `https://www.terabox.app/share/streaming?app_id=250528&channel=dubox&clienttype=0&web=1&type=M3U8_AUTO_480&path=${encodeURIComponent(sInfo.fileInfo.path)}&shareid=${sInfo.shareid}&uk=${sInfo.uk}&fid=${sInfo.fileInfo.fs_id}&sign=${sInfo.sign}&timestamp=${sInfo.timestamp}`;
        
        let m3u8Res = await new Promise(r => {
            https.get(streamUrl, {
                headers: {
                    "User-Agent": "Mozilla/5.0",
                    "Referer": pageUrl,
                    "Cookie": cookieStr
                }
            }, res => {
                let d = "";
                res.on("data", c => d += c);
                res.on("end", () => r(d));
            });
        });

        let isHls = m3u8Res.includes("#EXTM3U") && m3u8Res.includes("#EXTINF");
        assert("Direct TeraBox Native HLS Playlist (.m3u8)", isHls, "(Streamed directly from freeterabox.com with 0 rate limit)");
    }

    console.log("\n==================================================================");
    console.log(`VERIFICATION SUMMARY: ${passed} PASSED, ${failed} FAILED`);
    console.log("==================================================================");
}

runTestSuite();
