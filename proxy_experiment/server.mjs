import http from 'node:http';
import https from 'node:https';
import { URL } from 'node:url';

const PORT = process.env.PORT || 8888;
const DEFAULT_CONCURRENCY = 8;
const DEFAULT_CHUNK_SIZE = 512 * 1024; // 512 KB per chunk
const LOOKAHEAD_WINDOW = 16; // Number of chunks to keep in flight

function fetchChunk(targetUrl, start, end, customHeaders = {}) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(targetUrl);
        const client = parsed.protocol === 'https:' ? https : http;
        const reqHeaders = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
            'Range': `bytes=${start}-${end}`,
            'Connection': 'keep-alive',
            ...customHeaders
        };

        const req = client.get(targetUrl, { headers: reqHeaders }, (res) => {
            if (res.statusCode !== 200 && res.statusCode !== 206) {
                return reject(new Error(`Upstream returned ${res.statusCode} for range ${start}-${end}`));
            }
            const chunks = [];
            res.on('data', c => chunks.push(c));
            res.on('end', () => resolve(Buffer.concat(chunks)));
            res.on('error', reject);
        });

        req.on('error', reject);
        req.setTimeout(15000, () => {
            req.destroy(new Error(`Timeout fetching chunk ${start}-${end}`));
        });
    });
}

function probeTarget(targetUrl, customHeaders = {}) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(targetUrl);
        const client = parsed.protocol === 'https:' ? https : http;
        const reqHeaders = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
            'Range': 'bytes=0-1',
            'Connection': 'keep-alive',
            ...customHeaders
        };

        const req = client.get(targetUrl, { headers: reqHeaders }, (res) => {
            const contentRange = res.headers['content-range'];
            const contentLength = res.headers['content-length'];
            const contentType = res.headers['content-type'] || 'video/x-matroska';
            let totalBytes = null;

            if (contentRange) {
                const match = contentRange.match(/\/(\d+|\*)$/);
                if (match && match[1] !== '*') {
                    totalBytes = parseInt(match[1], 10);
                }
            }
            if (!totalBytes && contentLength && res.statusCode === 200) {
                totalBytes = parseInt(contentLength, 10);
            }

            res.resume(); // consume and close
            resolve({
                statusCode: res.statusCode,
                totalBytes,
                contentType,
                acceptRanges: res.headers['accept-ranges'] || 'bytes'
            });
        });

        req.on('error', reject);
        req.setTimeout(10000, () => req.destroy(new Error('Probe timeout')));
    });
}

const server = http.createServer(async (req, res) => {
    const reqUrl = new URL(req.url, `http://${req.headers.host}`);

    if (reqUrl.pathname === '/status') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ status: 'ok', uptime: process.uptime() }));
    }

    if (reqUrl.pathname !== '/play' && reqUrl.pathname !== '/stream' && reqUrl.pathname !== '/bench') {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        return res.end(`
            <html>
                <body style="font-family:sans-serif;background:#121218;color:#eee;padding:2rem;">
                    <h2>TeraBox Accelerated Streaming Proxy</h2>
                    <p>Use <code>/play?url=&lt;video_url&gt;&concurrency=8&chunk=512</code></p>
                    <p>Or <code>/bench?url=&lt;video_url&gt;&bytes=20971520</code></p>
                </body>
            </html>
        `);
    }

    const targetUrl = reqUrl.searchParams.get('url');
    if (!targetUrl) {
        res.writeHead(400, { 'Content-Type': 'text/plain' });
        return res.end('Missing target url query parameter');
    }

    const concurrency = parseInt(reqUrl.searchParams.get('concurrency'), 10) || DEFAULT_CONCURRENCY;
    const chunkSize = (parseInt(reqUrl.searchParams.get('chunk'), 10) || (DEFAULT_CHUNK_SIZE / 1024)) * 1024;
    const referer = reqUrl.searchParams.get('referer') || 'https://flowvideoplayer.com/';

    const customHeaders = {
        'Referer': referer
    };

    try {
        console.log(`[Probe] Checking ${targetUrl.substring(0, 60)}...`);
        const info = await probeTarget(targetUrl, customHeaders);
        console.log(`[Probe] Target Content-Length: ${info.totalBytes ? (info.totalBytes / (1024 * 1024)).toFixed(1) + ' MB' : 'unknown'}, Type: ${info.contentType}`);

        const totalBytes = info.totalBytes || 10 * 1024 * 1024 * 1024; // Default large if unknown

        // Parse incoming range
        const clientRange = req.headers['range'];
        let start = 0;
        let end = totalBytes - 1;

        if (clientRange) {
            const parts = clientRange.replace(/bytes=/, '').split('-');
            start = parseInt(parts[0], 10) || 0;
            if (parts[1]) {
                end = parseInt(parts[1], 10);
            }
        }

        // If benchmark mode, cap bytes to bench
        if (reqUrl.pathname === '/bench') {
            const benchBytes = parseInt(reqUrl.searchParams.get('bytes'), 10) || (10 * 1024 * 1024); // 10 MB default
            end = Math.min(start + benchBytes - 1, totalBytes - 1);
        }

        const bytesToDeliver = end - start + 1;
        const totalChunks = Math.ceil(bytesToDeliver / chunkSize);

        res.writeHead(clientRange ? 206 : 200, {
            'Content-Type': info.contentType,
            'Content-Length': bytesToDeliver,
            'Content-Range': `bytes ${start}-${end}/${info.totalBytes || '*'}`,
            'Accept-Ranges': 'bytes',
            'Access-Control-Allow-Origin': '*',
            'Connection': 'keep-alive'
        });

        console.log(`\n============================================================`);
        console.log(`[Pipeline] Starting Multi-Connection Stream`);
        console.log(`   Range: bytes=${start}-${end} (${(bytesToDeliver / (1024 * 1024)).toFixed(2)} MB)`);
        console.log(`   Concurrency: ${concurrency} parallel connections`);
        console.log(`   Chunk size: ${chunkSize / 1024} KB | Total Chunks: ${totalChunks}`);
        console.log(`============================================================\n`);

        let clientAborted = false;
        req.on('close', () => {
            clientAborted = true;
        });

        // Pipeline state
        const bufferMap = new Map(); // chunkIndex -> Buffer
        let nextChunkToDeliver = 0;
        let nextChunkToFetch = 0;
        let activeRequests = 0;
        let deliveredBytes = 0;
        let benchStartTime = Date.now();
        let lastReportTime = Date.now();
        let lastReportBytes = 0;

        const metricsInterval = setInterval(() => {
            if (clientAborted || nextChunkToDeliver >= totalChunks) {
                clearInterval(metricsInterval);
                return;
            }
            const now = Date.now();
            const elapsedTotalSec = (now - benchStartTime) / 1000;
            const elapsedPeriodSec = (now - lastReportTime) / 1000;
            const periodBytes = deliveredBytes - lastReportBytes;
            const instantSpeedKBps = periodBytes / (1024 * elapsedPeriodSec);
            const instantSpeedMBps = instantSpeedKBps / 1024;
            const avgSpeedMBps = (deliveredBytes / (1024 * 1024)) / elapsedTotalSec;

            console.log(`[Live Stream] Delivered: ${(deliveredBytes / (1024 * 1024)).toFixed(2)} MB / ${(bytesToDeliver / (1024 * 1024)).toFixed(2)} MB | Instant: ${instantSpeedMBps.toFixed(2)} MB/s (${(instantSpeedMBps * 8).toFixed(1)} Mbps) | Avg: ${avgSpeedMBps.toFixed(2)} MB/s | Active: ${activeRequests} workers | In Buffer: ${bufferMap.size}`);

            lastReportTime = now;
            lastReportBytes = deliveredBytes;
        }, 1000);

        function pump() {
            if (clientAborted) return;

            // Deliver available chunks in order
            while (bufferMap.has(nextChunkToDeliver)) {
                const chunkData = bufferMap.get(nextChunkToDeliver);
                bufferMap.delete(nextChunkToDeliver);

                res.write(chunkData);
                deliveredBytes += chunkData.length;
                nextChunkToDeliver++;

                if (nextChunkToDeliver >= totalChunks) {
                    clearInterval(metricsInterval);
                    const totalSec = (Date.now() - benchStartTime) / 1000;
                    const finalAvgMBps = (deliveredBytes / (1024 * 1024)) / totalSec;
                    console.log(`\n✅ [Pipeline Complete] Total: ${(deliveredBytes / (1024 * 1024)).toFixed(2)} MB in ${totalSec.toFixed(2)}s | Final Average Speed: ${finalAvgMBps.toFixed(2)} MB/s (${(finalAvgMBps * 8).toFixed(2)} Mbps)\n`);
                    res.end();
                    return;
                }
            }

            // Fill lookahead buffer
            while (
                activeRequests < concurrency &&
                nextChunkToFetch < totalChunks &&
                (nextChunkToFetch - nextChunkToDeliver) < LOOKAHEAD_WINDOW &&
                !clientAborted
            ) {
                const chunkIdx = nextChunkToFetch++;
                const cStart = start + (chunkIdx * chunkSize);
                const cEnd = Math.min(cStart + chunkSize - 1, end);
                activeRequests++;

                fetchChunk(targetUrl, cStart, cEnd, customHeaders)
                    .then(buffer => {
                        activeRequests--;
                        if (!clientAborted) {
                            bufferMap.set(chunkIdx, buffer);
                            pump();
                        }
                    })
                    .catch(err => {
                        activeRequests--;
                        if (!clientAborted) {
                            console.error(`[Error] Worker failed chunk ${chunkIdx} (${cStart}-${cEnd}):`, err.message);
                            // Retry chunk after brief delay
                            setTimeout(() => {
                                if (!clientAborted && !bufferMap.has(chunkIdx)) {
                                    activeRequests++;
                                    fetchChunk(targetUrl, cStart, cEnd, customHeaders)
                                        .then(b => {
                                            activeRequests--;
                                            bufferMap.set(chunkIdx, b);
                                            pump();
                                        })
                                        .catch(() => {
                                            activeRequests--;
                                        });
                                }
                            }, 500);
                        }
                    });
            }
        }

        // Start pipeline
        pump();

    } catch (err) {
        console.error('[Error] Pipeline failed:', err.message);
        if (!res.headersSent) {
            res.writeHead(500, { 'Content-Type': 'text/plain' });
            res.end(`Pipeline error: ${err.message}`);
        }
    }
});

server.listen(PORT, '0.0.0.0', () => {
    console.log(`🚀 TeraBox Accelerated Streaming Proxy listening on http://0.0.0.0:${PORT}`);
    console.log(`   Default Concurrency: ${DEFAULT_CONCURRENCY} workers | Chunk: ${DEFAULT_CHUNK_SIZE / 1024} KB`);
});
