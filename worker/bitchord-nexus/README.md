# BitChord Nexus — Cloudflare Worker

A sanitized variant of the personal Nexus worker, with one added route that
matches the `qobuz-web` addon's built-in `customEndpoint` setting.

No app source change is required: this proves the Worker idea using addon
configuration only.

## What changed from the downloaded reference

- Added `GET /download-url/{id}?quality=hi96|hi24|flac`.
  - `hi96` → Qobuz format `27` (24-bit hi-res)
  - `hi24` → Qobuz format `7` (24-bit)
  - `flac` → Qobuz format `6` (16-bit CD lossless) — **never** the MP3 rung
- Added `/health.routes.downloadUrl` and bumped worker version to `1.7.1`.
- Removed hard-coded provider URLs and the built-in LastWave signing secret.
  `LW_SECRET` must now come from Worker config.
- Rewrote logging so it never emits full URLs, URL prefixes, query strings,
  response bodies, tokens, secrets, or signed paths.

The response shape is exactly what `qobuz-web/index.js:2717-2751`
(`parseDownloadInfo`) expects:

```json
{
  "url": "https://...",
  "bit_depth": 24,
  "sampling_rate": 96000,
  "quality": 27
}
```

Only fields the upstream actually states are included. The Android app still
decides the real codec from the downloaded bytes and the decoder.

## Deploy

```sh
npm install
npm run deploy
```

Then set these in **Cloudflare Dashboard → Workers & Pages → bitchord-nexus →
Settings → Variables**. Do not put them in `wrangler.toml` or any committed file:

- `QOBUZ_PRIMARY_BASE_URL`
- `QOBUZ_UPSTREAM_BASE_URL`
- `LW_SECRET` (only if LastWave is wanted; use `wrangler secret put LW_SECRET`)
- other provider roots only if those providers are wanted

Check the result:

```sh
curl https://bitchord-nexus.<subdomain>.workers.dev/health
```

Expected: `"version":"1.7.1"` and `"routes":{"downloadUrl":true}`.

## Connect the Qobuz addon

In BitChord → Addons → Qobuz → settings:

- **Custom download endpoint**: `https://bitchord-nexus.<subdomain>.workers.dev`
- **Custom endpoint API key**: leave empty unless you add one yourself

The addon calls `{endpoint}/download-url/{id}?quality=...` before its default
download providers. Search and metadata still use the addon's existing paths.
That is intentional for this first proof: it isolates download resolution and
health from the session question.

## Local checks

```sh
npm test
```

The test mocks upstream Qobuz with a fake URL containing a fake signature and
asserts:

- `hi96`, `hi24`, and `flac` map to `27`, `7`, and `6`;
- the returned JSON has the addon's expected field names;
- no emitted log line contains the fake signed URL, its query string, or a URL
  prefix.

## Logs

`npm run tail` shows structured summary lines only: provider tag, method,
status, byte count, duration, quality, and track-id length. If a line would need
a URL to be useful, that is a sign the debugging belongs in the upstream
provider, not in persistent Worker logs.
