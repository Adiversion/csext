const fetch = globalThis.fetch;

async function testWorkerE2E() {
  console.log("1. Testing Health Endpoint...");
  const hRes = await fetch("https://terabox-gateway.terabox-gateway-worker.workers.dev/health");
  const hJson = await hRes.json();
  console.log("Health:", hJson);

  console.log("\n2. Testing /m3u8 Rewriting...");
  const sampleM3u8 = `#EXTM3U
#EXT-X-TARGETDURATION:15
#EXTINF:10,
https://v4.freeterabox.com/video/chunk1.ts?token=abc
#EXTINF:10,
https://v4.freeterabox.com/video/chunk2.ts?token=xyz
#EXT-X-ENDLIST`;

  const mRes = await fetch("https://terabox-gateway.terabox-gateway-worker.workers.dev/m3u8", {
    method: "POST",
    headers: { "Content-Type": "application/vnd.apple.mpegurl" },
    body: sampleM3u8
  });
  const mText = await mRes.text();
  console.log("Rewritten M3U8 Content:\n" + mText);

  console.log("\n3. Testing /proxy chunk download...");
  const chunkUrl = "https://v4.freeterabox.com/video/netdisk-videotran-tky/7b30f14f754b8b02ed023bbb7e73d920_1190_28_ts/67c2112bdf3cdd001daccdc8c3a6ffa2?ts_size=7845992&region=tky&ccn=IN&from_type=1&fsid=801687144894288&iv=0&ouk=4402320101784&sta_cs=53&to=any&vuk=&app_id=250528&dp-logid=201718891231867052&fn=School+boy+with+tution+teacher.mp4&hit_sl=0&logid=201718891231867052&r=373497895&raw_appid=0&ref=0&size=768963806&sta_dt=video&sta_dx=733&start_ply=0&time=1793746865&tot=ctqNZ&ts_sl=0&uo=any&uva=2272513262&csl=0&dtime=10&etag=67c2112bdf3cdd001daccdc8c3a6ffa2&fid=4337f7e443e8ac42ed21b4b5a38f5d02-&len=355320&range=7132720-7488039&resv4=&sign=BOUTHNFRQ-F3530edecde9cd71b79378b290804a96-TKEQyASi%252BX1ZFa3TSW%252B1suLQm5o%253D&xcode=7ae3a652522d49c95bea89379ccbacfd315b34ce2c179350c48ce52bdb2cde00c2170babad36dbc103386efc945faa490b2977702d3e6764&xv=6&ogr=0&rrg=XVVi&need_suf=&pmk=140067c2112bdf3cdd001daccdc8c3a6ffa21f45ab2300000077b868&by=my-streaming";
  const proxyUrl = "https://terabox-gateway.terabox-gateway-worker.workers.dev/proxy?url=" + encodeURIComponent(chunkUrl);
  const pRes = await fetch(proxyUrl);
  const pBuf = await pRes.arrayBuffer();
  console.log(`Proxy Result: HTTP ${pRes.status}, Size: ${pBuf.byteLength} bytes`);
}

testWorkerE2E().catch(console.error);
