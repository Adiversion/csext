import json
import urllib.request
import ssl
import sys

# Ensure UTF-8 output on Windows
sys.stdout.reconfigure(encoding='utf-8')

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    'Content-Type': 'application/json'
}

print("==================================================================")
print("      AUTOMATED TEST SUITE: iTeraPlay Provider Verification       ")
print("==================================================================")

passed = 0
failed = 0

def test(name, condition, details=""):
    global passed, failed
    if condition:
        passed += 1
        print(f"  [PASS] {name} {details}")
    else:
        failed += 1
        print(f"  [FAIL] {name} {details}")

# 1. Test Catalog API
print("\n--- TEST 1: Catalog API & Payload Verification ---")
try:
    url = "https://teraviralhub.com/api/home/search?sort=trending&offset=0&limit=20"
    req = urllib.request.Request(url, headers={'User-Agent': HEADERS['User-Agent'], 'Referer': 'https://teraviralhub.com/'})
    with urllib.request.urlopen(req, timeout=10, context=ctx) as r:
        raw_json = json.loads(r.read().decode('utf-8'))
        has_success = raw_json.get('success') is True
        has_data = bool(raw_json.get('data'))
        test("Catalog API Response", has_success and has_data, f"(Data length: {len(raw_json.get('data', ''))})")
except Exception as e:
    test("Catalog API Response", False, str(e))

# 2. Test TeraBox Stream Resolver with Auto Session Cookies
print("\n--- TEST 2: /api/stream TeraBox Link Resolution (with Auto-Cookie) ---")
try:
    # A. Visit homepage to acquire verification cookies
    home_req = urllib.request.Request("https://iteraplay.com/", headers={'User-Agent': HEADERS['User-Agent']})
    with urllib.request.urlopen(home_req, timeout=8, context=ctx) as r:
        headers_dict = dict(r.info())
        cookie_header = r.headers.get('Set-Cookie', '')
        # Handle multiple Set-Cookie headers
        all_cookies = r.headers.get_all('Set-Cookie') if hasattr(r.headers, 'get_all') else [cookie_header]
        cookie_str = "; ".join([c.split(';')[0].strip() for c in all_cookies if c])
        test("Session Cookie Acquisition", bool(cookie_str), f"(Cookies: {cookie_str[:60]}...)")

    # B. Call /api/stream
    stream_api = "https://iteraplay.com/api/stream"
    payload = json.dumps({"url": "https://1024terabox.com/s/1DlSow-fSEHT9Y3u2boxrIg"}).encode('utf-8')
    s_req = urllib.request.Request(
        stream_api,
        data=payload,
        headers={
            'User-Agent': HEADERS['User-Agent'],
            'Content-Type': 'application/json',
            'Origin': 'https://iteraplay.com',
            'Referer': 'https://iteraplay.com/',
            'Cookie': cookie_str
        }
    )
    with urllib.request.urlopen(s_req, timeout=12, context=ctx) as r:
        data = json.loads(r.read().decode('utf-8'))
        status = data.get('status')
        files = data.get('list', [])
        test("Stream Resolver API", status == "success" and len(files) > 0, f"(Returned {len(files)} files)")

        if files:
            first = files[0]
            fast_streams = first.get('fast_stream_url', {})
            qualities = list(fast_streams.keys())
            test("Multi-Quality HLS Streams", len(qualities) > 0, f"(Available qualities: {qualities})")

            # Probe one m3u8 playlist
            if qualities:
                m3u8_url = fast_streams[qualities[0]]
                m_req = urllib.request.Request(m3u8_url, headers={'User-Agent': HEADERS['User-Agent'], 'Referer': 'https://iteraplay.com/'})
                with urllib.request.urlopen(m_req, timeout=10, context=ctx) as mr:
                    m3u8_text = mr.read().decode('utf-8', errors='ignore')
                    is_hls = "#EXTM3U" in m3u8_text
                    test("HLS Stream Playlist (.m3u8)", is_hls, f"(Status: {mr.getcode()}, Chunks: {'#EXTINF' in m3u8_text})")
except Exception as e:
    test("Stream Resolver API", False, str(e))

print("\n==================================================================")
print(f"VERIFICATION SUMMARY: {passed} PASSED, {failed} FAILED")
print("==================================================================")
