import http from 'node:http';

const PORT = 9999;
const FILE_SIZE = 100 * 1024 * 1024; // 100 MB synthetic MKV video
const THROTTLE_KBPS = 100; // Artificially throttle each connection to 100 KB/s (matches upstream worker limit)
const CHUNK_TICK_BYTES = 10 * 1024; // 10 KB per tick
const TICK_INTERVAL_MS = (CHUNK_TICK_BYTES / (THROTTLE_KBPS * 1024)) * 1000; // ~97ms per 10KB

const server = http.createServer((req, res) => {
    const rangeHeader = req.headers['range'];
    let start = 0;
    let end = FILE_SIZE - 1;

    if (rangeHeader) {
        const parts = rangeHeader.replace(/bytes=/, '').split('-');
        start = parseInt(parts[0], 10) || 0;
        if (parts[1]) end = parseInt(parts[1], 10);
    }

    const totalToDeliver = end - start + 1;

    res.writeHead(rangeHeader ? 206 : 200, {
        'Content-Type': 'video/x-matroska',
        'Content-Length': totalToDeliver,
        'Content-Range': `bytes ${start}-${end}/${FILE_SIZE}`,
        'Accept-Ranges': 'bytes',
        'Connection': 'keep-alive',
        'Access-Control-Allow-Origin': '*'
    });

    let currentOffset = start;
    let clientClosed = false;

    req.on('close', () => {
        clientClosed = true;
    });

    // Dummy buffer (synthetic video payload)
    const dummyChunk = Buffer.alloc(CHUNK_TICK_BYTES, 0xAA);

    function sendNext() {
        if (clientClosed || currentOffset > end) {
            if (!clientClosed) res.end();
            return;
        }

        const bytesRemaining = end - currentOffset + 1;
        const bytesToSend = Math.min(CHUNK_TICK_BYTES, bytesRemaining);
        currentOffset += bytesToSend;

        res.write(dummyChunk.subarray(0, bytesToSend));

        // Throttle to 100 KB/s per connection
        setTimeout(sendNext, TICK_INTERVAL_MS);
    }

    sendNext();
});

server.listen(PORT, '127.0.0.1', () => {
    console.log(`📡 Mock Throttled TeraBox Video Server running on http://127.0.0.1:${PORT}/video.mkv`);
    console.log(`   Simulated File Size: 100 MB | Per-Connection Throttled Rate: ${THROTTLE_KBPS} KB/s`);
});
