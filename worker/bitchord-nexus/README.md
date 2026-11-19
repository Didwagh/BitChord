# BitChord Nexus — Cloudflare Worker

Two deliverables live here:

1. `src/index.js` — the personal Cloudflare Worker. It holds provider config and
   secrets, and exposes a small API for Qobuz search and lossless download URLs.
2. `addon/qobuz-worker/` — a standalone `.sflx` addon that installs in a stock
   Mornye-compatible app. It has **no** `signedSession`, no fork-only settings,
   and no credentials of its own. It calls the Worker for everything.

## Worker API

| Route | Purpose |
| --- | --- |
| `GET /health` | Version and provider status summaries only |
| `GET /qobuz/search?q=...&limit=...` | Qobuz catalog rows in stock addon shape |
| `GET /qobuz/track/:id` | One remembered row, or a fresh catalog lookup |
| `GET /download-url/:id?quality=hi96\|hi24\|flac` | Signed HTTPS download URL |
| `GET /search`, `GET /stream/:id` | Existing BitChord multi-provider routes |

### Provider priority

`/search` and `/stream` use this fixed order:

1. Qobuz (`qb:`)
2. Monochrome (`mc:`)
3. LastWave (`lw:`)

Aurix (`ax:`) and HiFi/TIDAL (`hf:`) are **disabled**: their upstreams were
returning 401/503, so they are no longer contacted. Their Worker bindings are
left intact, and `/health` reports them as `status: "disabled"`. Dedup keeps the
earlier provider on a tie; quality score is only a tie-breaker inside the same
provider.

`/download-url` first tries the original `/api/download-music` proxy shape. If
that fails, it also accepts a `qobuz-rest-api` compatible server at
`QOBUZ_UPSTREAM_BASE_URL`, calling `/download-url/{id}?quality=hi96|hi24|flac`.
That makes it possible to point the existing Worker binding at a working
self-hosted endpoint without changing the APK or pasting a session.

Quality mapping on `/download-url`:

- `hi96` → Qobuz format `27` (24-bit hi-res)
- `hi24` → Qobuz format `7` (24-bit)
- `flac` → Qobuz format `6` (16-bit CD lossless) — **never** the MP3 rung

`/qobuz/search` returns only Qobuz rows, never a Tidal/LastWave result wearing
a Qobuz badge. It first asks the configured Qobuz proxies; if they are down or
unauthenticated, it falls back to Qobuz's public metadata endpoint used by
`qobuz-web`. That fallback supplies catalog rows only — it cannot sign a full
lossless download. Catalog fields are copied only when the upstream states them.
The downloaded bytes and the device decoder still decide the real codec,
bit depth and sample rate.

## What changed from the downloaded reference

- Added the standalone addon API above and bumped the worker to `1.8.3`.
- Removed hard-coded provider URLs and the built-in LastWave signing secret.
  Provider values live in Worker Variables/Secrets, not in source.
- Rewrote logging so it never emits full URLs, URL prefixes, query strings,
  response bodies, tokens, secrets, signed paths, titles or search text.

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

Expected: `"version":"1.8.3"`,
`"routes":{"downloadUrl":true,"addonQobuz":true}`,
`"enabled":["qb","mc","lw"]` and `"disabled":["ax","hf"]`.

### Important: preserve bindings on API upload

A raw `PUT /workers/scripts/bitchord-nexus` that only sends `index.js` will
**remove every existing binding**. When deploying through the Cloudflare API,
first `GET .../scripts/bitchord-nexus/settings`, reuse the `bindings` array from
that response in the upload metadata, then upload the module. Never print or
commit those values.

## Standalone addon (stock app)

Source: `addon/qobuz-worker/{manifest.json,index.js}`.

Build and install:

```sh
cd addon/qobuz-worker
zip -q -X bitchord-qobuz-worker.sflx manifest.json index.js
```

Install `bitchord-qobuz-worker.sflx` in the stock app's addon/extension screen.
There is no Connect/verification step and nothing to paste. The addon's only
setting is the Worker URL, which already defaults to the live personal Worker.

What the addon does:

- `customSearch`/`searchTracks` call `/qobuz/search`;
- `download` calls `/download-url/...` with a lossless-only fallback chain;
- decoder-measured `bit_depth`, `sample_rate` and `format` are reported when the
  host provides them; when it does not, they are omitted rather than guessed;
- preview/short downloads are rejected rather than cached as full tracks.

### Legacy fork-only route

If an older BitChord fork already has `qobuz-web` installed, its **Custom
download endpoint** can also be pointed at the Worker root. That route still
exists for compatibility, but the standalone addon above is the intended
deliverable because it needs no fork.

## Local checks

```sh
npm test
```

`node --test` runs both suites:

- Worker tests mock upstream Qobuz and assert quality mapping, response shape,
  and that a fake signed URL never reaches a log line.
- Addon tests run the `.sflx` JavaScript in a VM with mocked host globals and
  assert stock contract fields, lossless-only fallback, and leak-free logs.

## Logs

`npm run tail` shows structured summary lines only: provider tag, method,
status, byte count, duration, quality, and track-id length. If a line would need
a URL to be useful, that is a sign the debugging belongs in the upstream
provider, not in persistent Worker logs.
