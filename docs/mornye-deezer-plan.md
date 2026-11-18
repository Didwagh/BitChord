# Deezer Mornye integration — reverse engineering + implementation plan

Internal planning doc. Short forms are intentional; it is for BitChord agents, not end users.
Line numbers are from the inspected snapshots; grep before relying on an exact offset.

## Path aliases

| Alias | Path |
|---|---|
| `DZ/index.js`, `DZ/manifest.json` | `/data/data/com.termux/files/usr/tmp/opencode/deezer-ext/` |
| `TD/index.js`, `TD/manifest.json` | `/data/data/com.termux/files/usr/tmp/opencode/tidal-web-ext/` (used only for cross-addon notes) |
| `LOK/...` | `/data/data/com.termux/files/usr/tmp/opencode/lokal/` |
| `BC/...` | `/data/data/com.termux/files/home/BitChord/` |
| registry | `/data/data/com.termux/files/usr/tmp/opencode/mornye-registry.json:105-121` |

## Status legend

`DONE` = present in current checkout. `PARTIAL` = generic plumbing exists but provider path not exercised/proven. `TODO` = must implement. `BLOCKED` = upstream/external failure, not fixable by us honestly.

## 0. Verdict / OPA

- **DONE (generic):** BitChord can install/run `.sflx`, search Mornye rows, call `download()`, cache file bytes, sniff codecs, and export/share cached files. Deezer is handled as a generic `SourceKind.MORNYE`.
- **DONE (provider-shaped helpers):** `patternedFileTransform@1` exists (`BC/.../MorneyeHostBridge.kt:369-422`) and is exactly what Deezer needs.
- **PARTIAL:** Deezer metadata field aliases are parsed (`BC/.../mornye/MorneyePackageModel.kt:107-115,119-130,172-179`), but `handleUrl`, `checkAvailability`, `enrichTrack`, and `preparedContext` are not wired in BitChord (`grep` found no call sites; only the deliberate unsupported note at `MorneyePackageManager.kt:50-54`).
- **BLOCKED upstream:** Deezer resolver/gateway currently returns `200` with empty `{"data":[]}` (known from earlier analysis). This is not a local code bug; do not invent data or label a stub as FLAC.
- **TODO:** quality honesty, direct-URL routing, optional availability/enrichment pass, explicit source labels/ranking for multiple `.sflx`, cache/export policy mapping, provider-specific diagnostics, and device proof with the five target songs.
- **Assumption:** treat Amazon/ClearKey as working per user instruction; do not re-litigate it in this plan.

## 1. What the Deezer addon actually does

### 1.1 Manifest contract

- `DZ/manifest.json:2-11`: `name=deezer`, `displayName=Deezer`, `version=1.3.11`, `type=[metadata_provider, download_provider]`, `minAppVersion=4.9.1`.
- `DZ/manifest.json:12-17`: required features `signedSession@1`, `sessionGrant@1`, `patternedFileTransform@1`, `preparedContext@1`.
  - BitChord advertises all except `preparedContext@1` (`BC/.../MorneyePackageManager.kt:50-61`). Unknown features only warn and still install (`:480-493`), so Deezer installs; `download()` falls back to its own `fetchTrack()` when no prepared context arrives (`DZ/index.js:1421-1429`).
- `DZ/manifest.json:18-32`: signed session `namespace=zarz-v2`, `baseUrl=https://api.zarz.moe/v2`, `appVersion=deezer@1.3.5`, `schemeLabel=ZARZ-HMAC-V1`, `headerPrefix=X-Zarz-`, 300 s window, endpoints `/bootstrap`, `/challenge`, `/session/exchange`, `/session/refresh`.
- `DZ/manifest.json:46-52`: one quality option `flac` / `"FLAC / Hi-Res"` claiming up to 24/192; **runtime `quality` is ignored** by the addon (`DZ/index.js:1411` signature, no use after validation).
- `DZ/manifest.json:92-99`: fallback tier `lossless`, timeout 15 s, related tracks true, share templates.
- `DZ/manifest.json:100-111`: required health check on `https://api.zarz.moe/v1/health` with `serviceKey=deezer`. Note `/v1` health vs `/v2` signed base — intentional in addon, but must be logged distinctly.
- `DZ/manifest.json:112-120`: `apiBaseUrl` setting default `https://api.zarz.moe`. In code it only selects a User-Agent (`DZ/index.js:2,94,208`); it does **not** redirect public API or signed resolver.

### 1.2 Search → row shape

- Handlers registered at `DZ/index.js:1724-1740`: `customSearch`, `searchTracks`, `handleURL`, `getTrack/Album/Artist/Playlist`, `enrichTrack`, `getRelatedTracks`, `checkAvailability`, `download`, `completeGrant`; `getDownloadUrl` is a `null` stub at `:1739`.
- `customSearch(query, options)` `DZ/index.js:1066-1092`: trims query, default limit 20 capped 50, filter lowercased, `all`/empty fans out track(limit)+artist(5)+album(5)+playlist(5) with no dedupe (`1084-1090`). No page/offset.
- `searchOne()` `DZ/index.js:1050-1064` → `deezerGet(path?q=...&limit=...)`; endpoints from `searchEndpointForFilter()` `:1014-1027`: `/search`, `/search/album`, `/search/artist`, `/search/playlist`.
- `deezerGet()` `DZ/index.js:417-431` → public `https://api.deezer.com` (`CONFIG.apiBaseURL` `:5`) via `getJSON()` `:100-109`. Empty `data` silently becomes `[]` (`:1055`).
- `formatTrack()` `DZ/index.js:598-669` emits: `id=deezer:<num>`, `spotify_id` **also set to the Deezer-prefixed id** (`:635`), `deezer_id` raw (`:636`), `name`, `artists`, `album_name`, `album_artist`, `artist_id/url`, `album_id/url`, `duration_ms`, `preview_url`, `cover_url`, `images`, `release_date`, `track_number`, `total_tracks`, `disc_number`, `total_discs`, `isrc`, `provider_id:"deezer"`, `item_type:"track"`, `external_links.deezer`, `upc`, `label`, `copyright`, `genre`, `composer`, `explicit`, `audio_quality`.
- Search rows use `formatSearchItem()` `DZ/index.js:1029-1048`, which **does not pass genre/total_discs**; full `getTrack` rows do. Same ID can therefore have different metadata richness.
- Artwork rewrite `highestQualityDeezerCoverURL()` `DZ/index.js:355-371` upgrades dzcdn `(\d+)x(\d+)-...jpg` to 1400 px; gateway synthesized cover `:1612-1613`.

### 1.3 URL / ID → acquisition

- ID parse `parseNumericID()` `DZ/index.js:262-277`: bare digits, `deezer:<digits>`, or `<type>/<digits>` anywhere. `deezer:track:123` is not accepted. Canonical prefix via `withPrefix()` `:250-254` is type-less: track/album/artist can all be `deezer:123`.
- Short links `deezer.page.link` / `link.deezer.com` resolved by `resolveURLTarget()` `DZ/index.js:901-941`: response URL → `Location` → canonical `<link>` → `og:url`; failures swallowed.
- `parseURL()` `DZ/index.js:943-958` tries track→album→artist→playlist; `handleURL()` `:960-1012` returns `{type, track}` / `{type:"album", tracks}` / etc., or `{success:false,error:"Unsupported Deezer URL..."}`.
- **No direct stream URL.** Pipeline is:
  1. `signedTicket("dzr","track", fullTrackURL)` `DZ/index.js:141-153`. Resource hash = `sha256("dzr:track:" + lowercase(fullURL))` (`:142`); POST `/tickets` with `capability:"download_ticket"`, provider `dzr`, `resource_hash`; reads `ticket_id || ticket`; throws `"signed ticket response missing ticket_id"` (`:148-151`).
  2. `resolveDownloadDescriptor()` `DZ/index.js:1272-1283`: track URL `https://www.deezer.com/track/<id>`; `signedJSON("POST", "/dl/dzr", {id,type:"track",platform:"deezer",url:trackURL}, {"X-Zarz-Ticket":ticketID})`.
  3. `signedJSON()` `DZ/index.js:126-139` requires `session.signedFetch`; consumes `{error,needsVerification,statusCode,body}`; throws on verification/error/non-200. Host must join relative `/tickets` and `/dl/dzr` to `manifest.signedSession.baseUrl`.
  4. URL precedence `resolveDescriptorDownloadURL()` `DZ/index.js:1285-1302`: `direct_downloadable && direct_download_url` → `download_url` → `direct_download_url` → `""`.
  5. Decrypt decision `descriptorRequiresClientDecryption()` `DZ/index.js:1304-1318`: explicit `requires_client_decryption`, else `!direct_downloadable`, else `deezer_encrypted`.
  6. Key ID for decrypt `DZ/index.js:1478`: `descriptor.deezer_track_id` → `mediaSongIDFromURL(downloadURL)` (`:1320-1327`) → requested track ID. Media-path override matters because CDN may serve another Deezer entry for same recording.
- No cookies/Referer/Authorization. Only extra header is `X-Zarz-Ticket`; media download sends a randomized User-Agent (`DZ/index.js:1460-1464`). No retry inside `signedFetch`, `file.download`, or ticket call. Gateway CSRF token has 30 min TTL and one retry on `VALID_TOKEN_REQUIRED` (`DZ/index.js:439-478`) but is metadata-only, not download URL.

### 1.4 Download + decryption

- `download(trackID, quality, outputPath, onProgress, options)` `DZ/index.js:1411-1539`:
  - `parseTrackID` validation → `invalid_track` (`:1412-1419`).
  - metadata from `options.preparedContext.track || host_track`, else `fetchTrack()`; failure is swallowed (`:1421-1429`). BitChord sends neither today, so this fallback is active.
  - descriptor resolve failure → `verification_required` only if message contains `VERIFY_REQUIRED`, else `api_error` (`:1431-1441`).
  - success requires `descriptor.success===true` and non-empty URL (`:1443-1450`).
  - extension from descriptor: `mp3` if `deezer_source_format`/`deezer_format` starts `MP3`, else `flac` (`DZ/index.js:1371-1374`). `quality` argument is ignored.
  - encrypted temp path `<name>.encrypted.<ext>` via `buildEncryptedTempPath()` `:185-189`.
  - progress: `5` (`:1457`), then `35+floor(processed/total*65)` only during decrypt (`:1351-1353`), then `100` (`:1498`). Unencrypted transfer has no byte progress.
  - `file.download(url, encryptedPath, {headers:{User-Agent}})` `:1460-1471`; failure returns `download_error`. **No partial cleanup on this failure path in the addon.**
  - decrypt on success `:1475-1490`, then `file.delete(encryptedLocalPath)`. On decrypt/delete throw: delete both temp and output, return `decrypt_error`.
  - final result written directly by `file.download` (clear) or `transformPatternedBlocks` (encrypted); no rename/move in addon.
- Key derivation `generateBlowfishKeyHex()` `DZ/index.js:196-204`:
  - `md5hex = md5(String(trackID).trim())`;
  - for i 0..15: `md5hex[i] ^ md5hex[i+16] ^ ord(publicConstant[i])`, where publicConstant is the classic Deezer `"g4el58wc0zvf9na1"` (`DZ/index.js:7`). This is an algorithm constant, not user secret; never log it as a credential.
- `decryptDownloadedFile()` `DZ/index.js:1329-1360` calls:
  `file.transformPatternedBlocks(in,out,{operation:"decrypt",algorithm:"blowfish",mode:"cbc",key:<hex>,iv:"0001020304050607",padding:"none",segmentSize:2048,transformEvery:3,transformOffset:0,bufferSize:1048576,transformPartial:false})`.
  - Layout: 2048-byte segments; decrypt every 3rd 16-byte CBC block starting at offset 0; trailing partial segment untouched. **Not CENC/ClearKey/ffmpeg-cli.** No key is returned to host; addon does the transform itself.
  - BitChord host primitive matches: `MorneyeHostBridge.kt:369-422` (algorithm/mode/segment/every/offset/key/iv/NoPadding; full segments only).

### 1.5 Quality reporting and false-lossless risks

- `trackAudioQuality()` `DZ/index.js:1362-1369`: returns cached measured label, else `"24-bit FLAC"` when gateway `FILESIZE_FLAC_24>0`, else `"FLAC"`. This is a **catalog claim**.
- `downloadedAudioQuality()` `DZ/index.js:1376-1409`:
  - probes `gobackend.getAudioQuality(filePath)`; reads `bitDepth`/`sampleRate`, validates ranges (`:1387-1390`).
  - if no measured bit depth and codec looks FLAC, infers 24 or 16 from `deezer_source_format` (`:1394-1397`).
  - label: `codec==="mp3" ? "MP3" : "FLAC"`; then `<depth>bit/<rate>kHz` if both, or `<depth>-bit FLAC` (`:1398-1400`).
  - returns `{bit_depth,sample_rate,audio_quality,audio_codec,actual_container}` (`:1402-1408`).
- **Risks:** any codec other than exact `mp3` is labeled `FLAC` and container `flac` (`:1398,1407`); no bitrate field exists anywhere; descriptor inference is not measurement. BitChord must treat all addon quality strings as `claimed`, never as measured.
- Success result `DZ/index.js:1501-1538` has metadata aliases + the five audio fields above, but **no `id`, `provider_id`, `item_type`, `duration_ms`**. Attribution must come from selected `configId`, not the result map.

### 1.6 Errors worth diagnosing

`DZ/index.js` exact strings: `"signed session runtime is not available"` (`:128`), `"signed ticket response missing ticket_id"` (`:150`), `"Deezer gateway did not return a CSRF token"` (`:446`), `"Deezer gateway error: ...VALID_TOKEN_REQUIRED..."` (`:470-475`), `"HTTP <status> for <path>"` (`:106,121,136`), `"Failed to resolve Deezer download: ..."` (`:1438`), `"Resolver did not return a download URL"` (`:1447`), `"Failed to download Deezer stream: ..."` (`:1468`), `"Failed to decrypt Deezer stream: ..."` (`:1487`), `"Unsupported Deezer URL"` (`:966`).
`needsVerification` without literal `VERIFY_REQUIRED` becomes `api_error`, not `verification_required` (`:1439`) — log the raw contract `code` in BitChord so this is visible.

## 2. How Lokal does it (reference host)

- Registry/version/features: `LOK/electron/mornye/packages.js:13-17,160-173,237-240,262-287`; SHA/identity/downgrade checks `:294-329`; lazy runtime `:342-372`. Same official registry and `5.3.0` compatibility as BitChord.
- Addon key = SHA1(`spotiflac:<name>`)[0..10], public provider `a-<key>` (`packages.js:17,198-207`); HTTP addons and Mornye packages merged for list/search (`LOK/electron/online/addons.js:669-691`; searchable gate `:221-224,672`). Installed list uses `installed_at` order (`packages.js:229-235`).
- Search normalization `LOK/electron/mornye/packages.js:95-115`; Deezer direct ID field `deezer_id` in availability options `packages.js:551-553`.
- Acquisition is availability-first for Play and Save; `providerFastPath.js:3-9` explicitly says **no Tidal/Deezer special skip**; only Qobuz gets live/growing-file treatment (`:11-14`). Negative availability is retried once after optional `enrichTrack` (`packages.js:558-579`).
- Prepared context is passed to addon `download()` (`packages.js:604-614`); BitChord currently does not do this.
- Download worker: 24 h timeout + 60 s resolution budget (`packages.js:614`), 90 s no-byte watchdog (`:468-473,615-618`), `.part` + validated resume + 3–6 attempts + backoff (`LOK/electron/mornye/host.js:63-142`), segmented bounded-window assembly (`:143-189`), patterned transform (`:190-211`).
- After addon result: `ffmpeg.mov_key` decrypt is the **only** result-side decryptor (`packages.js:623-632`) — Deezer does not use it because the addon already calls `transformPatternedBlocks`. Then ffprobe validation, container rewrap, duration check, Atmos verification, staged cleanup (`packages.js:633-693`).
- Playback cache key = hash of `(addonKey, id, addon.digest, quality)` (`LOK/electron/mornye/media.js:13-18,31-36`); cache joins shared trim and removes temp/canceled files (`:91-97`).
- Quality defaults: saved tier, else hi-res, lossless, lossless kind, first option, Tidal fallback (`LOK/electron/mornye/playbackQuality.js:18-27`). No Deezer-specific default.
- Tests: real upstream registration for `deezer.sflx` (`LOK/scripts/mornye-conformance.test.mjs:14-27`); progressive parity lists `deezer` among complete-header sources (`scripts/provider-progressive-parity.test.mjs:51-60`); enrichment retry parity includes Deezer (`:127-141`). No Deezer acquisition-order test equivalent to Tidal's exists.

## 3. BitChord current state

### DONE

- Install/registry/SHA/manifest validation: `BC/app/src/main/java/com/music/bitchord/data/sources/mornye/MorneyePackageManager.kt:40-42,108-181,205-234,421-494`; stable key `:521-526`; encrypted per-addon prefs `MorneyeAddonStorage.kt:7-61`; CENC key store `MorneyePackageManager.kt:552-575`.
- Sync to source registry: `SourceRegistry.kt:604-635` — one `SourceConfig(kind=MORNYE, baseUrl=packageKey)` per installed package.
- Ranking: `SourceKind.kt:130-153` puts Mornye at rank 1; `SourceRegistry.active()` stable-sorts by rank (`SourceRegistry.kt:194-201`); `reorderAddons()` only rewrites user-added rank-0 configs (`:409-418`). Mornye rows are **not draggable** today.
- Addons-tab search: `BC/app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:2161-2203` queries all `ADDON` + `MORNYE` sources in parallel, each with timeout, and round-robin merges rows. Previous `is AddonSource` exclusion is fixed.
- Row parsing: `MorneyeSource.search()` `MorneyeSource.kt:105-178` tries `searchTracks`/`customSearch`, accepts list/JSONArray/wrappers, parses/filter rows, dedupes by composite source/track key; `parseMorneyeTrack()` handles Deezer fields (`MorneyePackageModel.kt:107-115,119-130,172-179`).
- Download/reuse/cleanup: `MorneyeSource.kt:188-280` strips composite key, checks `download` method, resolves quality, per-track/per-tier cache filename, reuses complete files, drops encrypted files without key, applies failure cooldown, calls `download(trackId, qualityId, outputPath, null, {forPlayback:true})`.
- Result parsing: `MorneyePackageModel.kt:194-266` reads `file_path/path`, `actual_extension`, `error`, `quality/audio_quality`, `kbps/bitrate`, `sample_rate/sampleRate`, `bit_depth/bitDepth`, `audio_codec`, `decryption.key`/`decryption_key`.
- Byte-truth codec/quality: `MorneyeSource.kt:417-479` chooses codec by header first, then parsed claim, then manifest, then path; FLAC STREAMINFO read at `:429-433,600-618`; lossless request served by lossy bytes is logged as `FALLBACK` (`:449-468`). Header reader handles FLAC/WAV/Ogg/Opus/Vorbis/MP3/MP4/AAC/`dfLa`/`alac`/CAFF (`:548-598`).
- Pattern transform: `MorneyeHostBridge.kt:369-422` supports the Deezer Blowfish layout. Downloads are logged redacted-ish at `:260` — **full URL is still logged here; fix hygiene**.
- Cache/export: `BC/app/src/main/java/com/music/bitchord/playback/AudioCache.kt:988-1019` allows complete cached addon copies when export permitted; `:1148-1164` rejects non-audio (e.g. Tidal manifest XML) from being kept.
- Stats: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/NerdStats.kt:20-40,43-157` measures mime/bitrate/rate/channels/depth, keeps `claimed` separate, and exposes `downgraded/isLossless/isHiRes`; `BC/.../playback/PlaybackService.kt:6135-6179` publishes it.
- Diagnostics: `BC/app/src/main/java/com/music/bitchord/ui/screens/DiagnosticsScreen.kt:45-78,178-201,263-272` has All/Mornye/Warnings filters, copy/share/export/clear; auto-sink `Download/BitChord/Diagnostics/bitchord-diagnostics.txt` exists (`DiagnosticsFileSink.kt:17-34,49-109`).

### PARTIAL / TODO / BLOCKED

| Item | State | Concrete action |
|---|---|---|
| `preparedContext@1` | PARTIAL | Keep fallback for now; add diagnostics line once per addon: `"preparedContext@1 not claimed; addon will refetch"` (BC `MorneyePackageManager.kt:50-54`, DZ `index.js:1421-1429`). Optional later: support a minimal `{track}` only if we can guarantee correctness. |
| `handleURL` | TODO | Deezer URL pasted into search currently has no dedicated route; BC has zero `handleUrl` call sites. Add a resolver that calls addon `handleURL` for known Deezer hosts/short links, then maps `{type:"track",track}` to the normal row pipeline. |
| `checkAvailability` / `enrichTrack` | TODO | BC never calls them. Add an optional availability pass before `download()` for cross-provider rows; pass `deezer_id` raw and keep `host_track`. Do not call it for addon-origin rows already carrying a valid addon track id unless cheap; avoid double work. |
| `quality` honesty | TODO | Treat DZ `audio_quality`, `bit_depth`, `sample_rate`, `actual_container` as `claimed`; Stats must show measured codec/rate/depth. Never derive Lossless from `qualityOptions[0].label` or DZ label alone. |
| Partial cleanup on `file.download` failure | TODO | Addon does not clean its encrypted temp on `download_error`; BC `discardPartialDownload()` runs on null/failure result (`MorneyeSource.kt:285-320`) — verify it matches the addon's `.encrypted.<ext>` name or broaden cleanup to `fileBase.*`. |
| Full URL logging | TODO | `MorneyeHostBridge.kt:260` logs full `file.download` URL; use `redactedUrl()` like `http.request` (`:39-48,196-200`). |
| Allow-download policy | TODO | `syncMorneyePackages()` leaves `SourceConfig.allowDownloads` default true (`SourceRegistry.kt:604-635`); parse manifest `allowDownloads` and call `updatePolicy()` for Mornye like HTTP addons do (`AddonSource.kt:143-145`). |
| Upstream empty data | BLOCKED | Record `200 data:[]` / empty descriptor as `provider_unavailable` in diagnostics, apply failure cooldown, fall through to next source. No retry storms, no fake FLAC. |
| Device proof | TODO | Five target songs (Believer, Tum Hi Ho, Runaway, Night Changes, Demons) from a CI APK, with audible confirmation + Stats screenshot + diagnostics export. |

## 4. Implementation plan (ordered)

### Phase A — install + observe (no behavior change)
1. Re-fetch registry entry and verify SHA-256 `298c44d7...e0e5c34` (`registry:105-121`). Install via `MorneyeScreen`.
2. Confirm `MorneyePackageManager` installs despite `preparedContext@1` warning and that `capabilitiesOf()` reports `downloads=true`, one lossless tier (`MorneyePackageModel.kt:290-338`).
3. Add one-time diagnostics line for unsupported optional features; include manifest version + package key, no secrets.
4. Acceptance: package appears in Sources → Mornye manager, enabled, search rows appear in Addons tab.

### Phase B — search + URL input
1. Keep composite key `src:<configId>::<deezer:<id>>`; do not dedupe by `id` alone (DZ type-less prefix + `spotify_id` misuse at `DZ/index.js:250-254,635`).
2. Preserve row source label from `SourceConfig.label` (manifest `displayName`), and carry raw `deezer_id`/`isrc` into MatchMetadata if present.
3. Add `handleURL` route for `deezer.com`, `deezer.page.link`, `link.deezer.com`, `deezer:`; call addon `handleURL`, accept only `type:"track"`, then reuse row parser. Errors → `{success:false,error}` surfaced in UI, not as a stream.
4. Optional availability pass: call `checkAvailability(isrc, name, artists, {deezer_id, track_id, duration_ms, track})`; if `available:false`, run `enrichTrack(track)` once and retry; positive result carries `track_id` to `download()` and stores `prepared_context.track` for a future context-support flag.
5. Acceptance: direct URL and search row both produce a playable row; mixed addon search round-robin remains intact (`MainViewModel.kt:2198-2202`).

### Phase C — signed resolver + download
1. Verify `MorneyeSignedSession` joins relative paths `/tickets`, `/dl/dzr` to manifest `baseUrl` and signs `url.encodedPath` (`MorneyeSignedSession.kt:479-519,820-856`). Deezer uses the same `X-Zarz-*` HMAC scheme as Tidal; no `@1`-specific branch exists, but the wire format is shared (`:22-47`).
2. On `{needsVerification:true,auth_url}` open grant UI; callback must carry `state` (`MorneyePackageManager.kt:317-355`); on missing state, manual paste flow remains.
3. Add explicit logging: ticket issued / descriptor URL host (redacted query) / `direct_downloadable` / `requires_client_decryption` / key-ID source (`descriptor` vs media path vs requested). Never log the Blowfish key or grant.
4. Verify host `transformPatternedBlocks` with a synthetic 2048-byte fixture: decrypt blocks 0,3,6..., leave others and trailing partial bytes unchanged. Confirm output magic becomes `fLaC` or `ID3` as descriptor claims; if not, fail and clean.
5. Broaden `discardPartialDownload()` to all `fileBase.*` files, including `.encrypted.*`.
6. Acceptance: encrypted Deezer fixture plays; clear MP3 fixture reports MP3, never FLAC; descriptor empty produces cooldown + source fallthrough.

### Phase D — Stats for Nerds
1. Populate `claimed` from `StreamFormat` built from addon result (`audio_codec`, `bit_depth`, `sample_rate`) and manifest tier (`MorneyeSource.kt:417-427`); keep `mimeType`, `bitrateKbps`, `sampleRateHz`, `bitDepth`, `channels` from measured decoder/header (`NerdStats.kt:43-78`; `PlaybackService.kt:6135-6179`).
2. Show `sourceName = SourceConfig.label` (e.g. `Deezer (Mornye)`), requested tier `flac`, addon label, measured codec/rate/depth, `pcmDataRateKbps`, `downgraded`, and a decryption line `"host patterned Blowfish-CBC 2048/3"` or `"clear"`.
3. Bitrate: Deezer result has none; leave encoded bitrate unset unless container measurement gives it. If lossless, show PCM rate separately. Do not print `320kbps` inferred from a label.
4. `isLossless` must remain decoder/header-driven (`NerdStats.kt:100-120`). A DZ `"FLAC"` label over AAC/Opus bytes must show Lossy/FALLBACK.
5. Acceptance: Stats screenshot for a known 24-bit track and a known MP3-only track; labels match bytes.

### Phase E — cache, export/share, queue/prefetch
1. Keep file in `MorneyePackageManager.downloadCacheDir/<addonKey>/<track>_<quality>.<ext>` (`MorneyeSource.kt:210-220`); quality in filename prevents tier mixing.
2. Ensure `SourceConfig.allowDownloads` mirrors manifest; then `AudioCache.completeCopyOf()` export/share works through the normal Download sheet (`AudioCache.kt:988-1044`).
3. Prefetch: `MORNYE.worthPrefetching=true` (`SourceKind.kt:130-153`), warm-up caches next track (`AudioCache.kt:1379-1573`). Do not add a second queue.
4. Verify cached file playback after app restart and after source temporarily disabled; encrypted-without-key entries are discarded/re-downloaded (`MorneyeSource.kt:221-248`).
5. Acceptance: cache browser shows file; export/share produces same byte-identical file; queue crosses into next Deezer track without gap.

### Phase F — regressions + release gate
1. Gradle: `:app:testDevDebugUnitTest` and `:app:compileDevDebugKotlin`. Known pre-existing `LocaleStringsTest` failures remain the only allowed failures.
2. New unit tests: DZ result parser (false-FLAC label vs measured codec), patterned transform offsets/trailing partial, `handleURL` mapping, composite-key dedupe, availability retry parity, diagnostics redaction of URL.
3. Device: install CI APK manually; play the five target songs; confirm audible PCM (not just `isPlaying`), Stats values, cache export/share, native sources, downloads, queue/prefetch, Direct Stream, Lokal parity notes.
4. Diff review: no secrets, no versionCode/versionName bump (stay `39`/`1.9.1`), no unrelated changes.

## 5. Multiple Mornye extensions (Deezer-like or otherwise)

### Current behavior
- One `SourceConfig` per installed package, all `SourceKind.MORNYE`, all rank 1 (`SourceRegistry.kt:604-635`; `SourceKind.kt:130-153`). Install order is the only natural tie-break.
- Search: parallel + round-robin (`MainViewModel.kt:2188-2202`); no catalog buries another.
- Playback: `SourceResolver` pins/upgrades/falls back by quality and source; failure cooldown skips a failing addon (`MorneyeSource.kt:250-258`).
- Dedupe: composite `src:<configId>::<addonTrackId>` (`MorneyeSource.kt:175-178,188-193`). Do not use `spotify_id` or bare `id` for cross-addon identity: DZ writes its own id into `spotify_id` and its prefix has no type.

### Planned ranking for two or more Deezer-capable `.sflx`
1. Explicit user pin / selected Addons-tab row wins.
2. Enabled, session-ready, health-ok (`manifest.serviceHealth.required`).
3. Manifest `searchBehavior.primary` (Deezer declares `primary:true`, `DZ/manifest.json:55`) before non-primary.
4. Quality tier rank from `capabilitiesOf()` (`MorneyePackageModel.kt:293-331`) — lossless before lossy, then depth/rate/kbps.
5. Stable install order as final tie-break.
6. Failure cooldown demotes a candidate for `WINDOW_MS`, then another source answers; never hard-disable on one transient.
7. Store chosen provider label in `NerdStats.sourceName`; download result has no `provider_id`, so attribution comes from `configId`.

### Metadata handling across addons
- Normalize every addon row into `MorneyeTrack` fields (`MorneyePackageModel.kt:107-186`) and keep raw provider fields in a side map for matching (`deezer_id`, `tidal_id`, `isrc`).
- Surface source label + quality label on row; if two rows are the same recording, show both if they are different sources (user choice), but mark duplicates with `(title, artist, duration)` and merge only in "best version" logic, not in the Addons tab.
- Never let one addon's `audio_quality` label become another row's badge.

### Reorder `.sflx` (back of mind, not Phase A-F)
- Add `mornye_order: List<String>` in `MorneyePackageManager` encrypted/plain prefs; drag rows in `MorneyeScreen`; `syncMorneyePackages()` emits packages in that order.
- Use order only as the tie-break in rule 5 above; do **not** change `SourceKind.MORNYE.rank` or `reorderAddons()` (which is for rank-0 user HTTP addons only, `SourceRegistry.kt:409-418`).
- Expose "Move up/down" before drag if touch targets are tight; persist on each move; no version bump.

## 6. If this plan fails — go back to drawing board

- **Resolver keeps returning empty data:** stop treating Deezer as a lossless source. Keep addon installed but mark health degraded in Diagnostics; fall through to native sources. Do not patch addon bytes or fabricate `download_url`.
- **Pattern decrypt produces garbage:** verify key-ID selection first (`descriptor.deezer_track_id` vs media path). If still garbage, disable Deezer playback for that track and log offset/first-16-bytes hex; do not hand garbage to Media3.
- **Lossless badge appears over lossy bytes:** revert to header-only `isLossless` (`NerdStats.kt:100-120`); this is a correctness invariant, not a preference.
- **Multiple addons cause wrong source attribution:** stop trusting result maps entirely; carry `configId` from the selected row through cache key, Stats, and export.
