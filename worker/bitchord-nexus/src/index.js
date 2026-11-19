/**
 * BitChord Nexus — Cloudflare Worker
 * Sources (priority order): LastWave (lw:) · Qobuz-DL (qb:) · Monochrome (mc:) · HiFi/TIDAL (hf:)
 * LW first — independent of Monochrome/QB, keeps working when those go down.
 * QB second for 24-bit when available. MC/HF share Monochrome CDN.
 */

// ── Manifest ──────────────────────────────────────────────────────────────────
const MANIFEST = {
  id: 'com.bitchord.nexus',
  name: 'Nexus',
  version: '1.8.0',
  resources: ['search', 'stream', 'download_url', 'addon_qobuz'],
  allowDownloads: 1,
  settings: [{
    key: 'quality', type: 'select', default: 'lossless',
    options: [
      { label: 'Lossless (FLAC 24-bit)', value: 'lossless' },
      { label: 'Dolby Atmos',            value: 'atmos'    },
      { label: 'High (FLAC CD)',         value: 'high'     },
      { label: 'Low (MP3 320)',          value: 'low'      }
    ]
  }]
};

// ── Runtime config (lazy-init from env — constant per deployment) ─────────────
let cfg = null;
function initCfg(env) {
  return cfg ??= {
    // Aurix — Tidal + Qobuz + Deezer with real credentials (highest quality source)
    AX:          env.AURIX_ROOT             || '',
    // LastWave — TIDAL via HiFi API network
    LW:          env.LASTWAVE_ROOT          || '',
    LW_SECRET:   env.LW_SECRET             || '',
    // Qobuz proxy — primary first, fallback if primary 429s or fails
    QB_PRIMARY:  env.QOBUZ_PRIMARY_BASE_URL  || '',
    QB_FALLBACK: env.QOBUZ_UPSTREAM_BASE_URL || '',
    // Monochrome CDN
    MC:          env.MONOCHROME_BASE_URL    || '',
    // HiFi/TIDAL public addon (shares Monochrome track IDs with mc:)
    HF:          env.HIFI_ADDON_BASE_URL    || '',
  };
}

// ── In-memory cache (lives for the lifetime of this isolate) ──────────────────
const store = new Map();
function cacheGet(key) {
  const e = store.get(key);
  if (!e) return null;
  if (Date.now() > e.exp) { store.delete(key); return null; }
  return e.val;
}
function cacheSet(key, val, ttlMs) {
  store.set(key, { val, exp: Date.now() + ttlMs });
}

// ── Track metadata store: id → {title,artist,album,artwork,duration} ──────────
// Populated during search so stream responses can carry display metadata,
// and so BitChord shows the correct "now playing" info.
const metaStore = new Map();
function metaSet(id, meta) {
  metaStore.set(id, { val: meta, exp: Date.now() + 600_000 }); // 10 min
}
function metaGet(id) {
  const e = metaStore.get(id);
  if (!e) return null;
  if (Date.now() > e.exp) { metaStore.delete(id); return null; }
  return e.val;
}

// ── QB circuit breakers — one per source so a 429 on primary still tries fallback ─
const QB_COOL    = 60_000;
const QB_P_CB    = { until: 0 };   // primary  (soundfean-musics)
const QB_F_CB    = { until: 0 };   // fallback (qobuz-dll.foreyou)

// ── Logging ───────────────────────────────────────────────────────────────────
// Redaction policy for this worker:
//   allowed: provider tag, method, status, byte count, duration, error class,
//            requested quality, and track-id length.
//   never:   full URLs, URL prefixes, query strings, signed paths, response
//            bodies, tokens, secrets, callback state, or decoded credentials.
// The old reference build printed URL prefixes and body slices; that behaviour
// is deliberately removed here rather than copied forward.
function log(tag, msg) {
  console.log(`[${new Date().toISOString()}] [${tag}] ${msg}`);
}

// ── Fetch helpers ─────────────────────────────────────────────────────────────
const TIMEOUT        = 7_000;  // LW + QB — independent, reliable
const MC_HF_TIMEOUT  = 4_000;  // MC + HF — Monochrome-dependent; cut wait when it's down
const HDRS    = { 'User-Agent': 'BitChord-Nexus/1.1', Accept: 'application/json' };

async function getJSON(tag, url, timeoutMs = TIMEOUT) {
  log(tag, '→ GET <redacted>');
  try {
    const r   = await fetch(url, { signal: AbortSignal.timeout(timeoutMs), headers: HDRS });
    const raw = await r.text();
    log(tag, `← HTTP ${r.status} body=${raw.length}B`);
    if (!r.ok) return null;
    return JSON.parse(raw);
  } catch (e) {
    log(tag, `✗ ${e.name === 'TimeoutError' ? `timeout after ${timeoutMs}ms` : e.name}`);
    return null;
  }
}

// Low-level QB fetch — trips the given circuit breaker on 429
async function _qbRaw(tag, url, cb) {
  log(tag, '→ GET <redacted>');
  try {
    const r   = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT), headers: HDRS });
    const raw = await r.text();
    log(tag, `← HTTP ${r.status} body=${raw.length}B`);
    if (r.status === 429) {
      cb.until = Date.now() + QB_COOL;
      log(tag, `429 — pausing for ${QB_COOL / 1000}s`);
      return null;
    }
    if (!r.ok) return null;
    return JSON.parse(raw);
  } catch (e) {
    log(tag, `✗ ${e.name === 'TimeoutError' ? `timeout after ${TIMEOUT}ms` : e.name}`);
    return null;
  }
}

// High-level QB get — tries primary, falls through to fallback automatically
async function qbGet(path) {
  if (Date.now() >= QB_P_CB.until) {
    const d = await _qbRaw('QB-P', cfg.QB_PRIMARY + path, QB_P_CB);
    if (d != null) return d;
  } else {
    log('QB-P', `cooling down ${Math.ceil((QB_P_CB.until - Date.now()) / 1000)}s — trying fallback`);
  }
  if (Date.now() >= QB_F_CB.until) {
    return _qbRaw('QB-F', cfg.QB_FALLBACK + path, QB_F_CB);
  }
  log('QB-F', `cooling down ${Math.ceil((QB_F_CB.until - Date.now()) / 1000)}s — both QB sources blocked`);
  return null;
}

// ── Utils ─────────────────────────────────────────────────────────────────────
const abs = u  => typeof u === 'string' && u.startsWith('http') ? u : '';
const str = v  => v != null ? String(v) : '';
const num = v  => Number(v) || 0;

/**
 * Normalised dedup key — strips punctuation, collapses whitespace, lowercases.
 * Handles mixed-script titles (Latin + Devanagari etc.) without stripping them.
 */
function dedupKey(title, artist) {
  const clean = s => str(s)
    .normalize('NFC')
    .toLowerCase()
    .replace(/[''"\-–—,.!?()\[\]{}]/g, ' ')  // punctuation → space
    .replace(/\s+/g, ' ')
    .trim();
  return `${clean(title)}::${clean(artist)}`;
}

/**
 * Relevance filter — drops tracks with too little overlap with the search query.
 * Only applied when the query has 3+ meaningful tokens (ignores words ≤2 chars).
 * Requires at least 2 query words to appear as whole tokens in title + artist.
 *
 * Purpose: when our catalogues have no real match (e.g. regional Indian artists
 * not on TIDAL/Qobuz), we return [] instead of a coincidental wrong song.
 * Empty results let BitChord fall through to the next addon (e.g. JioSaavn).
 *
 * Examples:
 *  query "sundari sanju rathod" → "Sundari by Rahul Vaidya" → overlap=1 → DROP
 *  query "sundari sanju rathod" → "Sundari by Sanju Rathod" → overlap=3 → KEEP
 *  query "bohemian rhapsody"   → filter skipped (only 2 tokens)
 */
function relevanceFilter(query, tracks) {
  // Tokenise and drop very short words ("a", "by", "le", "ke" etc.)
  const qTokens = query
    .toLowerCase()
    .replace(/[''"\-–—,.!?()\[\]{}]/g, ' ')
    .split(/\s+/)
    .filter(w => w.length > 2);

  // Don't filter short queries — not enough signal
  if (qTokens.length < 3) return tracks;

  const MIN_OVERLAP = 2;

  return tracks.filter(t => {
    // Tokenise title + artist into a Set for exact word matching
    const haystackTokens = new Set(
      `${t.title} ${t.artist}`
        .toLowerCase()
        .replace(/[''"\-–—,.!?()\[\]{}]/g, ' ')
        .split(/\s+/)
        .filter(w => w.length > 2)
    );
    const overlap = qTokens.filter(w => haystackTokens.has(w)).length;
    if (overlap < MIN_OVERLAP) {
      log('FILTER', `dropped titleChars=${str(t.title).length} artistChars=${str(t.artist).length} overlap=${overlap}/${qTokens.length}`);
      return false;
    }
    return true;
  });
}

/**
 * Returns true if the URL looks like a prank/decoy CDN stream.
 * LW returns these for unsigned or incorrectly-signed requests.
 */
function isDecoy(url) {
  if (!url) return false;
  const l = url.toLowerCase();
  return l.includes('pranks-cdn') || l.includes('definatelynagato');
}

function qbFmt(q) {
  const s = str(q).toLowerCase();
  if (s === 'lossless') return 27;   // FLAC 24-bit / 192 kHz
  if (s === 'high')     return 6;    // FLAC 16-bit / 44.1 kHz
  return 5;                          // MP3 320 kbps
}
function qbMeta(fmt) {
  if (fmt === 27) return { codec:'flac', container:'flac', format:'flac', manifest:'none', encrypted:false, sampleRate:192000, bitDepth:24, audioQuality:'HI_RES_LOSSLESS', quality:'Lossless · 24-bit / 192 kHz' };
  if (fmt === 7)  return { codec:'flac', container:'flac', format:'flac', manifest:'none', encrypted:false, sampleRate: 96000, bitDepth:24, audioQuality:'HI_RES_LOSSLESS', quality:'Lossless · 24-bit / 96 kHz'  };
  if (fmt === 6)  return { codec:'flac', container:'flac', format:'flac', manifest:'none', encrypted:false, sampleRate: 44100, bitDepth:16, audioQuality:'LOSSLESS',         quality:'Lossless · 16-bit / 44.1 kHz' };
  return { codec:'mp3', container:'mp3', format:'mp3', manifest:'none', encrypted:false, audioQuality:'HIGH', quality:'High · MP3 320 kbps' };
}

// ── Qobuz-DL (qb:) — primary: soundfean-musics, fallback: qobuz-dll.foreyou ──
async function qbSearch(q) {
  const d     = await qbGet(`/api/get-music?q=${encodeURIComponent(q)}&offset=0`);
  if (!d) return [];
  const items = d?.data?.tracks?.items ?? d?.tracks?.items ?? (Array.isArray(d) ? d : []);
  if (!Array.isArray(items)) return [];
  const tracks = items.slice(0, 12).flatMap(t => {
    if (!t.id || !t.title) return [];
    const sr = t.maximum_sampling_rate, bd = t.maximum_bit_depth;
    return [{
      id: `qb:${t.id}`, title: str(t.title),
      artist:   str(t.performer?.name ?? t.artist?.name ?? t.artist),
      album:    str(t.album?.title),
      artwork:  abs(t.album?.image?.large ?? t.album?.image?.xlarge ?? t.album?.image?.small),
      duration: num(t.duration),
      codec: 'flac', container: 'flac', manifest: 'none', encrypted: false,
      ...(bd ? { bitDepth: bd } : {}),
      ...(sr ? { sampleRate: Math.round(sr * 1000) } : {}),
      quality: bd && sr ? `Lossless · ${bd}-bit / ${sr} kHz` : 'Lossless'
    }];
  });
  log('QB', `parsed ${tracks.length} tracks`);
  return tracks;
}

async function qbStream(upId, quality) {
  const fmt = qbFmt(quality);
  const d   = await qbGet(`/api/download-music?track_id=${upId}&quality=${fmt}`);
  const url = d?.data?.url ?? d?.url ?? d?.data?.download_url ?? d?.download_url;
  if (!url?.startsWith('https://')) return null;
  // Merge any extra bit-depth / sample-rate the upstream gives us on top of our known meta
  const meta = qbMeta(fmt);
  const bd   = d?.data?.bit_depth   ?? d?.bit_depth   ?? d?.bitDepth   ?? meta.bitDepth;
  const sr   = d?.data?.sample_rate ?? d?.sample_rate ?? d?.sampleRate ?? meta.sampleRate;
  log('QB', `stream ok: url=present [${bd}-bit / ${sr}Hz]`);
  return { ...meta, url, bitDepth: bd, sampleRate: sr };
}

// ── Qobuz addon customEndpoint adapter ────────────────────────────────────────
// The qobuz-web addon maps its internal quality codes to these three values
// before calling GET {customEndpoint}/download-url/{id}?quality=...
//   internal 27 → hi96   (24-bit hi-res)
//   internal  7 → hi24   (24-bit)
//   internal  6 → flac   (16-bit CD)
// Never map `flac` to the worker's `low` value (5 = MP3): that would let a
// lossy file wear a lossless label. The final bytes still decide the codec.
function qbCustomFmt(quality) {
  const s = str(quality).toLowerCase();
  if (s === 'hi96') return 27;
  if (s === 'hi24') return 7;
  return 6;
}

/**
 * Response shape expected by `parseDownloadInfo()` in qobuz-web/index.js:
 *   { url | download_url | link, bit_depth?, sampling_rate? }
 * Only pass through measurements the upstream actually states. Omitted fields
 * stay omitted so the app cannot mistake a claim for a measurement.
 */
async function qbDownloadUrl(trackId, quality) {
  const fmt = qbCustomFmt(quality);
  const d = await qbGet(`/api/download-music?track_id=${encodeURIComponent(trackId)}&quality=${fmt}`);
  if (!d) return null;

  const nested = d.data || {};
  const url = d.download_url || d.url || d.link ||
              nested.download_url || nested.url || nested.link;
  if (typeof url !== 'string' || !/^https:\/\//i.test(url)) return null;

  const bitDepthRaw = Number(nested.bit_depth ?? d.bit_depth ?? 0);
  const sampleRaw   = Number(nested.sample_rate ?? d.sample_rate ?? 0);
  const sampleRate  = sampleRaw > 0 && sampleRaw < 1000
    ? Math.round(sampleRaw * 1000)
    : sampleRaw;

  return {
    url,
    ...(bitDepthRaw > 0 ? { bit_depth: bitDepthRaw } : {}),
    ...(sampleRate > 0 ? { sampling_rate: sampleRate } : {}),
    quality: fmt,
  };
}

// ── Standalone .sflx addon API ────────────────────────────────────────────────
// A stock Mornye host can install a `.sflx` whose manifest omits `signedSession`.
// That addon has no zarz credentials of its own: it asks this Worker for catalog
// rows and signed download URLs. Only the Worker holds provider config/secrets.
//
// These mappers deliberately state catalog resolutions, never a codec claim.
// `audio_quality` is like qobuz-web's own label ("24bit/96kHz") and is omitted
// unless the upstream actually states both numbers. Codec and final bit depth
// are decided by the downloaded bytes and the decoder on the device.
function qbAddonTrack(raw) {
  if (!raw || !raw.id) return null;
  const id = String(raw.id).trim();
  if (!/^\d+$/.test(id)) return null;

  const title = str(raw.title || raw.name).trim();
  if (!title) return null;

  const performer = raw.performer || raw.artist || {};
  const artist = str(typeof performer === 'string'
    ? performer
    : (performer.name || '')).trim();

  const album = raw.album || {};
  const albumName = str(album.title || album.name || '').trim();
  const image = album.image || {};
  const artwork = abs(
    image.large || image.xlarge || image.medium || image.small || album.cover || ''
  );

  const durationMsRaw = Number(raw.duration_ms || 0);
  const durationSec   = Number(raw.duration || 0);
  const durationMs    = durationMsRaw > 0
    ? Math.round(durationMsRaw)
    : (durationSec > 0 ? Math.round(durationSec * 1000) : 0);

  const bitDepthRaw = Number(raw.maximum_bit_depth || 0);
  const sampleRaw   = Number(raw.maximum_sampling_rate || 0);
  const sampleHz    = sampleRaw > 0 && sampleRaw < 1000
    ? Math.round(sampleRaw * 1000)
    : sampleRaw;
  const sampleDisplay = sampleHz > 0
    ? (sampleHz % 1000 === 0 ? String(sampleHz / 1000) : (sampleHz / 1000).toFixed(1))
    : '';

  return {
    id,
    item_type: 'track',
    name: title,
    artists: artist,
    album_name: albumName,
    duration_ms: durationMs,
    cover_url: artwork,
    isrc: str(raw.isrc || ''),
    qobuz_id: id,
    ...(bitDepthRaw > 0 && sampleDisplay
      ? { audio_quality: `${bitDepthRaw}bit/${sampleDisplay}kHz` }
      : {}),
  };
}

function qbAddonItems(payload) {
  if (!payload) return [];
  if (Array.isArray(payload)) return payload;
  return payload?.data?.tracks?.items ?? payload?.tracks?.items ?? [];
}

async function qbAddonSearch(query, limit) {
  const n = Math.min(Math.max(Number(limit) || 20, 1), 50);
  const d = await qbGet(`/api/get-music?q=${encodeURIComponent(query)}&offset=0`);
  const items = qbAddonItems(d);
  if (!Array.isArray(items)) return [];

  const tracks = [];
  for (const raw of items) {
    const track = qbAddonTrack(raw);
    if (track) tracks.push(track);
    if (tracks.length >= n) break;
  }
  log('QB-ADDON', `search chars=${query.length} → ${tracks.length} tracks`);
  return tracks;
}

async function qbAddonTrackById(trackId) {
  const d = await qbGet(`/api/get-music?q=${encodeURIComponent(trackId)}&offset=0`);
  const items = qbAddonItems(d);
  if (!Array.isArray(items)) return null;
  for (const raw of items) {
    if (String(raw?.id || '') === trackId) return qbAddonTrack(raw);
  }
  log('QB-ADDON', `track lookup idChars=${trackId.length} not found`);
  return null;
}

// ── Monochrome (mc:) — streams routed through HF (shared ID space) ────────────
async function mcSearch(q) {
  const data  = await getJSON('MC', `${cfg.MC}/search/tracks?q=${encodeURIComponent(q)}`, MC_HF_TIMEOUT);
  if (!data) return [];
  const items = Array.isArray(data) ? data : (data.tracks ?? []);
  if (!Array.isArray(items)) return [];
  const tracks = items.slice(0, 12).flatMap(t => {
    const id = t.id ?? t.trackId;
    if (!id || !t.title) return [];
    return [{
      id: `mc:${id}`, title: str(t.title),
      artist:   Array.isArray(t.artistNames) ? t.artistNames.join(', ') : str(t.artist?.name ?? t.artist),
      album:    str(t.albumTitle ?? t.album?.title ?? t.album ?? ''),
      artwork:  abs(t.artwork ?? t.cover ?? ''),
      duration: Math.round(num(t.duration) / 1000), // MC sends ms
      codec: 'flac', container: 'flac', manifest: 'none', encrypted: false, quality: 'Lossless'
    }];
  });
  log('MC', `parsed ${tracks.length} tracks from ${items.length} raw`);
  return tracks;
}

async function mcStream(upId) {
  log('MC', `routing stream ${upId} → HF (shared ID space)`);
  return hfStream(upId); // HF and MC share the same Monochrome track IDs
}

// ── HiFi / TIDAL (hf:) ───────────────────────────────────────────────────────
async function hfSearch(q, quality) {
  const d     = await getJSON('HF', `${cfg.HF}/search?q=${encodeURIComponent(q)}&quality=${encodeURIComponent(quality)}`, MC_HF_TIMEOUT);
  if (!d) return [];
  const items = d?.tracks ?? (Array.isArray(d) ? d : []);
  if (!Array.isArray(items)) return [];
  const tracks = items.slice(0, 12).flatMap(t => {
    if (!t.id || !t.title) return [];
    return [{
      id: `hf:${t.id}`, title: str(t.title),
      artist:  str(t.artist), album: str(t.album ?? ''),
      artwork: abs(t.artworkURL ?? t.artwork ?? ''),
      duration: num(t.duration),
      codec: 'flac', container: 'flac', manifest: 'none', encrypted: false,
      ...(t.sampleRate ? { sampleRate: t.sampleRate } : {}),
      ...(t.bitDepth   ? { bitDepth:   t.bitDepth   } : {}),
      quality: t.audioQuality === 'HI_RES_LOSSLESS' ? 'Lossless · 24-bit / 96 kHz' : 'Lossless · 16-bit / 44.1 kHz'
    }];
  });
  log('HF', `parsed ${tracks.length} tracks from ${items.length} raw`);
  return tracks;
}

async function hfStream(upId) {
  const d   = await getJSON('HF', `${cfg.HF}/stream/${upId}`, MC_HF_TIMEOUT);
  const url = d?.url;
  if (!url?.startsWith('https://')) return null;
  if (d.encrypted === true) { log('HF', 'encrypted — refusing'); return null; }
  log('HF', 'stream ok: url=present');
  const codec = (d.format ?? d.codec ?? 'flac').toLowerCase().replace('audio/', '');
  const bd    = d.bitDepth   ?? d.bit_depth   ?? null;
  const sr    = d.sampleRate ?? d.sample_rate ?? null;
  // Derive audioQuality so LastWave classifies the stream tier correctly
  const aq = d.audioQuality ?? d.audio_quality ??
    (bd >= 24 ? 'HI_RES_LOSSLESS' : (codec === 'flac' ? 'LOSSLESS' : 'HIGH'));
  return {
    url, codec, container: codec, format: codec,
    manifest: d.manifest ?? 'none', encrypted: false,
    ...(bd != null ? { bitDepth:     bd } : {}),
    ...(sr != null ? { sampleRate:   sr } : {}),
    audioQuality: aq,
    quality: d.quality ?? (bd >= 24 ? `Lossless · ${bd}-bit` : 'Lossless'),
    ...(d.headers && Object.keys(d.headers).length ? { headers: d.headers } : {})
  };
}

// ── LastWave (lw:) ───────────────────────────────────────────────────────────
// LW is a BitChord-contract addon — same search/stream shape we already know.
// Root URL includes the personal token, set via LASTWAVE_ROOT env var.
// Returns Dolby Atmos when quality=atmos; otherwise FLAC lossless.
// Completely independent of Monochrome and Qobuz — our insurance provider.
//
// The LW server uses HMAC-SHA256 request signing to distinguish real clients
// from scrapers. Unsigned requests get a prank/decoy audio file back.
// We replicate the same signing scheme here using the Web Crypto API.
// Signing scheme (from AddonClient.kt):
//   message  = "${ts}\nGET\n${path}\n${token}"
//   X-LW-TS  = ts (unix seconds)
//   X-LW-Sign = hmac_sha256(secret, message) as hex

// Cache the imported CryptoKey — importKey is the slow part of HMAC signing.
// Keyed by secret string so a custom LW_SECRET env var still works correctly.
const _lwKeys = new Map();
async function getLwKey(secretStr) {
  if (_lwKeys.has(secretStr)) return _lwKeys.get(secretStr);
  const key = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(secretStr),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
  );
  _lwKeys.set(secretStr, key);
  return key;
}

/** True for known prank/decoy CDN streams. */
function lwIsDecoy(url) {
  if (!url) return false;
  const l = url.toLowerCase();
  return l.includes('pranks-cdn') || l.includes('definatelynagato');
}

/**
 * Extract the /a/<token>/ segment from a LastWave URL path.
 * e.g. <root>/a/<token>/search
 *   => token = "feda7ca..."
 */
function lwExtractToken(urlStr) {
  try {
    const path = new URL(urlStr).pathname;
    const m = path.match(/\/a\/([^/]+)/);
    return m ? m[1] : null;
  } catch {
    return null;
  }
}

/**
 * Signed fetch for LastWave endpoints.
 * Attaches X-LW-TS, X-LW-Sign, X-LW-Intent, and User-Agent so the server
 * returns the real stream instead of the decoy prank file.
 */
async function lwFetch(url, intent = 'stream') {
  const ts = Math.floor(Date.now() / 1000).toString();

  // Parse path and token from the full URL for the HMAC message.
  let path = '/';
  let token = '';
  try {
    const parsed = new URL(url);
    // LastWave HMAC expects only the pathname (without query parameters)
    path  = parsed.pathname;
    const m = parsed.pathname.match(/\/a\/([^/]+)/);
    token = m ? m[1] : '';
  } catch { /* keep defaults */ }

  const message = `${ts}\nGET\n${path}\n${token}`;

  // Derive the signing secret from Worker config only; no built-in credential.
  if (!cfg.LW_SECRET) { log('LW', 'LW_SECRET not set — skipping signed request'); return null; }
  const secretStr = cfg.LW_SECRET.trim();

  let signHex = '';
  try {
    const cryptoKey = await getLwKey(secretStr); // cached — importKey runs once per isolate
    const sigBuf    = await crypto.subtle.sign('HMAC', cryptoKey, new TextEncoder().encode(message));
    signHex = Array.from(new Uint8Array(sigBuf)).map(b => b.toString(16).padStart(2, '0')).join('');
  } catch (e) {
    log('LW', `HMAC sign failed (${e.name}) — request may return decoy`);
  }

  const headers = {
    'User-Agent':   'LastWave-Player/1.0',
    'Accept':       'application/json',
    'X-LW-Intent':  intent,
    'X-LW-TS':      ts,
    'X-LW-Sign':    signHex,
  };

  log('LW', `→ GET <redacted> [signed ts=${ts}]`);
  try {
    const r   = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT), headers });
    const raw = await r.text();
    log('LW', `← HTTP ${r.status} body=${raw.length}B`);
    if (!r.ok) return null;
    return JSON.parse(raw);
  } catch (e) {
    log('LW', `✗ ${e.name === 'TimeoutError' ? `timeout after ${TIMEOUT}ms` : e.name}`);
    return null;
  }
}

async function lwSearch(q, quality) {
  if (!cfg.LW) { log('LW', 'LASTWAVE_ROOT not set — skipping'); return []; }
  const d = await lwFetch(`${cfg.LW}search?q=${encodeURIComponent(q)}&quality=${encodeURIComponent(quality)}`, 'search');
  if (!d) return [];
  const items = d?.tracks ?? (Array.isArray(d) ? d : []);
  if (!Array.isArray(items) || items.length === 0) {
    log('LW', `no tracks. keys: ${Object.keys(d || {}).join(', ')}`);
    return [];
  }
  log('LW', `first item keys: ${Object.keys(items[0]).join(', ')}`);

  const tracks = items.slice(0, 12).flatMap(t => {
    // Filter swap_ synthetic/fallback IDs — they have no real audio backing
    if (!t.id || !t.title || String(t.id).startsWith('swap_')) return [];
    const isAtmos = t.audioQuality === 'DOLBY_ATMOS' || t.manifest === 'hls';
    const codec   = isAtmos ? 'aac'  : (t.format ?? t.codec ?? 'flac').toLowerCase().replace('audio/', '');
    return [{
      id:       `lw:${t.id}`,
      title:    str(t.title),
      artist:   str(t.artist),
      album:    str(t.album ?? ''),
      artwork:  abs(t.artworkURL ?? t.artwork ?? t.coverUrl ?? ''),
      duration: num(t.duration),
      codec, container: codec,
      manifest:  isAtmos ? 'hls' : (t.manifest ?? 'none'),
      encrypted: false,
      ...(t.sampleRate ? { sampleRate: t.sampleRate } : {}),
      ...(t.bitDepth   ? { bitDepth:   t.bitDepth   } : {}),
      quality: isAtmos
        ? 'Dolby Atmos'
        : (t.audioQuality === 'HI_RES_LOSSLESS' ? 'Lossless · 24-bit / 96 kHz' : 'Lossless · 16-bit / 44.1 kHz')
    }];
  });
  log('LW', `parsed ${tracks.length} tracks from ${items.length} raw`);
  return tracks;
}

async function lwStream(upId, quality) {
  if (!cfg.LW) return null;
  const d = await lwFetch(`${cfg.LW}stream/${upId}?quality=${encodeURIComponent(quality)}`, 'stream');
  const url = d?.url ?? d?.streamUrl ?? d?.directUrl ?? d?.data?.url;
  if (!url?.startsWith('https://')) {
    if (d) log('LW', `stream: no url. keyCount=${Object.keys(d).length}`);
    return null;
  }
  // Reject prank / decoy streams the server might return for unsigned requests
  if (lwIsDecoy(url)) {
    log('LW', 'stream: decoy/prank URL detected — signing may have failed');
    return null;
  }
  if (d.encrypted === true) { log('LW', 'encrypted — refusing'); return null; }
  const codec = (d.format ?? d.codec ?? 'flac').toLowerCase().replace('audio/', '');
  const bd    = d.bitDepth   ?? d.bit_depth   ?? d.data?.bitDepth   ?? null;
  const sr    = d.sampleRate ?? d.sample_rate ?? d.data?.sampleRate ?? null;
  // Derive audioQuality so LastWave classifies the stream tier correctly
  const aq = d.audioQuality ?? d.audio_quality ?? d.data?.audioQuality ??
    (bd >= 24 ? 'HI_RES_LOSSLESS' : (codec === 'flac' ? 'LOSSLESS' : 'HIGH'));
  log('LW', `stream ok: url=present [${bd ?? '?'}-bit / ${sr ?? '?'}Hz audioQuality=${aq}]`);
  return {
    url, codec, container: codec, format: codec,
    manifest:  d.manifest ?? 'none',
    encrypted: false,
    ...(bd != null ? { bitDepth:   bd } : {}),
    ...(sr != null ? { sampleRate: sr } : {}),
    audioQuality: aq,
    quality: d.quality ?? d.data?.quality ?? 'Lossless',
    ...(d.headers && Object.keys(d.headers).length ? { headers: d.headers } : {})
  };
}

// ── Aurix (ax:) ──────────────────────────────────────────────────────────────
// Aurix (vanquzie/aurix) — full BitChord addon backed by Tidal + Qobuz + Deezer
// with real per-user credentials. URL contains a personal token from the dashboard.
// Follows the standard BitChord contract — same shapes as LW/HF.

async function axSearch(q, quality) {
  if (!cfg.AX) { log('AX', 'AURIX_ROOT not set — skipping'); return []; }
  const d = await getJSON('AX', `${cfg.AX}search?q=${encodeURIComponent(q)}&quality=${encodeURIComponent(quality)}`);
  if (!d) return [];
  const items = d?.tracks ?? (Array.isArray(d) ? d : []);
  if (!Array.isArray(items) || items.length === 0) {
    log('AX', `no tracks. keys: ${Object.keys(d || {}).join(', ')}`);
    return [];
  }
  log('AX', `first item keys: ${Object.keys(items[0]).join(', ')}`);
  const tracks = items.slice(0, 12).flatMap(t => {
    if (!t.id || !t.title) return [];
    const codec = str(t.codec ?? t.format ?? 'flac').toLowerCase().replace('audio/', '');
    return [{
      id:       `ax:${t.id}`,
      title:    str(t.title),
      artist:   str(t.artist),
      album:    str(t.album ?? ''),
      artwork:  abs(t.artwork ?? t.artworkURL ?? t.coverUrl ?? ''),
      duration: num(t.duration),
      codec, container: codec,
      manifest:  t.manifest ?? 'none',
      encrypted: false,
      ...(t.sampleRate ? { sampleRate: t.sampleRate } : {}),
      ...(t.bitDepth   ? { bitDepth:   t.bitDepth   } : {}),
      quality: t.quality ?? 'Lossless'
    }];
  });
  log('AX', `parsed ${tracks.length} tracks from ${items.length} raw`);
  return tracks;
}

async function axStream(upId, quality) {
  if (!cfg.AX) return null;
  const d   = await getJSON('AX', `${cfg.AX}stream/${upId}?quality=${encodeURIComponent(quality)}`);
  const url = d?.url;
  if (!url?.startsWith('https://')) {
    if (d) log('AX', `stream: no url. keyCount=${Object.keys(d).length}`);
    return null;
  }
  if (d.encrypted === true) { log('AX', 'encrypted — refusing'); return null; }
  log('AX', 'stream ok: url=present');
  const codec = str(d.codec ?? d.format ?? 'flac').toLowerCase().replace('audio/', '');
  return {
    url, codec, container: codec,
    manifest:  d.manifest  ?? 'none',
    encrypted: false,
    ...(d.sampleRate ? { sampleRate: d.sampleRate } : {}),
    ...(d.bitDepth   ? { bitDepth:   d.bitDepth   } : {}),
    quality: d.quality ?? 'Lossless',
    ...(d.headers && Object.keys(d.headers).length ? { headers: d.headers } : {})
  };
}

// ── Quality scoring ───────────────────────────────────────────────────────────
// Returns a numeric score for a track — higher = better quality.
// Used to pick the best version when multiple providers have the same song.
//
//  24-bit/192kHz FLAC  →  4,608,000   (QB hi-res)
//  24-bit/96kHz  FLAC  →  2,304,000   (QB/HF hi-res)
//  Dolby Atmos   HLS   →    900,000   (premium spatial, separate tier)
//  16-bit/44.1kHz FLAC →    705,600   (LW/MC CD lossless)
//  AAC 320kbps         →    300,000
//  MP3 320kbps         →    200,000
function qualityScore(t) {
  // Dolby Atmos
  if (t.manifest === 'hls' || String(t.quality ?? '').toLowerCase().includes('atmos')) {
    return 900_000;
  }
  // FLAC / ALAC — score by bit-depth × sample-rate
  if (t.codec === 'flac' || t.codec === 'alac') {
    const bd = t.bitDepth   || 16;
    const sr = t.sampleRate || 44_100;
    return bd * sr; // 24*192000 = 4,608,000 at best
  }
  // Lossy
  if (t.codec === 'aac') return 300_000;
  if (t.codec === 'mp3') return 200_000;
  // Unknown codec — try to infer from quality label
  const ql = String(t.quality ?? '').toLowerCase();
  if (ql.includes('24-bit') || ql.includes('hi-res')) return 2_000_000;
  if (ql.includes('lossless'))                         return 700_000;
  return 400_000; // safe fallback
}

// ── Search & Stream ───────────────────────────────────────────────────────────
async function doSearch(q, quality) {
  log('SEARCH', `queryChars=${q.length} quality=${quality}`);
  const [ax, lw, qb, mc, hf] = await Promise.allSettled([
    axSearch(q, quality),   // Aurix — Tidal/Qobuz/Deezer with real creds
    lwSearch(q, quality),   // LastWave — TIDAL via HiFi network
    qbSearch(q),            // Qobuz proxy (primary → fallback)
    mcSearch(q),            // Monochrome CDN
    hfSearch(q, quality)    // HiFi/TIDAL public addon
  ]);
  const axT = ax.status === 'fulfilled' ? ax.value : [];
  const lwT = lw.status === 'fulfilled' ? lw.value : [];
  const qbT = qb.status === 'fulfilled' ? qb.value : [];
  const mcT = mc.status === 'fulfilled' ? mc.value : [];
  const hfT = hf.status === 'fulfilled' ? hf.value : [];

  // Quality-score dedup — best version of each song wins regardless of which
  // provider returned it first. 24-bit always beats 16-bit for the same track.
  const byKey = new Map(); // key → { track, score }

  for (const t of [...axT, ...lwT, ...qbT, ...mcT, ...hfT]) {
    const key   = dedupKey(t.title, t.artist);
    const score = qualityScore(t);
    const prev  = byKey.get(key);
    if (!prev || score > prev.score) {
      byKey.set(key, { track: t, score });
    }
  }

  const result = [...byKey.values()].slice(0, 20).map(({ track: t }) => {
    // Populate metadata store so stream response can carry display info
    metaSet(t.id, {
      title: t.title, artist: t.artist,
      album: t.album, artwork: t.artwork, duration: t.duration
    });
    return t;
  });

  // Drop tracks that don't match the query well enough — returns [] so BitChord
  // falls through to the next addon (e.g. JioSaavn) instead of playing a wrong song
  const filtered = relevanceFilter(q, result);

  log('SEARCH', `returning ${filtered.length} tracks (ax:${axT.length} lw:${lwT.length} qb:${qbT.length} mc:${mcT.length} hf:${hfT.length} dropped:${result.length - filtered.length})`);
  return filtered;
}

async function doStream(id, quality) {
  log('STREAM', `idChars=${id.length} quality=${quality}`);
  const colon  = id.indexOf(':');
  if (colon < 0) return null;
  const prefix = id.slice(0, colon);
  const upId   = id.slice(colon + 1);

  let result = null;
  if      (prefix === 'ax') result = await axStream(upId, quality);
  else if (prefix === 'lw') result = await lwStream(upId, quality);
  else if (prefix === 'qb') result = await qbStream(upId, quality);
  else if (prefix === 'mc') result = await mcStream(upId);
  else if (prefix === 'hf') result = await hfStream(upId);

  if (!result) return null;

  // Authenticity guard: reject decoy streams even if they slipped through
  if (isDecoy(result.url)) {
    log('STREAM', `decoy URL rejected for idChars=${id.length} — source returned prank audio`);
    return null;
  }

  // Attach display metadata from the search phase (if available).
  // This lets BitChord show the correct "now playing" info and helps
  // surface mismatches — if the title shown doesn't match what you searched,
  // the upstream index has an incorrect mapping for that track ID.
  const meta = metaGet(id);
  if (meta) {
    result.title   = result.title   ?? meta.title;
    result.artist  = result.artist  ?? meta.artist;
    result.album   = result.album   ?? meta.album;
    result.artwork = result.artwork ?? meta.artwork;
  }

  return result;
}

// ── Response helpers ──────────────────────────────────────────────────────────
const CORS = {
  'Access-Control-Allow-Origin':  '*',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
  'Content-Type': 'application/json',
};
const json = (status, body) =>
  new Response(JSON.stringify(body), { status, headers: CORS });

// ── Request handler ───────────────────────────────────────────────────────────
async function handle(request, env) {
  initCfg(env);

  if (request.method === 'OPTIONS')
    return new Response(null, { status: 204, headers: CORS });

  const { pathname, searchParams } = new URL(request.url);
  const start = Date.now();
  log('REQ', `▶ ${request.method} ${pathname}`);

  // /manifest.json
  if (pathname === '/manifest.json') {
    log('REQ', `◀ 200 manifest (${Date.now() - start}ms)`);
    return json(200, MANIFEST);
  }

  // /health
  if (pathname === '/health') {
    // Ping all providers in parallel — shows which are actually reachable right now
    const ping = async (tag, url) => {
      try {
        const r = await fetch(url, { signal: AbortSignal.timeout(5000), headers: HDRS });
        return { ok: r.ok, status: r.status };
      } catch (e) {
        return { ok: false, status: e.name === 'TimeoutError' ? 'timeout' : e.name };
      }
    };
    const [ax, lw, qbP, qbF, mc, hf] = await Promise.all([
      cfg.AX ? ping('AX', `${cfg.AX}manifest.json`)    : Promise.resolve({ ok: false, status: 'not configured' }),
      cfg.LW ? ping('LW', `${cfg.LW}manifest.json`)    : Promise.resolve({ ok: false, status: 'not configured' }),
      ping('QB-P', `${cfg.QB_PRIMARY}/health`),
      ping('QB-F', `${cfg.QB_FALLBACK}/health`),
      ping('MC',   `${cfg.MC}/search/tracks?q=test`),
      ping('HF',   `${cfg.HF}/health`),
    ]);
    const status = {
      ok: true,
      version: '1.8.0',
      ts: Date.now(),
      routes: { downloadUrl: true, addonQobuz: true },
      providers: { ax, lw, qb_primary: qbP, qb_fallback: qbF, mc, hf },
    };
    log('REQ', `◀ 200 health ax=${ax.status} lw=${lw.status} qb-p=${qbP.status} qb-f=${qbF.status} mc=${mc.status} hf=${hf.status} (${Date.now() - start}ms)`);
    return json(200, status);
  }

  // /search?q=...&quality=...
  if (pathname === '/search') {
    const q       = (searchParams.get('q') ?? '').trim();
    const quality = searchParams.get('quality') ?? 'lossless';
    if (!q) return json(200, { tracks: [] });

    const key    = `s:${q}:${quality}`;
    const cached = cacheGet(key);
    if (cached) {
      log('REQ', `◀ 200 search "${q}" → ${cached.length} [CACHE] (${Date.now() - start}ms)`);
      return json(200, { tracks: cached });
    }
    const tracks = await doSearch(q, quality);
    cacheSet(key, tracks, 600_000);
    log('REQ', `◀ 200 search "${q}" → ${tracks.length} tracks (${Date.now() - start}ms)`);
    return json(200, { tracks });
  }

  // /qobuz/search?q=...&limit=... — catalog rows for the standalone .sflx addon.
  // Only Qobuz data is returned here; the worker's multi-provider /search route
  // remains for BitChord's own source list. An addon must not silently pick a
  // Tidal or LastWave result while wearing a Qobuz badge.
  if (pathname === '/qobuz/search') {
    const query = (searchParams.get('q') ?? '').trim();
    if (!query) return json(200, { tracks: [] });
    const limit = Number(searchParams.get('limit') ?? 20) || 20;
    const key = `qa:s:${query}:${limit}`;
    const cached = cacheGet(key);
    if (cached) {
      log('QB-ADDON', `200 search chars=${query.length} [CACHE] (${Date.now() - start}ms)`);
      return json(200, { tracks: cached });
    }
    const tracks = await qbAddonSearch(query, limit);
    cacheSet(key, tracks, 600_000);
    log('QB-ADDON', `200 search chars=${query.length} → ${tracks.length} (${Date.now() - start}ms)`);
    return json(200, { tracks });
  }

  // /qobuz/track/:id — fallback when the addon has no remembered search row.
  const qt = pathname.match(/^\/qobuz\/track\/([^/]+)$/);
  if (qt) {
    const trackId = decodeURIComponent(qt[1]);
    if (!/^\d+$/.test(trackId)) {
      log('QB-ADDON', `400 invalid id chars=${trackId.length} (${Date.now() - start}ms)`);
      return json(400, { error: 'invalid track id' });
    }
    const key = `qa:t:${trackId}`;
    const cached = cacheGet(key);
    if (cached) {
      log('QB-ADDON', `200 track idChars=${trackId.length} [CACHE] (${Date.now() - start}ms)`);
      return json(200, { track: cached });
    }
    const track = await qbAddonTrackById(trackId);
    if (!track) {
      log('QB-ADDON', `404 track idChars=${trackId.length} (${Date.now() - start}ms)`);
      return json(404, { error: 'track not found' });
    }
    cacheSet(key, track, 600_000);
    log('QB-ADDON', `200 track idChars=${trackId.length} (${Date.now() - start}ms)`);
    return json(200, { track });
  }

  // /download-url/:id?quality=hi96|hi24|flac
  // Exact contract of qobuz-web's `customEndpoint` setting. Deliberately kept
  // separate from /stream/:id: that route returns this worker's own rich JSON,
  // while the addon expects the small qobuz-rest-api shape.
  const dl = pathname.match(/^\/download-url\/([^/]+)$/);
  if (dl) {
    const trackId = decodeURIComponent(dl[1]);
    const quality = searchParams.get('quality') || 'flac';
    if (!/^\d+$/.test(trackId)) {
      log('QB-DL', `400 invalid id chars=${trackId.length} (${Date.now() - start}ms)`);
      return json(400, { error: 'invalid track id' });
    }

    const key = `qbdl:${trackId}:${quality}`;
    const cached = cacheGet(key);
    if (cached) {
      log('QB-DL', `200 cached idChars=${trackId.length} q=${quality} (${Date.now() - start}ms)`);
      return json(200, cached);
    }

    const info = await qbDownloadUrl(trackId, quality);
    if (!info) {
      log('QB-DL', `404 idChars=${trackId.length} q=${quality} (${Date.now() - start}ms)`);
      return json(404, { error: 'download url unavailable' });
    }
    cacheSet(key, info, 240_000);
    log(
      'QB-DL',
      `200 idChars=${trackId.length} q=${quality} fmt=${info.quality} ` +
        `bitDepth=${info.bit_depth ?? 'unstated'} sampleRate=${info.sampling_rate ?? 'unstated'} ` +
        `(${Date.now() - start}ms)`,
    );
    return json(200, info);
  }

  // /stream/:id?quality=...
  const m = pathname.match(/^\/stream\/(.+)$/);
  if (m) {
    const id      = decodeURIComponent(m[1]);
    const quality = searchParams.get('quality') ?? 'lossless';
    const key     = `st:${id}:${quality}`;
    const cached  = cacheGet(key);
    if (cached) {
      log('REQ', `◀ 200 stream "${id}" [CACHE] (${Date.now() - start}ms)`);
      return json(200, cached);
    }
    const result = await doStream(id, quality);
    if (!result) {
      log('REQ', `◀ 404 stream "${id}" (${Date.now() - start}ms)`);
      return json(404, { error: 'track not available' });
    }
    cacheSet(key, result, 240_000);
    log('REQ', `◀ 200 stream idChars=${id.length} codec=${result.codec ?? '?'} (${Date.now() - start}ms)`);
    return json(200, result);
  }

  return json(404, { error: 'not found' });
}

// ── Entry point ───────────────────────────────────────────────────────────────
// Named export exists only so the local Node tests can exercise the handler
// without a network deployment. Cloudflare continues to use the default export.
export { handle };

export default {
  async fetch(request, env, ctx) {
    return handle(request, env);
  }
};
