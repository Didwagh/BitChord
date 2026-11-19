/*
 * Qobuz (Nexus Worker) — standalone Mornye `.sflx` addon.
 *
 * This package deliberately does NOT declare `signedSession`. It needs no
 * zarz bootstrap, no Connect flow and no app fork. All provider configuration
 * and credentials stay in the Cloudflare Worker.
 *
 * Contract used here is the stock Mornye addon API exposed to every `.sflx`:
 *   initialize(), customSearch()/searchTracks(), download()
 *   globals: settings, storage, http, file, log, gobackend, registerExtension
 *
 * Logging rule: no URLs, query strings, signed paths, tokens or response
 * bodies. Only counts, status codes, quality names and error classes.
 */
(function () {
  'use strict';

  var DEFAULT_WORKER = 'https://bitchord-nexus.jimmy-hayes.workers.dev';
  var remembered = {};

  function baseUrl() {
    var raw = '';
    try {
      raw = settings && settings.workerUrl ? String(settings.workerUrl).trim() : '';
    } catch (e) {
      raw = '';
    }
    if (!/^https:\/\//i.test(raw)) raw = DEFAULT_WORKER;
    return raw.replace(/\/+$/, '');
  }

  function firstNonEmpty() {
    for (var i = 0; i < arguments.length; i++) {
      var value = arguments[i];
      if (value === null || value === undefined) continue;
      var text = String(value).trim();
      if (text) return text;
    }
    return '';
  }

  function progress(onProgress, percent) {
    if (typeof onProgress !== 'function') return;
    var value = Number(percent) || 0;
    if (value < 0) value = 0;
    if (value > 100) value = 100;
    try {
      onProgress(Math.round(value));
    } catch (e) {}
  }

  function parentDirectory(path) {
    var text = String(path || '').trim().replace(/\\/g, '/');
    var idx = text.lastIndexOf('/');
    return idx > 0 ? text.substring(0, idx) : '';
  }

  function ensureFlacExtension(path) {
    var text = String(path || '').trim();
    if (!text) return text;
    var dot = text.lastIndexOf('.');
    var slash = text.lastIndexOf('/');
    if (dot <= slash) return text + '.flac';
    if (text.substring(dot).toLowerCase() === '.flac') return text;
    return text.substring(0, dot) + '.flac';
  }

  function deleteQuietly(path) {
    if (!file || typeof file.delete !== 'function') return;
    try {
      file.delete(String(path));
    } catch (e) {}
  }

  function workerJSON(path) {
    var response = http.get(baseUrl() + path, {
      'Accept': 'application/json',
      'User-Agent': 'BitChord-Qobuz-Worker/1.0'
    });
    if (!response) throw new Error('worker_no_response');
    if (!response.ok) throw new Error('worker_http_' + Number(response.status || 0));
    var body = typeof response.text === 'function' ? response.text() : String(response.body || '');
    try {
      return JSON.parse(body);
    } catch (e) {
      throw new Error('worker_bad_json');
    }
  }

  function normalizeTrack(raw) {
    if (!raw) return null;
    var id = firstNonEmpty(raw.id, raw.qobuz_id).replace(/^qb:/i, '').trim();
    if (!/^\d+$/.test(id)) return null;
    var name = firstNonEmpty(raw.name, raw.title);
    if (!name) return null;

    var artists = raw.artists || raw.artist || '';
    var artist = '';
    if (typeof artists === 'string') {
      artist = artists.trim();
    } else if (artists.length) {
      for (var i = 0; i < artists.length; i++) {
        var entry = artists[i];
        var label = typeof entry === 'string' ? entry : (entry && entry.name) || '';
        if (String(label).trim()) {
          artist = artist ? artist + ', ' + String(label).trim() : String(label).trim();
        }
      }
    }

    var durationMs = Number(raw.duration_ms || raw.durationMs || 0);
    if (!(durationMs > 0)) {
      var durationSec = Number(raw.duration || 0);
      durationMs = durationSec > 0 ? Math.round(durationSec * 1000) : 0;
    }

    return {
      id: id,
      item_type: 'track',
      name: name,
      artists: artist,
      album_name: firstNonEmpty(raw.album_name, raw.album),
      duration_ms: Math.round(durationMs),
      cover_url: firstNonEmpty(raw.cover_url, raw.artworkUrl, raw.artwork, raw.thumbnail),
      isrc: firstNonEmpty(raw.isrc, raw.ISRC),
      audio_quality: firstNonEmpty(raw.audio_quality, raw.audioQuality),
      qobuz_id: id
    };
  }

  function initialize() {
    remembered = {};
    return { success: true };
  }

  function cleanup() {
    remembered = {};
    return { success: true };
  }

  function customSearch(query, options) {
    var filter = String((options && options.filter) || '').trim().toLowerCase();
    if (filter === 'album' || filter === 'artist') return [];

    var text = String(query || '').trim();
    if (!text) return [];

    var limit = Number((options && options.limit) || 20);
    if (!(limit > 0) || limit > 50) limit = 20;

    var payload = workerJSON('/qobuz/search?q=' + encodeURIComponent(text) + '&limit=' + limit);
    var rows = payload && payload.tracks ? payload.tracks : [];
    var out = [];
    for (var i = 0; i < rows.length; i++) {
      var track = normalizeTrack(rows[i]);
      if (!track) continue;
      remembered[track.id] = track;
      try {
        storage.set('qbworker:track:' + track.id, JSON.stringify(track));
      } catch (e) {}
      out.push(track);
      if (out.length >= limit) break;
    }
    log.info('[QobuzWorker] search rows=' + out.length);
    return out;
  }

  function searchTracks(query, limit) {
    return customSearch(query, { limit: limit, filter: 'track' });
  }

  function rememberedTrack(id) {
    if (remembered[id]) return remembered[id];
    try {
      var raw = storage.get('qbworker:track:' + id, null);
      if (raw) {
        var track = normalizeTrack(JSON.parse(raw));
        if (track) {
          remembered[id] = track;
          return track;
        }
      }
    } catch (e) {}

    // Last resort: ask the Worker. This is only for a track reached outside a
    // search (for example a restored queue), never a substitute for metadata.
    try {
      var payload = workerJSON('/qobuz/track/' + encodeURIComponent(id));
      var fallback = normalizeTrack(payload && payload.track);
      if (fallback) {
        remembered[id] = fallback;
        return fallback;
      }
    } catch (e) {}

    return {
      id: id,
      item_type: 'track',
      name: id,
      artists: '',
      duration_ms: 0,
      cover_url: '',
      isrc: '',
      audio_quality: ''
    };
  }

  function normalizeQuality(quality) {
    var value = String(quality === null || quality === undefined ? '' : quality).trim().toUpperCase();
    if (value === 'HI_RES' || value === '7') return 'HI_RES';
    if (value === 'LOSSLESS' || value === '6') return 'LOSSLESS';
    return 'HI_RES_LOSSLESS';
  }

  function qualityChain(quality) {
    var tier = normalizeQuality(quality);
    if (tier === 'HI_RES_LOSSLESS') return ['hi96', 'hi24', 'flac'];
    if (tier === 'HI_RES') return ['hi24', 'flac'];
    return ['flac'];
  }

  function readAudioQuality(path) {
    if (!gobackend || typeof gobackend.getAudioQuality !== 'function') return null;
    try {
      var info = gobackend.getAudioQuality(String(path));
      if (!info || info.error) return null;
      return info;
    } catch (e) {
      return null;
    }
  }

  function audioDurationSeconds(info) {
    var duration = Number((info && info.duration) || 0);
    if (duration > 0) return Math.round(duration);
    var sampleRate = Number((info && info.sampleRate) || 0);
    var totalSamples = Number((info && info.totalSamples) || 0);
    if (sampleRate > 0 && totalSamples > 0) return Math.round(totalSamples / sampleRate);
    return 0;
  }

  function validateDuration(expectedMs, actualSec) {
    var expected = Math.round(Number(expectedMs || 0) / 1000);
    var actual = Math.round(Number(actualSec || 0));
    if (expected <= 0 || actual <= 0) return { valid: true, preview: false };
    var diff = Math.abs(expected - actual);
    if (diff <= 10) return { valid: true, preview: false };
    return { valid: false, preview: actual <= 35 && expected > 45 };
  }

  function downloadUrl(id, tier) {
    var payload = workerJSON('/download-url/' + encodeURIComponent(id) + '?quality=' + encodeURIComponent(tier));
    var nested = payload && payload.data ? payload.data : {};
    var url = firstNonEmpty(
      payload && payload.download_url,
      payload && payload.url,
      payload && payload.link,
      nested.download_url,
      nested.url,
      nested.link
    );
    if (!/^https:\/\//i.test(url)) throw new Error('no_https_download_url');
    return url;
  }

  function download(trackID, quality, outputPath, onProgress, options) {
    try {
      var id = firstNonEmpty(trackID).replace(/^qb:/i, '').trim();
      if (!/^\d+$/.test(id)) {
        return { success: false, error_message: 'Invalid Qobuz track id', error_type: 'invalid_argument' };
      }

      var prepared = (options && options.preparedContext) || {};
      var track = null;
      if (prepared.raw_track && firstNonEmpty(prepared.raw_track.id).replace(/^qb:/i, '') === id) {
        track = normalizeTrack(prepared.raw_track);
      } else if (prepared.host_track && firstNonEmpty(prepared.host_track.name)) {
        track = normalizeTrack(prepared.host_track);
      }
      if (!track) track = rememberedTrack(id);

      if (track.isrc && gobackend && typeof gobackend.checkISRCExists === 'function') {
        try {
          var existing = gobackend.checkISRCExists(parentDirectory(outputPath), track.isrc);
          if (existing && existing.exists && existing.filePath) {
            return {
              success: true,
              already_exists: true,
              file_path: String(existing.filePath)
            };
          }
        } catch (e) {}
      }

      progress(onProgress, 5);
      var finalPath = ensureFlacExtension(outputPath);
      var chain = qualityChain(quality);
      var lastError = 'no_candidate';

      for (var i = 0; i < chain.length; i++) {
        var tier = chain[i];
        var url;
        try {
          url = downloadUrl(id, tier);
        } catch (e) {
          lastError = e && e.message ? e.message : 'url_resolution_failed';
          continue;
        }

        progress(onProgress, 10);
        var transferred = file.download(url, finalPath, {
          headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android) AppleWebKit/537.36' }
        });
        if (!transferred || !transferred.success) {
          deleteQuietly(finalPath);
          lastError = transferred && transferred.error ? String(transferred.error) : 'transfer_failed';
          continue;
        }

        var writtenPath = firstNonEmpty(transferred.path, finalPath);
        var info = readAudioQuality(writtenPath);
        var duration = validateDuration(track.duration_ms, audioDurationSeconds(info));
        if (!duration.valid) {
          deleteQuietly(writtenPath);
          lastError = duration.preview ? 'preview_length_audio' : 'duration_mismatch';
          continue;
        }

        var result = { success: true, file_path: writtenPath };
        if (info) {
          var bitDepth = Number(info.bitDepth || 0);
          var sampleRate = Number(info.sampleRate || 0);
          var format = firstNonEmpty(info.format, info.codec, info.container).toLowerCase();
          if (format) result.format = format;
          if (bitDepth > 0) result.bit_depth = bitDepth;
          if (sampleRate > 0) result.sample_rate = sampleRate;
          if (bitDepth > 0 && sampleRate > 0) {
            var khz = sampleRate % 1000 === 0
              ? String(sampleRate / 1000)
              : (sampleRate / 1000).toFixed(1);
            result.audio_quality = bitDepth + 'bit/' + khz + 'kHz';
          }
        }

        progress(onProgress, 100);
        log.info('[QobuzWorker] download ok tier=' + tier + ' measured=' + (info ? 'yes' : 'no'));
        return result;
      }

      return {
        success: false,
        error_message: lastError,
        error_type: /preview/.test(lastError) ? 'duration_mismatch' : 'download_error'
      };
    } catch (e) {
      return {
        success: false,
        error_message: e && e.message ? e.message : 'runtime_error',
        error_type: 'runtime_error'
      };
    }
  }

  registerExtension({
    initialize: initialize,
    cleanup: cleanup,
    customSearch: customSearch,
    searchTracks: searchTracks,
    download: download
  });
})();
