# TeraBox Gateway Cloudflare Worker

A production-ready Cloudflare Worker that enables high-speed, unthrottled video streaming from TeraBox links on demand.

### Features
* **Zero Storage**: Streams media chunks in-flight using `TransformStream` without storing any video on disk.
* **Auto-Fallback**: If `1080p` or `720p` returns `errno: 130` (VIP required), it automatically falls back to unthrottled `480p` with zero player stalls.
* **Header & CORS Bypass**: Injects all required headers (`Referer`, `User-Agent`, `Origin`) so players like ExoPlayer play chunks natively.
* **Compatible with iTeraPlay Cloudstream Plugin**: Works out-of-the-box with the "Custom Gateway Worker" setting.

---

## Method 1: Web Dashboard (Easiest — 60 Seconds, No Installs)

1. Go to [dash.cloudflare.com](https://dash.cloudflare.com/) and log in (or sign up for free).
2. On the left sidebar, click **Compute (Workers) > Workers & Pages**.
3. Click the **Create application** button, then select **Create Worker**.
4. Give your worker a name (e.g., `terabox-gateway`), then click **Deploy**.
5. On the success screen, click **Edit code** (Quick Edit).
6. Select everything in `worker.js`, delete it, and paste the entire contents of [`worker.js`](worker.js).
7. (Optional) To add your default `ndus` cookie:
   * Go to the Worker's **Settings > Variables and Secrets**.
   * Under **Environment Variables**, add:
     * **Variable name**: `NDUS_COOKIE`
     * **Value**: Your TeraBox `ndus` cookie
8. Click **Save and deploy**.

Your worker URL will look like:
```
https://terabox-gateway.<your-subdomain>.workers.dev
```

---

## Method 2: Command Line via Wrangler CLI

If you prefer deploying from your terminal:

```powershell
cd cloudflare-worker

# 1. Log in to Cloudflare (opens browser)
npx wrangler login

# 2. (Optional) Add your private ndus cookie
npx wrangler secret put NDUS_COOKIE

# 3. Deploy
npx wrangler deploy
```

---

## How to Connect to Cloudstream

1. Open Cloudstream on your device.
2. Go to **Settings > Extensions > iTeraPlay > Settings**.
3. In the **"Custom Worker Gateway"** field, paste your worker URL:
   ```
   https://terabox-gateway.<your-subdomain>.workers.dev
   ```
4. Click **Save**.

The provider will now automatically route multi-quality 1080p, 720p, and 480p streams through your private worker!
