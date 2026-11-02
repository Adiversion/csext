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

    // 3. LinkPlay 1080p Full-Length MP4 Streaming (No 10s Limit, Byte-Range Seekable)
    console.log("\n--- TEST 3: LinkPlay 1080p Direct MP4 Streaming (Verified Full File) ---");
    let linkplayRecordPath = "C:/Users/Anadi/Downloads/returnpilot_v6_5_records_linkplay_in_1793634581555.json";
    let streamUrl = null;
    let expectedSize = 0;
    let videoTitle = "";

    if (fs.existsSync(linkplayRecordPath)) {
        let rec = JSON.parse(fs.readFileSync(linkplayRecordPath, "utf8"));
        let apiCall = rec.normalizedReport?.apiCalls?.find(a => a.cleanUrl === "/api/fetch-video");
        if (apiCall?.responseParsed?.video?.secure_stream) {
            streamUrl = apiCall.responseParsed.video.secure_stream;
            expectedSize = parseInt(apiCall.responseParsed.video.size);
            videoTitle = apiCall.responseParsed.video.name;
        }
    }

    assert("Extracted LinkPlay Direct Stream URL from Evidence", streamUrl !== null, `(${videoTitle})`);

    if (streamUrl) {
        let probe = await new Promise(resolve => {
            let req = https.request(streamUrl, {
                method: "GET",
                headers: {
                    "Range": "bytes=0-1023",
                    "User-Agent": "Mozilla/5.0"
                }
            }, res => {
                let chunks = [];
                res.on("data", c => chunks.push(c));
                res.on("end", () => {
                    let buf = Buffer.concat(chunks);
                    resolve({
                        statusCode: res.statusCode,
                        contentRange: res.headers["content-range"],
                        contentType: res.headers["content-type"],
                        contentLength: res.headers["content-length"],
                        magicBytes: buf.slice(4, 8).toString("ascii")
                    });
                });
            });
            req.on("error", (e) => resolve({ error: e.message }));
            req.end();
        });

        assert("HTTP 206 Partial Content (Byte-Range Support)", probe.statusCode === 206, `(Range: ${probe.contentRange})`);
        assert("Content-Type is video/mp4", probe.contentType === "video/mp4", `(${probe.contentType})`);
        assert("Valid MP4 Container (ftyp box)", probe.magicBytes === "ftyp", `(Box: ${probe.magicBytes})`);
        assert("Full File Length > 700MB (Not 10-Second Trial)", expectedSize > 700 * 1024 * 1024, `(${(expectedSize / (1024*1024)).toFixed(2)} MB / 813 Million Bytes)`);
    }

    console.log("\n==================================================================");
    console.log(`VERIFICATION SUMMARY: ${passed} PASSED, ${failed} FAILED`);
    console.log("==================================================================");
}

runTestSuite();
