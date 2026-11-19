import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const HERE = path.dirname(new URL(import.meta.url).pathname);
const code = fs.readFileSync(path.join(HERE, 'index.js'), 'utf8');
const manifest = JSON.parse(fs.readFileSync(path.join(HERE, 'manifest.json'), 'utf8'));

const FAKE_SIGNED_URL = 'https://cdn.test/4242.flac?sig=SECRET_SIGNATURE_VALUE';

function loadAddon({ failFirstDownload = false } = {}) {
  const calls = { worker: [], downloads: [], logs: [], stored: {} };
  let extension = null;
  let downloadAttempt = 0;

  const sandbox = {
    console,
    JSON,
    Math,
    Number,
    String,
    Array,
    Object,
    encodeURIComponent,
    decodeURIComponent,
    Date,
    settings: { workerUrl: 'https://worker.test/' },
    storage: {
      get(key, fallback) {
        return Object.prototype.hasOwnProperty.call(calls.stored, key)
          ? calls.stored[key]
          : fallback;
      },
      set(key, value) {
        calls.stored[key] = value;
        return true;
      },
    },
    http: {
      get(url) {
        const parsed = new URL(url);
        calls.worker.push(parsed.pathname + parsed.search);
        if (parsed.pathname === '/qobuz/search') {
          return jsonResponse({
            tracks: [{
              id: 4242,
              item_type: 'track',
              name: 'Alpha Beta',
              artists: 'Test Artist',
              album_name: 'Test Album',
              duration_ms: 210000,
              cover_url: 'https://art.test/cover.jpg',
              isrc: 'TESTISRC0001',
              audio_quality: '24bit/96kHz',
            }],
          });
        }
        if (parsed.pathname === '/download-url/4242') {
          return jsonResponse({
            url: FAKE_SIGNED_URL,
            bit_depth: 24,
            sampling_rate: 96000,
            quality: 27,
          });
        }
        return jsonResponse({ error: 'not found' }, 404);
      },
    },
    file: {
      download(url, outputPath) {
        calls.downloads.push({ url, outputPath });
        downloadAttempt += 1;
        if (failFirstDownload && downloadAttempt === 1) {
          return { success: false, error: 'http_503' };
        }
        return { success: true, path: outputPath };
      },
      delete() {},
    },
    gobackend: {
      getAudioQuality() {
        return { duration: 210, bitDepth: 24, sampleRate: 96000, format: 'FLAC' };
      },
      checkISRCExists() {
        return false;
      },
    },
    log: {
      info(...parts) { calls.logs.push(parts.join(' ')); },
      warn(...parts) { calls.logs.push(parts.join(' ')); },
      error(...parts) { calls.logs.push(parts.join(' ')); },
    },
    registerExtension(value) { extension = value; },
  };

  vm.runInNewContext(code, sandbox, { filename: 'index.js' });
  assert.ok(extension, 'registerExtension must be called');
  return { extension, calls };
}

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text() { return JSON.stringify(body); },
  };
}

test('manifest is a stock .sflx with no session or fork-only settings', () => {
  assert.equal(manifest.name, 'bitchord-qobuz-worker');
  assert.ok(manifest.type.includes('download_provider'));
  assert.equal(manifest.signedSession, undefined);
  assert.ok(!manifest.requiredRuntimeFeatures);
  const keys = manifest.settings.map((s) => s.key);
  assert.deepEqual(keys, ['workerUrl']);
});

test('search returns stock-addon rows and remembers them for download', () => {
  const { extension, calls } = loadAddon();
  extension.initialize();
  const rows = extension.customSearch('alpha beta', { limit: 10, filter: 'songs' });
  assert.equal(rows.length, 1);
  assert.equal(rows[0].id, '4242');
  assert.equal(rows[0].duration_ms, 210000);
  assert.ok(calls.stored['qbworker:track:4242']);
  assert.ok(calls.worker[0].startsWith('/qobuz/search?q=alpha%20beta'));
});

test('download asks for hi96 first and reports only decoder-measured fields', () => {
  const { extension, calls } = loadAddon();
  extension.initialize();
  extension.customSearch('alpha beta', { limit: 10 });

  const result = extension.download('4242', 'HI_RES_LOSSLESS', '/cache/4242.bin', () => {});
  assert.equal(result.success, true);
  assert.equal(result.file_path, '/cache/4242.flac');
  assert.equal(result.bit_depth, 24);
  assert.equal(result.sample_rate, 96000);
  assert.equal(result.audio_quality, '24bit/96kHz');

  assert.ok(calls.worker.some((p) => p === '/download-url/4242?quality=hi96'));
  assert.equal(calls.downloads[0].url, FAKE_SIGNED_URL);

  const logs = calls.logs.join('\n');
  for (const forbidden of ['https://', 'cdn.test', 'sig=', 'SECRET_SIGNATURE_VALUE']) {
    assert.ok(!logs.includes(forbidden), `addon log leaked ${forbidden}`);
  }
});

test('a failed hi96 fetch falls back to hi24, not to MP3', () => {
  const { extension, calls } = loadAddon({ failFirstDownload: true });
  extension.initialize();
  extension.customSearch('alpha beta', { limit: 10 });

  const result = extension.download('4242', 'HI_RES', '/cache/4242.bin', () => {});
  assert.equal(result.success, true);
  const tiers = calls.worker
    .filter((p) => p.startsWith('/download-url/'))
    .map((p) => new URL('https://worker.test' + p).searchParams.get('quality'));
  assert.deepEqual(tiers, ['hi24', 'flac']);
  assert.ok(!tiers.includes('low'));
});

test('LOSSLESS quality never asks for a hi-res tier', () => {
  const { extension, calls } = loadAddon();
  extension.initialize();
  extension.download('4242', 'LOSSLESS', '/cache/4242.bin', () => {});
  const tiers = calls.worker
    .filter((p) => p.startsWith('/download-url/'))
    .map((p) => new URL('https://worker.test' + p).searchParams.get('quality'));
  assert.deepEqual(tiers, ['flac']);
});
