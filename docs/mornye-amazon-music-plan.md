# Amazon Music Mornye integration — current diagnosis

Internal planning/incident doc. Line numbers are from the inspected snapshots;
grep before relying on an exact offset. Short forms are intentional.

## Path aliases / related docs

| Alias | Path |
|---|---|
| `BC/...` | `/data/data/com.termux/files/home/BitChord/` |
| `DIAG13` | `/storage/emulated/0/Download/BitChord/Diagnostics/bitchord-diagnostics (13).txt` |
| CENC report | `BC/docs/mornye-amazon-cenc-report.md` (decryption/KID history) |

## Status legend

`DONE` = present in current checkout. `PARTIAL` = plumbing exists, device-proof
limited. `FIXED` = bug identified and corrected here. `BLOCKED` = upstream, not
fixable honestly in the app.

## 0. Verdict

Amazon Music is **not uniformly broken**. `DIAG13` shows both a working path and
a failing one:

- **Working:** search returns rows (`DIAG13:94-109,187-210,278-296,531-578`),
  and a chosen ASIN downloads genuine CENC FLAC and warms it
  (`DIAG13:640-675,696-702`): `B0FSLDKP7P` → `codec=flac`, 48 kHz, 40,838,229
  bytes, ClearKey material registered. Decryption was solved earlier and is
  documented in `mornye-amazon-cenc-report.md:1-21,45-57`.
- **Failing:** the *strict* lookups — direct playback, mid-song upgrades and
  downloads — rejected every Amazon row. This was a local matcher bug, now
  fixed; see §1.
- **Upstream noise, already bounded:** `showSearch` bridge timeouts
  (`DIAG13:489-517`, including a 90-second stall and the addon's own session
  refresh/retry at `:495-501`) and one signed API `503 PROVIDER_UNAVAILABLE`
  (`DIAG13:661`). Neither is a local logic fault; the addon retries and the
  resolver treats a throw as a source miss.

## 1. Root cause: strict length filtering of a duration-less catalogue

1. The Amazon addon's `showSearch` answer states no runtime. Every row arrives
   as `duration_ms: 0`. `BC/app/src/main/java/com/music/bitchord/data/sources/mornye/MorneyePackageModel.kt:190-200`
   deliberately converts that to `null` — a zero is "the catalogue did not say",
   not a zero-second song. That part was already right, and is covered by
   `BC/app/src/test/java/com/music/bitchord/AmazonCatalogMatchTest.kt:14-25,70-85`.
2. `BC/app/src/main/java/com/music/bitchord/data/sources/MorneyeSource.kt:252-254`
   therefore leaves `Song.durationText` null.
3. `BC/app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt:1205-1221`
   partitioned strict matches with `TrackMatcher.withinSeconds`; `TrackMatcher.kt:349-353`
   answers false whenever *either* runtime is unknown. Every Amazon row went
   into the "outside ±N s" bucket and was dropped at `:1220`.
4. The `has no match` line at `SourceResolver.kt:1222-1244` then listed the rows
   with scores `110`/`125` — i.e. `BASE + ARTIST_SHARED` / `BASE + ARTIST_EXACT`
   (`TrackMatcher.kt:803-825`). Title and artist **did** match. Examples:
   `DIAG13:97-98` (Dard Dilo Ke), `:190-191` (Banjaara), `:281-282` (Main Woh
   Chaand), `:560-561` (Tum Hi Ho), `:639-643` (Baarish). Each target had a
   runtime; each Amazon candidate had none.
5. Strict callers affected: `SourceResolver.kt:401` (direct/substitute
   playback), `:576` (upgrade hunt), `:751` and `:781` (download). The relaxed
   warm-up path skipped the filter, which is why Amazon sometimes played at all
   (`DIAG13:673-675,697-702`).
6. `BC/app/src/main/java/com/music/bitchord/playback/QualityUpgrade.kt:480-481`
   applied the same idea at audition time: a null `durationSec` failed
   `sameRecordingAs` (`SourceResolver.kt:922-925`) even when the bytes were right.

### Fix (this checkout)

- `TrackMatcher.withinSecondsOrUnstated()` (`TrackMatcher.kt:355-374`) lets a row
  with **no stated runtime** through a strict filter. A row that *does* state a
  runtime is still held to the old bar.
- `SourceResolver.matchAndStream()` uses it for the strict partition
  (`SourceResolver.kt:1205-1221`).
- `QualityUpgrade.lookAgain()` no longer refuses a live lookup purely because it
  states no runtime (`QualityUpgrade.kt:477-501`). It logs the case and leaves
  the decision to the audition, which already compares the decoder's duration
  against the playing track (`PlaybackService.kt:5404-5426`).
- Direct playback is protected independently: `cutShort()`
  (`SourceResolver.kt:1435-1451`) probes the file against the *target's* runtime
  when the row has none.

Focused coverage: `AmazonCatalogMatchTest.kt` now has two extra tests for the
new helper (unstated accepted, stated-but-wrong still rejected).

## 2. What remains honestly blocked

- **Bridge timeouts / 503** (`DIAG13:489-517,661`): network or provider side.
  Do not add unbounded retries and never substitute a preview under an Amazon
  label. Existing addon retry + resolver fallthrough is the correct behaviour.
- **Addon labels vs bytes**: `codec=flac` and a `.flac` filename are claims.
  Keep byte sniffing and decoder output authoritative; CENC MP4 must keep its
  ClearKey/KID handling from `mornye-amazon-cenc-report.md:45-57`.
- **Provenance**: `configId` stays the source identity through cache, Stats,
  export and share. Never trust result maps for attribution.

## 3. Device proof still owed

The five-song Amazon check has not been run yet. Install the CI APK built from
this checkout, replay the five chosen Amazon tracks, and export diagnostics.
Success means: `Amazon Music matched …` for a strict lookup, a decoded FLAC
line (`DECODE … audio/flac … 24-bit 2ch`), no `ERROR_DRM_NO_LICENSE`, and a
cache row that can still be copied/exported/share byte-for-byte.

## 4. Regression gate

- Focused: `AmazonCatalogMatchTest`, `CachedReplayFidelityTest`,
  `MorneyeOrderTest`, `AddonOrderTest`, `TrackIdentityMismatchTest`,
  `UpgradeJobSlotsTest`.
- Full suite: only the known `LocaleStringsTest` / `ClearDrmSessionManagerTest`
  baseline failures are tolerated.
- No `versionCode`/`versionName` bump (39 / 1.9.1). CI remains the only release
  APK source.
