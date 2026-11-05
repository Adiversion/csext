import http from 'node:http';

function testWorkers(concurrency, chunkSize, totalMb = 10) {
    return new Promise((resolve) => {
        const totalBytes = totalMb * 1024 * 1024;
        const totalChunks = Math.ceil(totalBytes / chunkSize);
        let nextChunk = 0;
        let active = 0;
        let completed = 0;
        let totalDownloaded = 0;
        const startTime = Date.now();

        function scheduleNext() {
            if (completed >= totalChunks) {
                const elapsedSec = (Date.now() - startTime) / 1000;
                const speedKBps = totalDownloaded / (1024 * elapsedSec);
                resolve({ speedMBps: speedKBps / 1024, speedKBps, elapsedSec });
                return;
            }

            while (active < concurrency && nextChunk < totalChunks) {
                const chunkIdx = nextChunk++;
                const start = chunkIdx * chunkSize;
                const end = Math.min(start + chunkSize - 1, totalBytes - 1);
                active++;

                http.get('http://127.0.0.1:9999/video.mkv', { headers: { 'Range': `bytes=${start}-${end}` } }, res => {
                    let len = 0;
                    res.on('data', c => { len += c.length; });
                    res.on('end', () => {
                        active--;
                        completed++;
                        totalDownloaded += len;
                        scheduleNext();
                    });
                }).on('error', () => {
                    active--;
                    scheduleNext();
                });
            }
        }
        scheduleNext();
    });
}

async function main() {
    console.log("==========================================================================");
    console.log("⚡ TESTING 1 MB/s (8 Mbps) PLAYBACK ACCELERATION ON 10 MB PAYLOAD");
    console.log("==========================================================================");

    for (const workers of [1, 4, 8, 12, 16]) {
        const chunk = 256 * 1024; // 256 KB chunks
        console.log(`Running with ${workers} workers...`);
        const res = await testWorkers(workers, chunk, workers === 1 ? 2 : 8);
        const goal = res.speedMBps >= 1.0 ? "🎉 GOAL ACHIEVED! (>= 1 MB/s)" : "Throttled";
        console.log(`  ➔ Workers: ${workers.toString().padStart(2)} | Speed: ${res.speedMBps.toFixed(2)} MB/s (${(res.speedMBps * 8).toFixed(2)} Mbps) | ${goal}`);
    }
    console.log("==========================================================================");
}

main();
