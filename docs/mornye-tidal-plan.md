# Tidal Mornye integration — reverse engineering + implementation plan

Internal planning doc. Short forms are intentional; it is for BitChord agents, not end users.
Line numbers are from the inspected snapshots; grep before relying on an exact offset.

## Path aliases

| Alias | Path |
|---|---|
| `TD/index.js`, `TD/manifest.json` | `/data/data/com.termux/files/usr/tmp/opencode/tidal-web-ext/` |
| `DZ/...` | `/data/data/com.termux/files/usr/tmp/opencode/deezer-ext/` (cross-addon notes only) |
| `LOK/...` | `/data/data/com.termux/files/usr/tmp/opencode/lokal/` |
| `BC/...` | `/data/data/com.termux/files/home/BitChord/` |
| registry | `/data/data/com.termux/files/usr/tmp/opencode/mornye-registry.json:145-164` |

## Status legend

`DONE` = present in current checkout. `PARTIAL` = generic plumbing exists but provider path not exercised/proven. `TODO` = must implement. `BLOCKED` = upstream/external failure, not fixable by us honestly.

## 0a. Current diagnosis — `bitchord-diagnostics (13).txt` (2026-11-19)

This is a newer capture than the `/dl/tid` 503 evidence in diagnostics (12).
It shows a **different, earlier gate**:

- `DIAG13:36,43,46,150-152` — `tidal-web v1.2.15` is installed and its signed
  session connects (`18:45:39`, still reported connected at `18:56:25`).
- `DIAG13:61,82-92,164-182,263-274,316-346,509-530,591-614,698-701,710-739` —
  every `GET https://tidal.com/v1/search/tracks?...deviceType=BROWSER...`
  answers `401 84b`; the addon logs `searchTracks error: HTTP 401 …` and returns
  an empty list.
- `MorneyeSource.kt:188-211` treats that throw as an empty search;
  `SourceResolver.kt:1222-1244` then logs "Tidal has no match" with **zero**
  candidates. No `download()` is ever invoked on this capture.
- Consequently there is no `/dl/tid` 503 in (13): the download API is never
  reached. The 503 in (12) remains a real second gate for whenever search works,
  and is already classified `BLOCKED` at §1.6 / §3 / §6.

**Classification:** `BLOCKED (upstream auth)` for the addon's public web
metadata token, not a local matcher or queue bug. The token setting is already
called out at §1.2 (`TD/index.js:232-238,257-283`) as a redacted public web
token; a rotated/revoked token or an API change must be fixed in the addon,
not by loosening BitChord's matching or by faking a lossless source. A reconnect
or a newer `tidal-web` package is the honest remedy. Do not add infinite 401
retries — the resolver's bounded source failure/fallthrough is the right local
behaviour.

## 0. Verdict / OPA

- **DONE (generic):** BitChord can install/run `.sflx`, show Mornye rows in Addons tab, call `download()`, join DASH segments, cache bytes, sniff codecs, and publish measured Stats.
- **DONE (Tidal-shaped helpers):** `file.downloadSegments` is implemented (`BC/.../MorneyeHostBridge.kt:274-336`); Tidal metadata aliases are parsed (`MorneyePackageModel.kt:107-115,119-130,176-179`); cache rejects stray DASH manifest XML (`AudioCache.kt:1148-1164`).
- **PARTIAL:** signed-session client exists and explicitly names Tidal (`MorneyeSignedSession.kt:22-47`), including 503 `PROVIDER_UNAVAILABLE` bounded retry (`:221-295`), but no `@1` vs `@3` branch and no Tidal end-to-end proof.
- **TODO:** availability/enrichment pass, `handleURL`, quality-fallback gating, container-conversion/decoder decision, explicit requested-vs-resolved Stats, source labels/ranking, and five-song device proof.
- **BLOCKED historically:** gateway `/dl/tid` returned `503 PROVIDER_UNAVAILABLE`; public fallback `403`; Turnstile-style verification may require a grant. Do not call these local bugs.
- **Assumption:** Amazon/ClearKey works per user instruction; out of scope here.

## 1. What the Tidal addon actually does

### 1.1 Manifest contract

- `TD/manifest.json:2-11`: `name=tidal-web`, `displayName=Tidal`, `version=1.2.14`, `type=[metadata_provider,download_provider]`, `minAppVersion=4.9.0`.
- `TD/manifest.json:12-16`: required features `signedSession@3`, `sessionGrant@1`, `downloadSegments@1`. BitChord accepts all three (`BC/.../MorneyePackageManager.kt:56-61`).
- `TD/manifest.json:17-31`: same `zarz-v2` HMAC scheme as Deezer, `appVersion=tidal-web@1.2.5`, 300 s window, endpoints `/bootstrap`, `/challenge`, `/session/exchange`, `/session/refresh`.
- `TD/manifest.json:45-76` quality options:
  - `DOLBY_ATMOS` (`kind:spatial`) — require Atmos, else best FLAC.
  - `HI_RES_LOSSLESS` (`kind:lossless`) — prefer 24-bit, fall back to 16-bit Lossless.
  - `LOSSLESS` (`kind:lossless`) — no compressed fallback.
  - `HIGH`, `LOW` (`kind:lossy`).
- `TD/manifest.json:77-102`: fallback tier `lossless`, timeout 15 s, `requiresNativeContainerConversion:true`, transfer contract (4 attempts, 500–8000 ms backoff, validated resume, checkpoint, refresh on 401/403, maxParallelSegments 4, maxConcurrentDownloads 2), `replacesBuiltInProviders:["tidal"]`, share templates, related tracks.
- `TD/manifest.json:103-114`: required health check `https://api.zarz.moe/v1/health`, `serviceKey=tidal`, 4 s timeout, 60 s cache. `PROVIDER_UNAVAILABLE` is a host/gateway concept; addon string does not contain it.
- `TD/manifest.json:115-151`: search enabled, `primary:false`, filters; URL patterns `tidal.com`, `www.tidal.com`, `listen.tidal.com`, `tidal:`.
- `TD/manifest.json:152-195`: settings include redacted public web token (do not reproduce), `countryCode=US`, `locale=en_US`, `deviceType=BROWSER`, `downloadApiUrl=https://api.zarz.moe/v1/dl/tid2`, optional `customEndpoint` with local-network grant.

### 1.2 Search → row shape

- Registered methods `TD/index.js:2871-2886`: `initialize`, `cleanup`, `completeGrant`, `customSearch`, `checkAvailability`, `download`, `handleUrl`, `getTrack/Album/Artist/Playlist`, `enrichTrack`, `searchTracks`, `getRelatedTracks`.
- `customSearch()` `TD/index.js:2385-2413`: limit default 20 capped 50; unfiltered fans out track(limit)+artist(5)+album(5)+playlist(5) (`:2402-2407`); errors return `[]` unless verification required (`:2408-2411`).
- `searchEndpoint()` `TD/index.js:2049-2059` GETs `search/<kind?query&limit&offset=0>`; `buildMetadataURL()` `:257-283` appends `countryCode/locale/deviceType`; headers include `Accept`, randomized UA, `x-tidal-token` (`:232-238`). Token value redacted in docs/logs.
- `formatTrack()` `TD/index.js:1698-1757` emits: `id=spotify_id="tidal:<num>"` (`:1722-1723`), `tidal_id` raw (`:1724`), `name`, `artists`, `album_name`, `album_artist`, `artist_id`, `album_id/url`, `duration_ms`, `cover_url`, `images`, `release_date`, `track_number`, `total_tracks`, `disc_number`, `total_discs`, `isrc`, `provider_id:"tidal-web"`, `item_type:"track"`, `external_links.tidal_track/tidal_album`, `genre`, `label`, `copyright`, `composer`, `comment` (album URL), `explicit`, `audio_quality`, `audio_modes`.
- Artwork `imageURL()` `TD/index.js:190-195` → `https://resources.tidal.com/images/<id-with-slashes>/<size>.jpg`; 1280 cover, 750 artist, `origin` playlist (`:1733-1734,1848,1864`).
- `hydrateRawTrack()` `TD/index.js:1975-2010` enriches label/composer; metadata richness varies by helper success (`tryMetadataFetch` `:1966-1973`).
- Match ranking inside addon: `titlesMatch` `:656-692`, `artistNamesMatch` `:711-731`, `durationMatches` ±10 s `:767-772`, `selectBestSearchTrack` `:1444-1473` (ISRC first, then title/artist/duration). Reuse for BitChord matching if we add availability pass.
- `provider_id:"tidal-web"` on all formatted rows (`:1741,1787,1835,1856,1872,2315,2335`); playlists use raw UUID without prefix (`:1866,2329`) — inconsistent namespace, key on `(configId,id)`.

### 1.3 URL / ID → acquisition

- `parseURL()` `TD/index.js:501-546`: accepts `tidal:<type>:<id>`, `tidal://<type>/<id>`, and http(s) only on tidal hosts; optional `browse` segment; only track/album/artist/playlist.
- Numeric parse `:459-486` accepts bare digits, `tidal:<digits>`, `<type>/<digits>`; playlist UUID parse `:488-499`. Prefix helpers `:171-181`.
- `handleUrl()` `TD/index.js:2806-2862`: track `{type:"track",track}`, album `{type:"album",tracks}`, artist `{type:"artist",artist,albums,albums_next}`, playlist `{type:"playlist",tracks}`; failures `{success:false,error}`.
- **BitChord does not call `handleUrl` today** (zero call sites). Search-row playback works because `download()` self-resolves metadata; direct URL pasting is a TODO.
- Signed session consumption `signedJSON()` `TD/index.js:325-349` is richer than Deezer: consumes `error`, `needsVerification`, `statusCode`, `body`, `headers` (case-insensitive `Retry-After`), `retryAfterSeconds`, `retryMode`, `retryable`, `code` (`:329-348`; helpers `:351-371`).
- Ticket `signedTicket("tid","track",id)` `TD/index.js:404-432`: hash `sha256("tid:track:"+lowercase(id))`; POST `/tickets` with `capability:"download_ticket"`; reads `ticket_id||ticket`. Long 429 sets global block 60 s–15 min (`:395-425`); subsequent calls throw `RESOLUTION_TIMEOUT`.
- `postDownloadAPI()` `TD/index.js:1017-1023`: signed POST to `CONFIG.downloadAPIURL` with `X-Zarz-Ticket`; inline ticket if absent.
- **Path normalization gotcha:** `CONFIG.downloadAPIURL` default is `/dl/tid` (`TD/index.js:4`), but manifest setting default is `https://api.zarz.moe/v1/dl/tid2`. `initialize()` `:58-64` rewrites any `https://api.zarz.moe/...` value to `/dl/tid`; non-Zarz absolute custom endpoints are kept. This is why logs say `/dl/tid`.
- Locale/country/device are only in metadata URLs (`TD/index.js:262-264`), not in download/ticket requests.
- No relogin inside addon; host handles bootstrap/challenge/exchange/refresh and `completeGrant()` (`TD/index.js:2864-2869`). `needsVerification` → `error_type:"verification_required"` (`:2798`).

### 1.4 Download: direct vs manifest segments

- `fetchAPIDownloadInfo()` `TD/index.js:1063-1101`:
  - Atmos: POST `{id,endpoint:"manifests",formats:["EAC3_JOC"]}` (`:1025-1031`), require `attributes.formats` contains `EAC3_JOC` (`:1041-1051`), fetch `attributes.uri` MPD text (`:1053-1060`), parse; set `audioMode/audioQuality="DOLBY_ATMOS"`, sample rate fallback 48000. `attributes.hash` is fetched but unused.
  - Other: POST `{id,quality}`, reject `PREVIEW` (`:1086-1088`), require base64 `data.manifest` (`:1089-1091`), parse, copy `audioMode`, `audioQuality`, `bitDepth`, `sampleRate`, `manifestMimeType`.
- `parseManifestText()` `TD/index.js:904-951`:
  - JSON "BTS" manifest → `{kind:"direct",directURL:urls[0],manifestMimeType}` (`:906-916`).
  - DASH MPD → regex `initialization`, `media`, `audioSamplingRate`, `<S d r>` counts; expand `$Number$` to 1..N; returns `{kind:"manifest",initURL,mediaURLs[],manifestMimeType:"application/dash+xml",sampleRate}` (`:926-950`). `&amp;` unescaped `:900-902`.
- Mirror/custom endpoint: `mirrorRequestURL()` `:1103-1107` (`<base>/track/?id=&quality=`); `fetchMirrorDownloadInfo()` `:1113-1187` accepts array of `{OriginalTrackUrl}` or API-like `{data.manifest}`; array path hardcodes `bitDepth:16,sampleRate:44100,manifestMimeType:"audio/flac"` (`:1151-1153`) — **false-lossless risk**. Rejected candidates tracked `:1109-1111,1121-1125`.
- Retry/fallback `fetchDownloadInfo()` `TD/index.js:1219-1319`: candidates from `buildFallbackQualities()` `:1006-1015`; up to 5 attempts (`:17`), 250–4000 ms backoff + jitter (`:18-19,383-393`), honours `retryAfterMs/retryMode` (`:1275-1284`), resolution budget (`:373-381`), cancellation (`:1237-1239`). **Brittle gate at `:1303-1308`:** if a tier fails without deterministic `TRACK_NOT_FOUND`/`QUALITY_UNAVAILABLE`/etc. confirmation (`:1189-1199`), it throws before trying the next tier. A plain `HTTP 503 for /dl/tid` is non-deterministic, so Atmos→FLAC fallback does not happen.
- `download()` `TD/index.js:2658-2804`:
  - reuse `preparedContext` only when `track_id` matches (`:2661-2668`); else fetch + hydrate (`:2669-2675`).
  - resolve quality via `resolveDownloadQualityForTrack()` `:1592-1618`; ISRC dedupe via `gobackend.checkISRCExists()` `:2678-2688`.
  - progress 5 → 10–90 transfer → 94 validation → 100 lyrics (`:2690,2711,2763,2779`).
  - output extension from `inferOutputExtension()` `:872-879`: `.m4a` for DASH/mp4/m4a/HIGH/LOW/Atmos, else `.flac`.
  - transfer attempts max 3 (mirrors) or 2 (`:2697,2700`), delete output before each (`:2707`), dispatch direct vs segments (`:2710-2713`).
  - transfer failure deletes partial, detects cancel, rejects bad mirror, refreshes `expired_stream` once (`:2716-2741`).
  - validation: `getAudioQuality` + duration ±10 s; `actual<=35s && expected>45s` treated as preview, delete + retry once (`:2743-2761`, helpers `:1359-1389`).
  - final fields prefer measured values, else manifest info (`:2765-2776`); lyrics `:2778`; result `:2782-2792`.
- `downloadDirectFile()` `TD/index.js:1330-1346`: `file.download(url,out,{headers:{User-Agent},resume:true,persistentCheckpoint:true,trackItemBytes:true,onProgress(written,total)})`.
- `downloadManifestSegments()` `TD/index.js:1404-1424`: `file.downloadSegments([initURL,...mediaURLs],out,{headers,maxParallel:4,persistentCheckpoint:true,onProgress(written,total,completed,totalSegments)})`. Order is init first then `$Number$` 1..N; host merges.
- **No addon crypto:** no ffmpeg/openssl/CENC/PSSH/KID/decrypt in `TD/index.js`. If encrypted segments appear, addon cannot handle them; only the BitChord ClearKey path would, and only when a key is returned (it isn't for Tidal).

### 1.5 Quality detection / false-lossless risks

- `normalizeDownloadQuality()` `TD/index.js:832-846`: Atmos aliases, HiRes aliases, FLAC→LOSSLESS, default LOSSLESS.
- `isDolbyAtmosInfo()` `:957-961`, `isHiResInfo()` `:963-971`, `isLosslessInfo()` `:973-981`, `isCDLosslessInfo()` `:983-996`, `satisfiesQuality()` `:998-1004`.
  - `satisfiesQuality(HI_RES_LOSSLESS)` calls `isLosslessInfo`, so a CD FLAC satisfies HiRes; downgrade label recorded as `resolvedQuality:"LOSSLESS"` (`:1247-1250`).
  - Atmos is excluded from `isLosslessInfo` (`:976`); Atmos-only response cannot satisfy HiRes and hits the brittle fallback gate.
- `buildFallbackQualities()` `:1006-1015`: Atmos→[Atmos,HiRes,Lossless], HiRes→[HiRes,Lossless], else single tier.
- `tidalAudioQualityLabel()` `:1620-1648` metadata labels: `"24bit"`, `"MQA"`, `"16bit/44.1kHz"`, `"320kbps"`, `"96kbps"`. These are catalog claims, not file measurements.
- `download()` result returns `bit_depth`, `sample_rate`, `audio_codec`, `actual_extension`, `output_extension`, `requires_container_conversion`, `lyrics_lrc` (`:2782-2792`) but **not** `resolvedQuality`, `requestedQuality`, `apiURL`, manifest, or `audio_modes`. Add host-side tracking for requested/resolved.
- **Risks:** mirror hardcoded 16/44.1 FLAC (`:1151-1153`); `isHiResInfo` can be true from `sampleRate>44100` alone (`:963-971`); `application/dash+xml` m4a without quality can look lossless/HiRes. Never show FLAC/HiRes from these claims alone.

### 1.6 Errors worth diagnosing

- `HTTP <status> for <path>` from `signedJSON()` `TD/index.js:340-347`; plain 503 has no `code/retryable`, so addon retries 5× then throws `"Quality <candidate> failed without confirmation..."` (`:1303-1308`).
- `isDeterministicDownloadError()` `:1189-1199` whitelist: `TRACK_NOT_FOUND`, `TRACK_UNAVAILABLE`, `TRACK_NOT_AVAILABLE`, `QUALITY_UNAVAILABLE`, `QUALITY_NOT_AVAILABLE`, `CODEC_UNAVAILABLE`, `ATMOS_UNAVAILABLE`, PREVIEW, "TIDAL API did not report EAC3_JOC...". Auth/invalid-ticket are explicitly excluded (`:1192-1194`).
- Ticket 429 block: `"ticket service refused this client, next try in Ns (no request sent)"` with `code=RESOLUTION_TIMEOUT` (`:405-413`).
- `"Download API returned PREVIEW asset"` (`:1086-1088`), `"Download API payload missing manifest"` (`:1091`), `"Downloaded audio does not match the track duration"` via validation (`:2753-2757`).
- `expired_stream` triggers one URL refresh (`:2730-2734`); `cancelled` on `utils.isDownloadCancelled()` (`:1237-1239`).
- No literal `Turnstile` handling; only generic `needsVerification`/`VERIFY_REQUIRED` → `verification_required` (`:434-437,2798`).

## 2. How Lokal does it (reference host)

- Same registry/install/validation core as Deezer: `LOK/electron/mornye/packages.js:13-17,160-173,237-240,262-329,342-372`.
- Provider direct fields `tidal:'tidal_id'` and `deezer:'deezer_id'` (`packages.js:551-553`). Tidal has an explicit acquisition-order test: **availability → enrichment → availability** for both Play and Save (`LOK/scripts/mornye-packages.test.mjs:217-263`).
- `providerFastPath.js:3-9`: no Tidal/Deezer skip; negative availability may be retried once after enrichment; only Qobuz is live/progressive (`:11-14`).
- Quality: `playbackQuality.js:18-27` prefers saved tier, then HiRes, Lossless, lossless kind, first option, and finally `HI_RES_LOSSLESS` for Tidal if no option is usable. Spatial tiers rejected for PCM playback (`:29-33,54-57`); one-off download requires manifest tier and explicit Atmos authorization (`:39-59`).
- Atmos metadata is explicit-only (`LOK/electron/mornye/atmos.js:3-21`); `scripts/tidal-atmos-metadata.test.mjs:8-27` rejects inference from HiRes/stereo/bit depth/raw eac3.
- Download worker as in Deezer doc: `.part`, validated resume, 3–6 attempts, backoff to 8 s, 90 s stall watchdog, segmented bounded assembly (`LOK/electron/mornye/host.js:63-189`), patterned transform `:190-211`, 24 h/60 s budgets (`packages.js:614,468-473`).
- Result-side decrypt is only `ffmpeg.mov_key` (`packages.js:623-632`); Tidal needs none. Then ffprobe + container repair + Atmos JOC verification + duration check (`packages.js:633-691`).
- Playback cache key `(addonKey,id,addon.digest,quality)` (`LOK/electron/mornye/media.js:13-18,31-36`), shared trim + cleanup `:91-97`.
- Tests: conformance registers `tidal-web.sflx` (`scripts/mornye-conformance.test.mjs:14-27`); progressive parity with Tidal and `tidal-web` (`scripts/provider-progressive-parity.test.mjs:51-60,127-141`); Atmos saved quality controls playback vs download (`scripts/atmos-stereo-handoff.test.mjs:9-22,76-99`).

## 3. BitChord current state

### DONE

- Install/registry/session infra same as Deezer doc: `MorneyePackageManager.kt:40-42,108-181,205-234,421-494`; `MorneyeSignedSession.kt:22-47,221-295,306-375,479-519,820-856`; `MorneyeBootstrap.kt:238-258`.
- Signed-session client already handles gateway 503 `PROVIDER_UNAVAILABLE` with two bounded same-operation retries and a capped sleep (`MorneyeSignedSession.kt:221-295`). This directly addresses the historical `/dl/tid` 503 stall.
- DASH segment join: `MorneyeHostBridge.kt:274-336` writes init + media segments in order, deletes partial on failure, returns `{success,path,size}`. Comment explicitly names Tidal/Qobuz.
- Mornye rows in Addons tab: `BC/app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:2161-2203`.
- Search parsing: `MorneyeSource.kt:105-178`; Tidal field aliases `MorneyePackageModel.kt:107-115,119-130,176-179`.
- Download/reuse/cooldown: `MorneyeSource.kt:188-280,285-320`; partial cleanup `:530-546`; CENC key handling `:341-363` (inert for Tidal since no key is returned).
- Byte-truth codec/quality: `MorneyeSource.kt:417-479,548-618`; MP4 `dfLa`/`alac` detection prevents false AAC for lossless-in-MP4.
- Cache rejects non-audio/DASH XML: `BC/app/src/main/java/com/music/bitchord/playback/AudioCache.kt:1148-1164`.
- Stats measured/claimed split: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/NerdStats.kt:20-40,43-157`; publish `BC/.../playback/PlaybackService.kt:6135-6179`.
- Diagnostics + auto file sink as in Deezer doc (`DiagnosticsScreen.kt:45-78,178-201,263-272`; `DiagnosticsFileSink.kt:17-34,49-109`).

### PARTIAL / TODO / BLOCKED

| Item | State | Concrete action |
|---|---|---|
| `signedSession@3` specific behavior | PARTIAL | One unified HMAC client exists; no explicit `@3` branch (`MorneyePackageManager.kt:56-61`). Add provider/version logging and tests for richer error fields (`retryMode`, `retryable`, `code`, `Retry-After`) before claiming `@3` fully. |
| `handleURL` | TODO | No BC call sites. Route `tidal.com`, `listen.tidal.com`, `tidal:` via addon `handleUrl`; only `type:"track"` becomes a playable row. |
| `checkAvailability` / `enrichTrack` | TODO | BC never calls them. For Tidal this is the Lokal-tested order; add optional availability pass with `tidal_id` raw and `prepared_context` capture. Do not claim `preparedContext@1` unless we pass a faithful context. |
| Quality fallback gating | TODO | Addon throws before FLAC fallback on non-deterministic 503 (`TD/index.js:1303-1308`). Handle at host by treating that error as a source failure/cooldown and trying another source; do not edit addon bytes. Log `requested` vs `resolved` even though result lacks it. |
| Container conversion | TODO | Addon declares `requiresNativeContainerConversion` but runs no converter; BC has no ffmpeg CLI. Decide per bytes: `dfLa`/`alac`/FLAC headers → play as lossless-in-MP4/FLAC via Media3; `aac`/`eac3`/`ec3`/`ac4` → label lossy/Atmos, never FLAC. If a true conversion is required, implement native path or mark unsupported. |
| Atmos | TODO/PARTIAL | Metadata recognition exists (`MorneyePackageModel.kt:176-179`; `AddonModels.kt:217-243`), but no verified E-AC-3 JOC gate like Lokal (`packages.js:650-675`). PCM playback must not consume spatial tiers (`playbackQuality.js:29-33`). Show Atmos only when decoder says JOC. |
| Stats requested/resolved | TODO | Result omits `resolvedQuality`; carry it from selected quality + internally tracked `downloadInfo` claim, show separately from measured `mimeType/rate/depth`. |
| Full URL logging | TODO | `MorneyeHostBridge.kt:260` logs full URL; use `redactedUrl()`. |
| Allow-download policy | TODO | Mirror manifest `allowDownloads` into `SourceConfig` like Deezer doc. |
| Upstream 503/403 | BLOCKED | Use bounded cooldown + source fallthrough; no infinite retries, no fake FLAC. |
| Device proof | TODO | Five target songs on CI APK; audible + Stats + diagnostics export. |

## 4. Implementation plan (ordered)

### Phase A — install + session health
1. Verify registry SHA-256 `0f23f034...6094` (`registry:145-164`) and install via `MorneyeScreen`.
2. Confirm manifest features accepted; log one line: package key, version `1.2.14`, `signedSession@3`, `downloadSegments@1`, `requiresNativeContainerConversion`, `replacesBuiltInProviders`.
3. Verify `MorneyeSignedSession` sends `X-Zarz-Session/Timestamp/Nonce/Body-SHA256/Signature/App-Version/Platform` with `baseUrl=https://api.zarz.moe/v2` and 300 s window (`MorneyeSignedSession.kt:479-519,820-856`).
4. On `503 provider PROVIDER_UNAVAILABLE`, use existing two bounded retries (`MorneyeSignedSession.kt:227-253`); log origin/code/retryable/retry_after (no secrets). On `428 VERIFY_REQUIRED`, open grant UI; require `state` for auto callback (`MorneyePackageManager.kt:317-355`).
5. Acceptance: no queue stall from one dead Tidal candidate; verification path completes or fails visibly.

### Phase B — search + URL input
1. Keep composite key `src:<configId>::<tidal:<id>>`; playlists are raw UUID (`TD/index.js:1866,2329`) so type is carried by row metadata, not by ID string.
2. Source label from `SourceConfig.label` = "Tidal"; do not create a first-party `SourceKind.TIDAL` that would double-count with `replacesBuiltInProviders`.
3. Add `handleUrl` route for `tidal.com/browse/track/`, `listen.tidal.com`, `tidal:track:<id>`, `tidal://track/<id>`; call addon `handleUrl`, accept only `type:"track"`.
4. Optional availability pass: `checkAvailability(isrc, name, artists, {tidal_id,track_id,duration_ms,track})`; on false, call `enrichTrack(track)` once and retry, matching Lokal's tested order (`LOK/scripts/mornye-packages.test.mjs:217-263`). Capture `prepared_context.track_id/raw_track/formatted_track` locally but do not advertise `preparedContext@1` unless we pass them into `download()`.
5. Acceptance: direct URL, search row, and cross-provider row all resolve; round-robin Addons tab unaffected.

### Phase C — quality + resolver
1. Map manifest tiers to `StreamRequest`: `Best` → ranked first (`MorneyeSource.kt:408-415`); `Lossless` → highest `isLossless` (not Atmos, not lossy); `Capped` as existing.
2. Do **not** offer `DOLBY_ATMOS` for PCM playback; native output only. If user selected Atmos explicitly for download, keep it as a download-only tier with explicit authorization, mirroring `playbackQuality.js:39-59`.
3. Log requested tier; if addon returns lower tier (HiRes→LOSSLESS downgrade at `TD/index.js:1247-1250`), record as `resolved=LOSSLESS` even though download result omits it.
4. Treat non-deterministic `Quality X failed without confirmation` as source-level failure with cooldown; do not retry the same addon 5× again inside BC. Let `SourceResolver` try next source (`BC/.../data/sources/SourceResolver.kt:208-344`).
5. Acceptance: 503 log shows bounded retries then fallthrough; 401/403 triggers session refresh path, not silent lossy fallback.

### Phase D — download + container + Stats
1. For direct: `file.download` with resume/checkpoint (`TD/index.js:1330-1346`; host `MorneyeHostBridge.kt:260`). For manifest: `file.downloadSegments` init-first sequential (`MorneyeHostBridge.kt:274-336`); log segment count and completed ratio.
2. Delete partial on any transfer/validation failure; broaden BC cleanup to `fileBase.*` (`MorneyeSource.kt:530-546`).
3. Validate bytes before labels:
   - `fLaC` → FLAC; read STREAMINFO rate/depth (`MorneyeSource.kt:600-618`).
   - MP4 with `dfLa`/`alac` → lossless-in-MP4; report codec accordingly, do not call it AAC (`MorneyeSource.kt:568-586`).
   - `mp4a`/`aac`/`mp3`/`opus`/`vorbis` → lossy; `ec3`/`eac3`/`ac4` → Atmos/spatial only if decoder proves JOC.
   - DASH manifest XML under an audio key → reject from cache (`AudioCache.kt:1148-1164`).
4. Duration sanity: mirror addon's ±10 s / preview guard (`TD/index.js:2743-2761`) at host level too; a 35 s preview must never be cached as the full song.
5. Stats fields: `sourceName="Tidal (Mornye)"`, requested tier, resolved tier, direct vs `manifest(N segments)`, `audioMode/audioQuality` when known, measured `mimeType`, `bitrateKbps` (or PCM rate for lossless), `sampleRateHz`, `bitDepth`, `channels`, `container`, `pcmDataRateKbps`, `downgraded`. Never show `24bit` from `tidalAudioQualityLabel()` alone.
6. Acceptance: Stats screenshot for HiRes FLAC, CD FLAC, HIGH/AAC, Atmos; labels match bytes. No "FLAC" over AAC.

### Phase E — cache, export/share, queue/prefetch
1. Cache filename `<tidal_id>_<quality>.<ext>` (`MorneyeSource.kt:210-215`); separate tiers so a HIGH prefetch is never reused for LOSSLESS.
2. Mirror `allowDownloads` into `SourceConfig`, then `AudioCache.completeCopyOf()` export/share (`AudioCache.kt:988-1044`).
3. Prefetch next track through `MORNYE.worthPrefetching=true` (`SourceKind.kt:130-153`); no second queue.
4. Cached-file playback after restart: if file is DASH XML or zero/partial, discard and re-download; if MP4 with CENC PSSH but no key, discard (same rule as Deezer doc).
5. Acceptance: cache browser entry, byte-identical export/share, gapless queue, Direct Stream unaffected.

### Phase F — regressions + release gate
1. Gradle unit + compile commands; only pre-existing `LocaleStringsTest` failures allowed.
2. New tests: Tidal manifest feature/quality parsing; URL parser mapping; availability→enrich→availability ordering; 503 bounded-retry logging; segment order init-first; false-lossless guard for mirror hardcoded FLAC and dash+xml m4a; Atmos not inferred from HiRes.
3. Device: five target songs; audible confirmation, Stats, diagnostics export, native/source regressions, cache/export/share, queue/prefetch, Direct Stream.
4. Diff review: no public token/secret, no version bump (39/1.9.1), no unrelated changes.

## 5. Multiple Mornye extensions (Tidal-like or otherwise)

### Current behavior
- One `SourceConfig` per installed `.sflx`, all rank 1, keyed by stable addon key (`SourceRegistry.kt:604-635`; `SourceKind.kt:130-153`).
- Search round-robin across all `ADDON` and `MORNYE` sources (`MainViewModel.kt:2188-2202`).
- Playback uses `SourceResolver` pin/quality/fallback plus Mornye failure cooldown (`MorneyeSource.kt:250-258`).
- Dedupe by composite key; `tidal-web` writes `spotify_id="tidal:<id>"` (`TD/index.js:1722-1723`) and declares `replacesBuiltInProviders:["tidal"]` (`manifest:94-96`). Do not merge it into a first-party Tidal source by `spotify_id`.

### Planned ranking for two or more Tidal-capable `.sflx`
1. Explicit pin / selected Addons row.
2. Enabled + signed-session ready + health ok.
3. `searchBehavior.primary` first (Tidal declares false, so a primary Deezer-like addon wins ties for generic queries).
4. Quality tier rank from `capabilitiesOf()` with Atmos treated as spatial, not lossless (`MorneyePackageModel.kt:293-331`).
5. Stable install order.
6. Failure cooldown demotion; never hard-disable on one 503.
7. Attribution from `configId` into Stats/cache/export; never from result map (result has no `provider_id`).

### Metadata across addons
- Normalize to `MorneyeTrack`, keep raw `tidal_id`/`isrc`/`audio_modes` in side map; do not let one addon's `audio_quality` label badge another's row.
- Show both rows for the same recording if they come from different sources; let the user pick. Merge only for "best version" suggestions with explicit source label.
- Playlists: raw UUID + `configId` are the identity; never assume `tidal:<num>`.

### Reorder `.sflx` (back of mind)
- Persist `mornye_order` in prefs, expose up/down in `MorneyeScreen`, emit packages in that order from `syncMorneyePackages()`.
- Use only as tie-break rule 5. Do not change `SourceKind.MORNYE.rank` or `reorderAddons()` (`SourceRegistry.kt:409-418`), which is for rank-0 user HTTP addons.

## 6. If this plan fails — go back to drawing board

- **`/dl/tid` stays 503:** keep bounded cooldown + fallthrough; do not increase retries indefinitely. Treat as provider outage in Diagnostics with origin/code/retry_after.
- **Verification/Turnstile cannot be completed:** show `needsVerification` with auth URL and require user grant; never silently substitute a preview or lossy source under a Tidal label.
- **Segments merge but bytes do not decode:** log segment count, first magic bytes, init URL host (redacted), and player error; discard file. Do not label FLAC from `manifestMimeType`.
- **Atmos cannot be verified:** remove Atmos label; if the stream is only E-AC-3 without JOC, report spatial/encoded, not Atmos/lossless. PCM playback must stay on a lossless stereo tier or another source.
- **Multiple addons misattributed:** carry `configId` end-to-end and stop trusting addon result maps for provenance.
