import urllib.request, json, time

user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
headers = {
    "User-Agent": user_agent,
    "Accept": "application/json, text/plain, */*",
    "Referer": "https://getindevice.com/terabox-downloader/"
}

t_url = f"https://getindevice.com/api/token/?_t={int(time.time()*1000)}"
req = urllib.request.Request(t_url, headers=headers)
try:
    with urllib.request.urlopen(req, timeout=10) as resp:
        token_data = json.loads(resp.read().decode('utf-8'))
        token = token_data.get('token')
        print("Token:", token)

        dl_headers = headers.copy()
        dl_headers["Content-Type"] = "application/json"
        dl_headers["x-request-token"] = token

        dl_payload = json.dumps({"url": "https://terabox.app/s/1UVJNrxF01I8IuvOXbZYIdw", "tool": "terabox-downloader"}).encode('utf-8')
        dl_req = urllib.request.Request("https://getindevice.com/api/download/", headers=dl_headers, data=dl_payload)
        with urllib.request.urlopen(dl_req, timeout=10) as dl_resp:
            dl_data = json.loads(dl_resp.read().decode('utf-8'))
            print("Download data keys:", dl_data.keys())
            videos = dl_data.get('videos', [])
            print(f"Found {len(videos)} videos:")
            for v in videos:
                print("Title:", v.get('title'))
                print("HLS URL:", v.get('hlsStreamUrl')[:100] if v.get('hlsStreamUrl') else None)
                print("Direct URL:", v.get('url')[:100] if v.get('url') else None)
except Exception as e:
    print("Error:", e)
