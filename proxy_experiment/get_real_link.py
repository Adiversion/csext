import urllib.request, json, re

user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
baseUrl = "https://flowvideoplayer.com"

req = urllib.request.Request(baseUrl, headers={'User-Agent': user_agent})
cookie_jar = {}
try:
    with urllib.request.urlopen(req, timeout=10) as resp:
        html = resp.read().decode('utf-8', errors='ignore')
        for h, v in resp.getheaders():
            if h.lower() == 'set-cookie':
                parts = v.split(';')[0].split('=')
                cookie_jar[parts[0]] = parts[1]
        csrf = re.search(r'name=["\']csrf-token["\']\s+content=["\']([^"\']+)["\']', html)
        csrf_token = csrf.group(1) if csrf else None

        cookie_str = "; ".join([f"{k}={v}" for k, v in cookie_jar.items()])
        
        # 2. POST /device/init
        init_payload = {
            "cpu": 4, "memory": 8, "touch": 0, "platform": "Win32", "lang": "en-IN",
            "vendor": "Google Inc.", "ua": user_agent, "backup_token": None,
            "os": "windows", "browser": "chrome", "pwa_installed": False
        }
        init_req = urllib.request.Request(
            f"{baseUrl}/device/init",
            headers={
                'User-Agent': user_agent,
                'Content-Type': 'application/json',
                'X-Requested-With': 'XMLHttpRequest',
                'X-CSRF-TOKEN': csrf_token,
                'Origin': baseUrl,
                'Referer': f"{baseUrl}/",
                'Cookie': cookie_str
            },
            data=json.dumps(init_payload).encode('utf-8')
        )
        with urllib.request.urlopen(init_req, timeout=10) as i_resp:
            for h, v in i_resp.getheaders():
                if h.lower() == 'set-cookie':
                    parts = v.split(';')[0].split('=')
                    cookie_jar[parts[0]] = parts[1]

        cookie_str = "; ".join([f"{k}={v}" for k, v in cookie_jar.items()])

        # 3. POST /search/video
        search_req = urllib.request.Request(
            f"{baseUrl}/search/video",
            headers={
                'User-Agent': user_agent,
                'Content-Type': 'application/json',
                'X-Requested-With': 'XMLHttpRequest',
                'X-CSRF-TOKEN': csrf_token,
                'Origin': baseUrl,
                'Referer': f"{baseUrl}/",
                'Cookie': cookie_str
            },
            data=json.dumps({"url": "https://terabox.app/s/1UVJNrxF01I8IuvOXbZYIdw"}).encode('utf-8')
        )
        with urllib.request.urlopen(search_req, timeout=10) as s_resp:
            s_data = json.loads(s_resp.read().decode('utf-8'))
            print("Response:", json.dumps(s_data, indent=2))
except Exception as e:
    print("Error:", e)
