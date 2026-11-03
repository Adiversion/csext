/**
 * TeraBox Gateway & Anti-Buffering Cloudflare Worker
 * 
 * Features:
 * - On-demand HLS playlist extraction & chunk URL rewriting (?mode=stream&surl=...&type=...)
 * - High-speed zero-storage streaming chunk proxy (/proxy?url=...)
 * - Automatic 480p fallback if 1080p/720p returns VIP transcode restriction (errno: 130)
 * - Full CORS & Range request pass-through for ExoPlayer and browser web players
 */

const USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // 1. Handle CORS Preflight
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
          "Access-Control-Allow-Headers": "*",
          "Access-Control-Max-Age": "86400",
        },
      });
    }

    const mode = url.searchParams.get("mode") || "";
    const pathname = url.pathname;

    // 2. Health & Status Check
    if (pathname === "/health" || (pathname === "/" && !url.search)) {
      return jsonResponse({
        status: "ok",
        service: "terabox-gateway-worker",
        version: "1.0.0",
        usage: {
          stream: "/?mode=stream&surl={surl}&type={M3U8_AUTO_480|M3U8_AUTO_720|M3U8_AUTO_1080}",
          proxy: "/proxy?url={encoded_upstream_chunk_url}",
          resolve: "/?mode=resolve&surl={surl}"
        }
      });
    }

    // 3. Segment / Chunk Proxy Route
    if (pathname === "/proxy" || pathname === "/segment" || mode === "segment") {
      return handleProxyChunk(request, url);
    }

    // 4. HLS Streaming Manifest Route
    if (mode === "stream" || pathname === "/stream") {
      return handleStreamManifest(request, url, env);
    }

    // 5. Direct Link / Metadata Resolver Route
    if (mode === "resolve" || pathname === "/resolve") {
      return handleResolve(request, url, env);
    }

    return new Response("Not Found", { status: 404 });
  }
};

/**
 * Handle HLS manifest generation and segment rewriting
 */
async function handleStreamManifest(request, url, env) {
  const surl = cleanSurl(url.searchParams.get("surl"));
  if (!surl) {
    return errorResponse("Missing or invalid 'surl' parameter", 400);
  }

  const requestedType = url.searchParams.get("type") || "M3U8_AUTO_480";
  const ndus = url.searchParams.get("ndus") || env.NDUS_COOKIE || "";

  try {
    const host = "dm.terabox.com";
    const pageUrl = `https://${host}/sharing/link?surl=${surl}`;
    const pageHeaders = {
      "User-Agent": USER_AGENT,
      ...(ndus ? { "Cookie": `ndus=${ndus}` } : {})
    };

    const pageRes = await fetch(pageUrl, { headers: pageHeaders });
    if (!pageRes.ok) {
      return errorResponse(`Failed to fetch TeraBox share page (HTTP ${pageRes.status})`, pageRes.status);
    }

    const html = await pageRes.text();

    // Extract dynamic jsToken
    const tokenMatch = html.match(/fn\(['"]([A-F0-9]{64,128})['"]\)/i) || html.match(/%22([A-F0-9]{64,128})%22/i);
    const jsToken = tokenMatch ? tokenMatch[1] : "";

    // Extract window.__INITIAL_STATE__
    let sign = null, timestamp = null, uk = null, shareid = null, fid = null, path = null;
    const stateIdx = html.indexOf("window.__INITIAL_STATE__");
    if (stateIdx !== -1) {
      const start = html.indexOf('{', stateIdx);
      const end = html.indexOf(";</script>", start) !== -1 ? html.indexOf(";</script>", start) : html.indexOf("</script>", start);
      if (start !== -1 && end !== -1 && end > start) {
        try {
          const parsed = JSON.parse(html.slice(start, end));
          const shareInfo = parsed?.pinia?.share?.shareInfo;
          if (shareInfo) {
            sign = shareInfo.sign;
            timestamp = shareInfo.timestamp;
            uk = shareInfo.uk;
            shareid = shareInfo.shareid;
            fid = shareInfo.fileInfo?.fs_id;
            path = shareInfo.fileInfo?.path;
          }
        } catch (e) {}
      }
    }

    // Fallback regex for state signatures
    if (!sign) sign = html.match(/"sign"\s*:\s*"([a-f0-9]{32,64})"/i)?.[1];
    if (!timestamp) timestamp = html.match(/"timestamp"\s*:\s*"?(\d{9,12})"?/)?.[1];
    if (!uk) uk = html.match(/"uk"\s*:\s*"?(\d+)"?/)?.[1];
    if (!shareid) shareid = html.match(/"shareid"\s*:\s*"?(\d+)"?/)?.[1];

    // If fileInfo wasn't embedded, query share/list
    if (!fid || !path) {
      const tokenParam = jsToken ? `&jsToken=${jsToken}` : "";
      const listUrl = `https://${host}/share/list?app_id=250528${tokenParam}&shorturl=${surl}&root=1`;
      const listRes = await fetch(listUrl, {
        headers: {
          "User-Agent": USER_AGENT,
          ...(ndus ? { "Cookie": `ndus=${ndus}` } : {}),
          "Referer": pageUrl
        }
      });
      const parsed = await listRes.json();
      const file = parsed?.list?.[0];
      if (file) {
        fid = file.fs_id;
        path = file.path;
      }
    }

    if (!fid || !path || !sign || !timestamp || !uk || !shareid) {
      return errorResponse("Could not extract TeraBox file signatures", 500);
    }

    async function queryStreaming(qType) {
      const pathEnc = encodeURIComponent(path);
      const tokenParam = jsToken ? `&jsToken=${jsToken}` : "";
      const streamUrl = `https://${host}/share/streaming?app_id=250528&channel=dubox&clienttype=0&shorturl=${surl}&root=1&type=${qType}&path=${pathEnc}${tokenParam}&uk=${uk}&shareid=${shareid}&fid=${fid}&sign=${sign}&timestamp=${timestamp}`;
      return await fetch(streamUrl, {
        headers: {
          "User-Agent": USER_AGENT,
          ...(ndus ? { "Cookie": `ndus=${ndus}` } : {}),
          "Referer": pageUrl
        }
      });
    }

    let manifestRes = await queryStreaming(requestedType);
    let manifestText = await manifestRes.text();
    let isM3U = manifestText.includes("#EXTM3U");

    // Auto-fallback to 480p unthrottled stream if 1080p/720p requires VIP transcode (errno: 130)
    if (!isM3U && manifestText.includes('"errno":130') && requestedType !== "M3U8_AUTO_480") {
      manifestRes = await queryStreaming("M3U8_AUTO_480");
      manifestText = await manifestRes.text();
      isM3U = manifestText.includes("#EXTM3U");
    }

    if (!isM3U) {
      return errorResponse(`Streaming manifest failed: ${manifestText.slice(0, 150)}`, 502);
    }

    // Rewrite all chunk URLs to stream on-demand through our worker proxy
    const workerOrigin = url.origin;
    const lines = manifestText.split("\n");
    const rewritten = lines.map(line => {
      const trimmed = line.trim();
      if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        return `${workerOrigin}/proxy?url=${encodeURIComponent(trimmed)}`;
      }
      return line;
    }).join("\n");

    return new Response(rewritten, {
      status: 200,
      headers: {
        "Content-Type": "application/vnd.apple.mpegurl; charset=utf-8",
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
        "Cache-Control": "public, max-age=3600"
      }
    });
  } catch (err) {
    return errorResponse(`Internal error generating stream: ${err.message}`, 500);
  }
}

/**
 * Handle high-speed streaming chunk proxying (Zero storage, in-flight pipe)
 */
async function handleProxyChunk(request, url) {
  const targetUrl = url.searchParams.get("url");
  if (!targetUrl || !targetUrl.startsWith("http")) {
    return errorResponse("Missing or invalid 'url' parameter", 400);
  }

  try {
    const upstreamHeaders = new Headers();
    upstreamHeaders.set("User-Agent", USER_AGENT);
    upstreamHeaders.set("Referer", "https://dm.terabox.com/");
    upstreamHeaders.set("Accept", "*/*");
    upstreamHeaders.set("Connection", "keep-alive");

    // Forward player's Range header for video seeking
    const range = request.headers.get("Range");
    if (range) {
      upstreamHeaders.set("Range", range);
    }

    const upstreamRes = await fetch(targetUrl, {
      headers: upstreamHeaders,
      redirect: "follow"
    });

    const responseHeaders = new Headers(upstreamRes.headers);
    responseHeaders.set("Access-Control-Allow-Origin", "*");
    responseHeaders.set("Access-Control-Allow-Headers", "*");
    responseHeaders.set("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges");
    responseHeaders.set("Cache-Control", "public, max-age=86400");
    if (!responseHeaders.has("Content-Type")) {
      responseHeaders.set("Content-Type", "video/mp2t");
    }

    // Pipe upstream byte stream directly to the client with zero buffering
    return new Response(upstreamRes.body, {
      status: upstreamRes.status,
      statusText: upstreamRes.statusText,
      headers: responseHeaders
    });
  } catch (err) {
    return errorResponse(`Proxy failed: ${err.message}`, 502);
  }
}

/**
 * Handle direct dlink and metadata resolution
 */
async function handleResolve(request, url, env) {
  const surl = cleanSurl(url.searchParams.get("surl"));
  if (!surl) {
    return errorResponse("Missing or invalid 'surl' parameter", 400);
  }

  const ndus = url.searchParams.get("ndus") || env.NDUS_COOKIE || "";
  const host = "dm.terabox.com";

  try {
    const tokenParam = "";
    const listUrl = `https://${host}/share/list?app_id=250528&shorturl=${surl}&root=1`;
    const res = await fetch(listUrl, {
      headers: {
        "User-Agent": USER_AGENT,
        ...(ndus ? { "Cookie": `ndus=${ndus}` } : {}),
        "Referer": `https://${host}/sharing/link?surl=${surl}`
      }
    });

    const data = await res.json();
    return jsonResponse(data);
  } catch (err) {
    return errorResponse(`Failed to resolve metadata: ${err.message}`, 500);
  }
}

function cleanSurl(raw) {
  if (!raw) return null;
  let surl = raw.trim();
  const match = surl.match(/(?:\/s\/|surl=)([a-zA-Z0-9_\-]+)/);
  if (match) surl = match[1];
  if (surl.startsWith("1") && surl.length === 23) {
    surl = surl.substring(1);
  }
  return surl;
}

function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Access-Control-Allow-Origin": "*"
    }
  });
}

function errorResponse(msg, status = 400) {
  return jsonResponse({ error: true, message: msg }, status);
}
