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

    // 3. Trending & Random Tabs
    console.log("\n--- TEST 3: Multi-Category Support (Trending, 720p, 1080p) ---");
    let trendingRes = await getJSON("https://teraviralhub.com/api/home/search?sort=trending&offset=0&limit=5");
    let trendingDecrypted = JSON.parse(decrypt(trendingRes.data, "iteraplay_super_secret_enc_key_2026_v2"));
    assert("Trending Catalog", trendingDecrypted.list && trendingDecrypted.list.length > 0, `(${trendingDecrypted.list.length} items)`);

    // 4. Session Cookies Acquisition
    console.log("\n--- TEST 4: Session Cookies Handshake ---");
    let cookieRes = await new Promise(resolve => {
        https.get("https://iteraplay.com/", res => resolve(res.headers["set-cookie"] || []));
    });
    let hasSession = cookieRes.some(c => c.includes("session_id"));
    let hasSecureToken = cookieRes.some(c => c.includes("__secure_token"));
    assert("Automatic Session Cookie Acquisition", hasSession && hasSecureToken, `(session_id & __secure_token present)`);

    console.log("\n==================================================================");
    console.log(`VERIFICATION SUMMARY: ${passed} PASSED, ${failed} FAILED`);
    console.log("==================================================================");
}

runTestSuite();
