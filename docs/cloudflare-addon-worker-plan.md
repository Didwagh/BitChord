# Cloudflare Workers for Mornye providers — feasibility plan

Living guideline, not an ultimatum. The user answered the shaping questions on
2026-11-19; if a better host or shape is found later, this doc changes with it.

**Decisions so far:** targets are Qobuz → Tidal → Amazon; preferred shape is a
thin shim addon + Worker; Free Workers only; credentials may live in Worker
Secrets because this is a personal addon; priority is no frequent session
pasting, no APK changes, best debugging, and lowest complexity/cost; Worker logs
are redacted summaries. Refresh cadence is still unknown and must be measured.

## 0. Goal and non-goals

**Goal:** move provider-specific breakage (Tidal 401, Amazon search/keys/logs,
"what to activate", session churn) out of the Android app and into something we
own and can fix without shipping a new APK.

**Non-goals:**
- No new competing playback queue in BitChord.
- No false lossless claims; bytes/decoder still decide codec and quality.
- No secrets, signed URLs, callback state or raw tokens in logs.
- No `versionCode`/`versionName` bump for an addon-only change.

## 1. What is actually running today (ground truth)

- Addons are `.sflx` JS packages executed on-device. Host API is bridged through
  `MorneyeHostBridge.kt:83,93-94` (`http.request`, `file.download`,
  `file.downloadSegments`) with JS shims in `MorneyeBootstrap.kt:92,261-262`.
- An addon is called by method name via `MorneyeRuntime.kt:79`.
- One `SourceConfig` is created per installed package; order is install order
  (`SourceRegistry.kt:604-635`, persisted by `MorneyeScreen`). **A Worker-backed
  addon is therefore just another installed addon — no app source change is
  needed to use it.**
- Signed sessions are negotiated by the app with the zarz gateway:
  `MorneyeSignedSession.kt:306-361` (bootstrap/challenge) and
  `:829-852` (`bootstrap`, `challenge`, `refresh` paths). Diagnostics (13) shows
  all three sessions connected at `:44-46,150-152`.
- Amazon CENC is solved **on device**: parse `tenc`/`seig` KID from the cached
  MP4 and provision ClearKey (`mornye-amazon-cenc-report.md:45-57`).
- Addon logs arrive in app diagnostics through `TrackLog`; Worker logs would not
  unless the shim deliberately forwards a small redacted status line.

## 2. Candidate shapes

### Shape A — thin shim addon + Worker for metadata/signing only (start here)

One small `.sflx` per provider (or one multi-provider shim) implements the
Mornye methods by POSTing to our Worker. The Worker owns provider API details,
secrets, retries and health; the app keeps its matcher, cache, queue, byte
sniffing and ClearKey path unchanged.

- Worker returns: search rows, a playable stream URL / segment list, an optional
  decryption key, and measured/claimed metadata.
- Shim calls `file.download(url, path)` or `file.downloadSegments(...)`; bytes
  never pass through Cloudflare.
- **Pros:** no APK change; fixes the "we must modify the app" problem; easy to
  hotfix; keeps large media egress free.
- **Cons:** still one shim install; Worker must be reachable; adds one HTTP hop
  to search/lookup (not to audio).

### Shape B — proxy only the provider's own HTTP through the Worker (smallest change for Tidal)

Keep the existing addons, but point their configurable metadata/download URLs at
our Worker (e.g. Tidal `metadataApiUrl` / `customEndpoint`, Amazon
`showSearch`/signed download API). The Worker holds the token/cookie and proxies
with a stable interface.

- **Pros:** least code; addon logic untouched; directly addresses Tidal's
  `401` from `tidal.com/v1/search/tracks` (`DIAG13:61,82-92`) if the token is
  the problem.
- **Cons:** brittle if addon bundles the URL rather than reading config;
  Amazon's 1.3 MB `showSearch` responses (`DIAG13:531`) are CPU-heavy on a
  Free Worker; does not by itself fix the app-side matcher bug.

### Shape C — full provider reimplementation in a Worker

The Worker implements search, URL parsing, quality selection and download
resolution from scratch; the shim is almost empty.

- **Pros:** one place to fix everything; cleanest long-term.
- **Cons:** largest rewrite; we must re-earn all byte-truth and quality rules;
  Amazon CENC still needs host-side ClearKey unless we additionally rewrite MP4
  boxes in the Worker (possible with WebCrypto AES-CTR, but CPU/memory risky).

### Shape D — app-side HTTP music source instead of addons

Not preferred: it requires changing `SourceRegistry`/`SourceResolver` and
shipping an APK, which is exactly what the user wants to avoid. Keep as a
fallback if the shim contract proves too narrow.

## 3. Per-provider reality check

| Provider | Today | Worker helps with | Worker cannot honestly fix |
|---|---|---|---|
| Qobuz | `HI_RES_LOSSLESS` FLAC works; DNS blip only (`DIAG13:145-147`) | session refresh, retry/mirror policy, health | upstream catalog gaps |
| Tidal | signed session OK, but every metadata search is `401` (`DIAG13:61,82-92`); `/dl/tid` 503 was the next gate in (12) | token/cookie ownership, proxy, refresh, bounded retries, mirror choice | Tidal revoking a token/account, provider 503/403 |
| Amazon | search + CENC FLAC works when a row is chosen (`DIAG13:640-675`); strict matcher bug fixed in `28e5cc61`; timeouts/503 upstream | huge/timeout-prone search, signed download API, key delivery, structured logs | provider outages; `showSearch` carrying no runtime (matcher already handles it) |

## 4. Session taxonomy — the important question

"Session" can mean three different things. They need different solutions:

1. **Mornye/zarz signed session** (app → gateway). App currently bootstraps and
   refreshes it (`MorneyeSignedSession.kt:306-361,829-852`). If the Worker owns
   it, a refresh token plus a Cron Trigger can keep it warm without user action
   — *if* the refresh token is long-lived. Need the actual lifetime.
2. **Provider account session** (Tidal/Amazon login cookies). If the Worker is
   to act as the user's account, those cookies must live in Worker Secrets.
   Expiry cadence decides whether "paste once in a while" is viable.
3. **Public/static web token** embedded in an addon's settings (Tidal's
   `x-tidal-token`, per `mornye-tidal-plan.md:44,50`). Rotating it means editing
   addon config or Worker env, not the app.

Acceptable cadence from the user: occasional paste is fine; **daily or weekly is
not**. So the plan must automate refresh (2) where possible, or choose public
endpoints that need no personal session.

## 5. Cloudflare constraints that matter here

From Cloudflare's published limits (checked 2026-11-19):

- Free: **10 ms CPU/request**, 100k requests/day, 128 MB memory, 50
  subrequests, 6 simultaneous outgoing connections.
- Paid ($5/mo account minimum): 30 s default CPU (up to 5 min), no daily request
  cap, 10k subrequests. Memory stays 128 MB.

Implications for the chosen **Free-only** constraint:
- Parsing Amazon's ~1.3 MB `showSearch` JSON (`DIAG13:531`) can plausibly blow
  the 10 ms CPU budget. Design Amazon as several small endpoints with cached,
  pre-shaped results; do not ship a single "search + parse + sign" mega-route.
  If that proves impossible, revisit Free vs Paid rather than pretending the
  limit is not there.
- Cron Triggers exist on Free, but scheduling is coarse. Use them as a
  keep-warm/refresh aid, not as a guarantee of minute-level session renewal.
- Do not buffer whole FLAC files in a Worker. Return URLs; let `file.download`
  stream to the device.
- Keep CENC decryption on-device; it already works and avoids MP4 box rewriting
  under 128 MB memory.
- Secrets go in Worker env/Secrets, never in code, logs, or URLs.
- Qobuz has a shortcut: its addon already supports a `customEndpoint` for a
  `qobuz-rest-api`-compatible server (`qobuz-web/manifest.json:143-151`,
  `index.js:418-463`). Pointing that setting at a Worker may prove the whole
  idea with **no shim and no APK change**. Search still falls back to the addon's
  own public Qobuz API (`index.js:1231,1320,2157-2195`), so this first proof is
  about download resolution and health, not eliminating every session yet.

## 6. Logging and "what to activate"

- Worker: structured records with `requestId`, provider, method, status,
  duration, redacted host/path — never tokens, signed URLs or callback state.
- Forward a one-line summary back through the shim into `TrackLog` so app
  diagnostics still shows *why* a source was skipped.
- Activation: install only the shims we trust; disable the originals; let
  install order decide rank. A Worker `/health` endpoint can be polled by the
  shim and its result forwarded as a redacted diagnostics line. Reporting it as
  formal `SourceHealth.Unreachable` would need an app change, so that is **not**
  part of the no-APK plan.
- No auto-enabling of a provider just because it exists.

## 7. Answers and decisions (2026-11-19)

| Question | Answer | Consequence |
|---|---|---|
| Three addons | Qobuz, Tidal, Amazon | Scope is fixed; Deezer is out for now. |
| "Session" concern | All three kinds | Plan must cover zarz, provider logins, and public tokens. |
| Refresh cadence | Not sure | Measure actual token lifetimes before promising "no pasting". |
| Cloudflare plan | Free only | Split routes, cache aggressively, avoid heavy JSON/Crypto in one request. |
| Credentials | Worker Secrets acceptable | Personal addon only; still never log or echo them. |
| Shape | Thin shim + Worker | New shim manifests should omit `signedSession` where possible so the Worker, not the phone, owns provider sessions. |
| First proof | Qobuz | Try the existing `customEndpoint` first; only build a shim if that is insufficient. |
| Priority | No frequent pasting, no APK changes, debugging, low cost | Worker health + redacted logs are part of the minimum, not extras. |
| Logs | Redacted summaries | `provider`, `method`, `status`, `duration`, `errorClass`; never URL/token/callback state. |

### Session plan, concretely

1. **zarz/Mornye session:** the cleanest fix is to avoid it. A new shim whose
   manifest does not declare `signedSession` will not trigger gateway
   bootstrap/challenge (`MorneyePackageManager.kt:277-284,420`,
   `MorneyeSignedSession.kt:837`). The Worker then owns provider calls with its
   own secrets. This is the main reason to prefer a shim over proxying the
   existing addons verbatim.
2. **Provider account cookies:** store in Worker Secrets. If the provider offers
   a refresh token, refresh in a small Cron route and write the new value back
   to Workers KV/Secret metadata. If it does not, record the true expiry during
   the Qobuz/Tidal proof and decide whether occasional paste is acceptable.
3. **Public tokens (Tidal `x-tidal-token`):** keep in Worker Secrets so a rotation
   is one Worker edit, not an APK or addon release. A rotated/revoked token or a
   changed provider API still cannot be fixed by BitChord code alone.

## 8. Decision gates

- **Gate 0 — source inventory (done for 3/4):** `.sflx` files are available at
  `/storage/emulated/0/Download/{qobuz-web,tidal-web,deezer}.sflx`. Amazon's
  package is **not** in that directory and must be exported or reinstalled
  before it can be ported.
- **Gate 1 — Qobuz proof (standalone addon ready):** the Worker source is at
  `BitChord/worker/bitchord-nexus/src/index.js` and the stock-app addon source
  is at `BitChord/worker/bitchord-nexus/addon/qobuz-worker/`. The Worker adds
  `/qobuz/search`, `/qobuz/track/:id` and
  `/download-url/{id}?quality=hi96|hi24|flac`, maps qualities to Qobuz formats
  `27/7/6`, and uses redacted status-only logs. The `.sflx` omits
  `signedSession` entirely, so the stock app needs no zarz session and no fork.
  Local checks: `cd worker/bitchord-nexus && npm test` → 11/11 pass, including
  fake-signed-URL log assertions and a VM run of the addon against mocked host
  globals. Deploy Worker `1.8.0`, then install the generated
  `bitchord-qobuz-worker.sflx`; no app setting or pasted credential is needed.
- **Gate 2 — Tidal:** Worker owns `x-tidal-token` and the download API call.
  Compare diagnostics against the `401` capture in (13). Do not loosen matching
  or fake a format to work around upstream auth.
- **Gate 3 — Amazon:** only after Gate 1/2. Split search and download resolution
  into small routes; keep CENC ClearKey handling on-device.
- **Gate 4 — app untouched:** keep `main` free of Worker-specific app changes.
  The only app-side change already pushed is `28e5cc61` (Amazon unstated-runtime
  strict matching).
- **Gate 5 — device proof:** install the addon/Worker config, test the five
  songs, compare diagnostics, and verify cache/copy/export/share still work
  byte-for-byte.

## 9. What must stay true

- Worker returns metadata and URLs; **media bytes never proxy through
  Cloudflare**. The phone's `file.download` and cache remain authoritative.
- Bytes and decoder decide codec/bitrate/quality. A Worker claiming "FLAC" does
  not make a file FLAC.
- A Worker status line may enter diagnostics only through a redacted shim log.
- Activation is manual: install the shim(s), disable the originals, and let
  install order decide rank. No auto-enable based on Worker health.
- Free-tier failure is a signal to simplify or reconsider the host, not to add
  unbounded retries or false labels.

## 10. Qobuz adapter deployment checklist

1. Deploy `worker/bitchord-nexus/` (worker version `1.8.0`). Do **not** commit
   provider URLs or secrets; `wrangler.toml` ships with empty values.
2. In Worker Variables set at least `QOBUZ_PRIMARY_BASE_URL` and
   `QOBUZ_UPSTREAM_BASE_URL`. Use `wrangler secret put LW_SECRET` only if
   LastWave is wanted; the old built-in secret was removed.
3. Confirm `GET /health` reports `"version":"1.8.0"` and
   `"routes":{"downloadUrl":true,"addonQobuz":true}`.
4. Build the stock addon:
   `cd worker/bitchord-nexus/addon/qobuz-worker && zip -q -X bitchord-qobuz-worker.sflx manifest.json index.js`.
5. Install that `.sflx` in the stock app. There is no Connect step, no session
   paste and no custom endpoint to fill in; the Worker URL is already the
   default setting. Disable the old `qobuz-web` addon to avoid duplicate rows.
6. Play one known Qobuz track and check diagnostics: the addon should resolve
   search and download URL through the Worker, and the decoded file must still
   decide whether the result is 16-bit or 24-bit FLAC.
7. Run `npm test` in `worker/bitchord-nexus/` after any logging change. The test
   deliberately fails if a signed URL or its query string appears in a log.

**API-upload warning:** a Cloudflare `PUT` that sends only `index.js` erases all
existing bindings. Read `GET .../scripts/bitchord-nexus/settings` first and send
its `bindings` array back with the new module. Never print those values.

### Deployment result (2026-11-19)

The sanitized `v1.7.1` module was deployed first and is the version that
established the restored bindings. Compatibility date `2026-11-02` was
preserved. The standalone-addon API in this repo is `v1.8.0` and is **not yet
live** at the time of writing; it must be deployed before installing the
`.sflx`, because `/qobuz/search` does not exist on `v1.7.1`.

The old script had **no bindings**; its provider values were source defaults.
Those six values were restored as `plain_text` bindings, and the old built-in
LastWave HMAC secret was restored as a `secret_text` binding named `LW_SECRET`.
Only binding *names* are recorded here — never their values.

Live provider statuses at deploy time (statuses only): `ax 401`, `lw 200`,
`qb_primary 503`, `qb_fallback 200`, `mc 200`, `hf 503`.

Reference source used: `/storage/emulated/0/Download/bitchord-nexus-worker (1).zip`.
The deployed worker previously reported `v1.7.0`; the repo copy is a sanitized
`v1.8.0` variant, not a blind overwrite.
