# Amazon Mornye CENC playback — issue report

Internal incident report. Line references are to the diagnostics snapshots in
`Download/BitChord/Diagnostics/` unless a source path is given.

## Outcome

Amazon Music CENC playback is working. The final diagnostic snapshot,
`bitchord-diagnostics (8).txt`, shows:

- `:105` — `ClearKey KIDs: pssh=3, tenc=1, using=4352c5670cd372ff416cd1f49095519c`
- `:106` — `ClearKey key request accepted for 1 key id(s)`
- `:107` — `ClearKey key status entries=3`
- `:108` — `ClearKey session ready for 1 key id(s)`
- `:110` — `decoder=c2.android.flac.decoder initialized`
- `:111` — `DECODE ... audio/flac 44.1kHz ... 24-bit 2ch`
- `:112` — `AudioTrack 44100Hz encoding=2`
- `:113` — `TIMING first audio: 20970ms since track selected`

No `ERROR_DRM_NO_LICENSE`, `ERROR_CODE_DRM_SYSTEM_ERROR`, or playback failure
appears in that capture.

## Failure chain

1. **Amazon search/URL parsing** required browser-style `URL` and
   `URLSearchParams`. A polyfill fixed it and was device-confirmed before the
   CENC work.

2. **Key availability was too late for provider-time lookup**
   (`bitchord-diagnostics (5).txt`). `DrmSessionManagerProvider` runs while the
   next item is prefetched, before `stream()`/download returns
   `decryption_key`. Moving key resolution to the deferred session-provider
   stage made the key available (`bitchord-diagnostics (6).txt:105`).

3. **Registering the key was not sufficient** (`bitchord-diagnostics (6).txt`).
   The Amazon file's non-ClearKey PSSH was rejected by Android's ClearKey plugin
   with `{"cdmError":4}` at `:106`, and the session still reached
   `ERROR_DRM_NO_LICENSE` at `:156`.

4. **A syntactically valid ClearKey PSSH fixed the request but not decoding**
   (`bitchord-diagnostics (7).txt`). The key request was accepted at `:105`, but
   `ERROR_DRM_NO_LICENSE` remained at `:110`. The request was now valid; the key
   was bound to the wrong KID.

5. **Root cause: PSSH KIDs did not match the track KID used by the samples.**
   Amazon's PSSH advertised three KIDs while the MP4's track-encryption box
   contained one different KID. Android's codec looks the key up by the `tenc`
   or `seig` KID, not by arbitrary PSSH KIDs. Reading the actual KID from the
   cached MP4 and using it for the ClearKey request fixed audible playback.

## Final implementation

- Cache the encrypted file path beside the saved per-track key so the KID can be
  inspected on later launches.
- Parse `tenc` and `seig` boxes from the cached MP4.
- Prefer the track KID over PSSH KIDs when creating the ClearKey request.
- Log only presence, counts, KIDs, and status counts; never the key material.

## Relevance to Deezer and Tidal

This CENC/KID bug does not transfer directly:

- Deezer predecrypts with a Blowfish key derived from the track ID and uses
  `transformPatternedBlocks`; no CENC, PSSH, or ClearKey is involved
  (`docs/mornye-deezer-plan.md:79-85`).
- Tidal uses direct downloads or host-side DASH segment assembly and has no
  addon crypto in the normal path; no key is returned to the host
  (`docs/mornye-tidal-plan.md:79-91`).

The shared risks are instead:

- Addon labels and self-authored `.flac` filenames must not become lossless
  evidence; bytes and decoder output are authoritative.
- Empty resolver data and non-deterministic 503 must become bounded source
  failures with cooldown/fallthrough, not retries or fake quality.
- Full remote URLs must be redacted in logs.
- Partial and encrypted temp files must be cleaned on failure.
- `configId` must remain the provenance source through cache, Stats, export,
  and share.
- URL resolution and availability/enrichment must use exact addon method names
  because runtime invocation is case-sensitive.
