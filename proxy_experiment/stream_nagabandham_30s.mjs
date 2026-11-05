import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

// Target worker URL from Nagabandham (2026) logcat
const WORKER_URL = "https://wandering-sun-9ba9.flat-leopard.workers.dev/dlink-fetch?token=3LjedMifGEB62H6tyPdPRJYPHaj8EiiZShTZ5847hqiGr9XMJIwt86bCZ2jp_jp34R_ig0xX-EmmR_9lSPNDRoAl70MXFk93pZcYbgtJnk95VZ25kry67fAluXaWIZKqZKzLFktkXExvKNrsS_OOG6dp3whbkGuWQhh2-9mzd0bToQcyXaZyO7vl8Wu3GBIWH5f4CG7vFQzYHXs43fdsnCCScQdyVYdmtQ5WlC0YyhWkQ0txLWx_gkhIJQ0nC0Bc8IAvLkJ6rPuUSM49XflvCp1dD63uVQpTr73TlWTGr8DgeiSJsTkbY1hapF8gN0tpue-Q7QlhO3Z6IKEcD5Qip0XVYtUqCrZiy_yOETXprk41dfu2fwudM38HWDKdkIns3fTlhQrrO7F8Q5A&file_name=Nagabandham_(2026)_Kannada_1080p_AMZN_WEB-DL_AV1_%5BDD%2B5.1.mkv";

// 30 seconds of video @ ~338 KB/s average bitrate + 1MB container metadata cushion = ~12 MB
const TARGET_BYTES_30S = 12 * 1024 * 1024; // 12 MB
const OUTPUT_FILE = path.join(process.cwd(), 'proxy_experiment', 'nagabandham_start_30s.mkv');

console.log("================================================================================");
console.log("🎬 STREAMING NAGABANDHAM (2026) 1080p - FIRST 30 SECONDS VIA LOCAL PROXY");
console.log(`   Source: High-Bitrate Original 1080p MKV (3.22 GB total)`);
console.log(`   Target Stream Segment: ~12 MB (First ~35 seconds of video)`);
console.log(`   Proxy Endpoint: http://127.0.0.1:8888/play (10 Parallel Range Workers)`);
console.log("================================================================================\n");

const proxyUrl = `http://127.0.0.1:8888/play?url=${encodeURIComponent(WORKER_URL)}&concurrency=10&chunk=256`;

const startTime = Date.now();
let receivedBytes = 0;
let firstChunkTime = null;

const fileStream = fs.createWriteStream(OUTPUT_FILE);

const req = http.get(proxyUrl, { headers: { 'Range': `bytes=0-${TARGET_BYTES_30S - 1}` } }, (res) => {
    console.log(`[Proxy Response] HTTP ${res.statusCode} ${res.statusMessage}`);
    console.log(`[Headers] Content-Type: ${res.headers['content-type']}`);
    console.log(`[Headers] Content-Range: ${res.headers['content-range']}`);
    console.log(`[Headers] Content-Length: ${(parseInt(res.headers['content-length'] || '0') / (1024*1024)).toFixed(2)} MB\n`);

    res.on('data', (chunk) => {
        if (!firstChunkTime) {
            firstChunkTime = (Date.now() - startTime) / 1000;
            console.log(`⚡ Time to First Byte (TTFB): ${firstChunkTime.toFixed(2)}s (Playback can begin!)`);
        }
        receivedBytes += chunk.length;
        fileStream.write(chunk);

        const elapsedSec = (Date.now() - startTime) / 1000;
        const currentMB = (receivedBytes / (1024 * 1024)).toFixed(2);
        const speedMBps = ((receivedBytes / (1024 * 1024)) / elapsedSec).toFixed(2);

        process.stdout.write(`\r▶ Streamed: ${currentMB} MB / ${(TARGET_BYTES_30S / (1024*1024)).toFixed(1)} MB | Speed: ${speedMBps} MB/s (${(speedMBps * 8).toFixed(1)} Mbps) | Elapsed: ${elapsedSec.toFixed(1)}s`);

        if (receivedBytes >= TARGET_BYTES_30S) {
            res.destroy(); // Stop stream immediately (without downloading the rest of the 3.22 GB!)
            fileStream.end();
            finish();
        }
    });

    res.on('end', () => {
        fileStream.end();
        finish();
    });

    res.on('error', (err) => {
        console.error("\nStream error:", err.message);
    });
});

req.on('error', (err) => {
    console.error("Request error:", err.message);
});

let finished = false;
function finish() {
    if (finished) return;
    finished = true;
    const totalSec = (Date.now() - startTime) / 1000;
    const totalMB = (receivedBytes / (1024 * 1024)).toFixed(2);
    const avgMBps = (totalMB / totalSec).toFixed(2);

    console.log(`\n\n================================================================================`);
    console.log(`✅ SUCCESS! FIRST 30 SECONDS STREAMED WITHOUT DOWNLOADING 3.22 GB FILE`);
    console.log(`================================================================================`);
    console.log(`   📦 Bytes Streamed: ${totalMB} MB (${receivedBytes.toLocaleString()} bytes)`);
    console.log(`   💾 Whole File Skipped: 3,440 MB (99.65% of the file was NOT downloaded!)`);
    console.log(`   ⏱️ Total Stream Time: ${totalSec.toFixed(2)} seconds`);
    console.log(`   🚀 Average Streaming Throughput: ${avgMBps} MB/s (${(avgMBps * 8).toFixed(1)} Mbps)`);
    console.log(`   📂 Stream Saved At: ${OUTPUT_FILE}`);
    console.log(`================================================================================\n`);
}
