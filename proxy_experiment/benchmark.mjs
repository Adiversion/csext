import http from 'node:http';
import https from 'node:https';
import { URL } from 'node:url';

// Benchmark function to test direct single connection speed
function testSingleConnection(targetUrl, headers, totalBytesToFetch) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(targetUrl);
        const client = parsed.protocol === 'https:' ? https : http;
        const reqHeaders = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
            'Range': `bytes=0-${totalBytesToFetch - 1}`,
            'Connection': 'keep-alive',
            ...headers
        };

        const startTime = Date.now();
        let receivedBytes = 0;

        const req = client.get(targetUrl, { headers: reqHeaders }, (res) => {
            if (res.statusCode !== 200 && res.statusCode !== 206) {
                return reject(new Error(`Single connection returned HTTP ${res.statusCode}`));
            }
            res.on('data', chunk => {
                receivedBytes += chunk.length;
                if (receivedBytes >= totalBytesToFetch) {
                    res.destroy(); // finish
                    const elapsedSec = (Date.now() - startTime) / 1000;
                    const speedKBps = receivedBytes / (1024 * elapsedSec);
                    resolve({ bytes: receivedBytes, elapsedSec, speedKBps, speedMBps: speedKBps / 1024 });
                }
            });
            res.on('end', () => {
                const elapsedSec = (Date.now() - startTime) / 1000;
                const speedKBps = receivedBytes / (1024 * elapsedSec);
                resolve({ bytes: receivedBytes, elapsedSec, speedKBps, speedMBps: speedKBps / 1024 });
            });
            res.on('error', reject);
        });

        req.on('error', reject);
        req.setTimeout(25000, () => req.destroy(new Error('Timeout')));
    });
}

// Benchmark function to test Multi-Connection Range Pipeline
function testMultiConnection(targetUrl, headers, totalBytesToFetch, concurrency, chunkSize) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(targetUrl);
        const client = parsed.protocol === 'https:' ? https : http;
        const totalChunks = Math.ceil(totalBytesToFetch / chunkSize);
        let nextChunk = 0;
        let active = 0;
        let completed = 0;
        let totalDownloaded = 0;
        const startTime = Date.now();
        let aborted = false;

        function scheduleNext() {
            if (aborted) return;
            if (completed >= totalChunks) {
                const elapsedSec = (Date.now() - startTime) / 1000;
                const speedKBps = totalDownloaded / (1024 * elapsedSec);
                resolve({ bytes: totalDownloaded, elapsedSec, speedKBps, speedMBps: speedKBps / 1024, concurrency });
                return;
            }

            while (active < concurrency && nextChunk < totalChunks && !aborted) {
                const chunkIdx = nextChunk++;
                const start = chunkIdx * chunkSize;
                const end = Math.min(start + chunkSize - 1, totalBytesToFetch - 1);
                active++;

                const reqHeaders = {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
                    'Range': `bytes=${start}-${end}`,
                    'Connection': 'keep-alive',
                    ...headers
                };

                const req = client.get(targetUrl, { headers: reqHeaders }, (res) => {
                    const parts = [];
                    res.on('data', c => parts.push(c));
                    res.on('end', () => {
                        active--;
                        completed++;
                        const chunkLen = parts.reduce((a, b) => a + b.length, 0);
                        totalDownloaded += chunkLen;
                        scheduleNext();
                    });
                    res.on('error', err => {
                        active--;
                        console.error(`Chunk ${chunkIdx} error:`, err.message);
                        scheduleNext();
                    });
                });

                req.on('error', err => {
                    active--;
                    console.error(`Req error chunk ${chunkIdx}:`, err.message);
                    scheduleNext();
                });

                req.setTimeout(15000, () => req.destroy(new Error('Chunk timeout')));
            }
        }

        scheduleNext();
    });
}

async function runBenchmark(targetUrl, testMb = 10) {
    const bytesToTest = testMb * 1024 * 1024;
    console.log(`\n======================================================================`);
    console.log(`🎯 BENCHMARK: Testing Streaming Acceleration on Node.js Server`);
    console.log(`   Target: ${targetUrl.substring(0, 70)}...`);
    console.log(`   Test Payload: ${testMb} MB (${bytesToTest.toLocaleString()} bytes)`);
    console.log(`======================================================================\n`);

    const headers = {
        'Referer': 'https://flowvideoplayer.com/'
    };

    console.log(`⏳ [1/5] Testing Direct Single Connection (Standard ExoPlayer / Browser behavior)...`);
    let singleResult = null;
    try {
        singleResult = await testSingleConnection(targetUrl, headers, bytesToTest);
        console.log(`   ➔ Speed: ${singleResult.speedKBps.toFixed(1)} KB/s (${singleResult.speedMBps.toFixed(2)} MB/s) | Time: ${singleResult.elapsedSec.toFixed(2)}s`);
    } catch (e) {
        console.log(`   ❌ Direct single connection test failed:`, e.message);
    }

    const testConfigs = [
        { concurrency: 4, chunkSize: 384 * 1024 },
        { concurrency: 8, chunkSize: 512 * 1024 },
        { concurrency: 12, chunkSize: 512 * 1024 },
        { concurrency: 16, chunkSize: 768 * 1024 }
    ];

    const results = [];

    for (let i = 0; i < testConfigs.length; i++) {
        const cfg = testConfigs[i];
        console.log(`\n⏳ [${i + 2}/5] Testing Parallel Pipeline with ${cfg.concurrency} Workers (Chunk: ${cfg.chunkSize / 1024} KB)...`);
        try {
            const res = await testMultiConnection(targetUrl, headers, bytesToTest, cfg.concurrency, cfg.chunkSize);
            const speedup = singleResult ? (res.speedKBps / singleResult.speedKBps).toFixed(1) + 'x' : 'N/A';
            console.log(`   ➔ Speed: ${res.speedKBps.toFixed(1)} KB/s (${res.speedMBps.toFixed(2)} MB/s) | Time: ${res.elapsedSec.toFixed(2)}s | Speedup: ${speedup}`);
            results.push({ ...res, speedup, chunkSize: cfg.chunkSize });
        } catch (e) {
            console.log(`   ❌ Multi-connection test (${cfg.concurrency} workers) failed:`, e.message);
        }
    }

    console.log(`\n======================================================================`);
    console.log(`📊 FINAL BENCHMARK COMPARISON TABLE (Targeting >= 1 MB/s Playback)`);
    console.log(`======================================================================`);
    console.log(`Mode                          | Bandwidth (MB/s) | Bandwidth (Mbps) | 1MBps Goal Met? | Speedup`);
    console.log(`------------------------------|------------------|------------------|-----------------|--------`);

    if (singleResult) {
        const goalMet = singleResult.speedMBps >= 1.0 ? '✅ YES' : '❌ NO (Buffers)';
        console.log(`Single Connection (Vanilla)   | ${singleResult.speedMBps.toFixed(2).padStart(12)} MB/s | ${(singleResult.speedMBps * 8).toFixed(1).padStart(12)} Mbps | ${goalMet.padStart(15)} |    1.0x`);
    }

    for (const r of results) {
        const label = `${r.concurrency} Workers (${r.chunkSize / 1024}KB chunk)`.padEnd(29);
        const goalMet = r.speedMBps >= 1.0 ? '✅ YES (Smooth!)' : '⚠️ Close';
        console.log(`${label} | ${r.speedMBps.toFixed(2).padStart(12)} MB/s | ${(r.speedMBps * 8).toFixed(1).padStart(12)} Mbps | ${goalMet.padStart(15)} | ${r.speedup.padStart(7)}`);
    }
    console.log(`======================================================================\n`);
}

// If invoked with a URL arg
const targetArg = process.argv[2] || 'https://speed.hetzner.de/100MB.bin';
const mbArg = parseInt(process.argv[3], 10) || 10;
runBenchmark(targetArg, mbArg);
