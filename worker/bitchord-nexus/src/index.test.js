import test from 'node:test';
import assert from 'node:assert/strict';
import { handle } from './index.js';

const ENV = {
  QOBUZ_PRIMARY_BASE_URL: 'https://primary.test',
  QOBUZ_UPSTREAM_BASE_URL: 'https://fallback.test',
};

let fetchCalls = [];

function signedUrlFor(id) {
  // Fake, obviously non-secret value. The tests assert it never reaches a log.
  return `https://cdn.test/${id}.flac?sig=SECRET_SIGNATURE_VALUE`;
}

globalThis.fetch = async (input, init) => {
  const url = new URL(String(input));
  fetchCalls.push({ url, init });
  if (url.pathname === '/api/download-music') {
    const id = url.searchParams.get('track_id') || '0';
    return new Response(JSON.stringify({
      data: {
        url: signedUrlFor(id),
        bit_depth: 24,
        sample_rate: 96, // kHz; worker must normalise to Hz
      },
    }), { status: 200 });
  }
  if (url.pathname === '/api/get-music') {
    const query = url.searchParams.get('q') || '';
    if (query === 'missing') {
      return new Response(JSON.stringify({ data: { tracks: { items: [] } } }), { status: 200 });
    }
    return new Response(JSON.stringify({
      data: {
        tracks: {
          items: [
            {
              id: 4242,
              title: 'Alpha Beta',
              performer: { name: 'Test Artist' },
              album: { title: 'Test Album', image: { large: 'https://art.test/cover.jpg' } },
              duration: 210,
              isrc: 'TESTISRC0001',
              maximum_bit_depth: 24,
              maximum_sampling_rate: 96,
            },
            { id: 'not-a-number', title: 'Bad row' },
          ],
        },
      },
    }), { status: 200 });
  }
  return new Response('{}', { status: 404 });
};

async function get(path) {
  const lines = [];
  const original = console.log;
  console.log = (...parts) => lines.push(parts.join(' '));
  try {
    const response = await handle(
      new Request(`https://worker.test${path}`, { method: 'GET' }),
      ENV,
    );
    return { response, logs: lines.join('\n') };
  } finally {
    console.log = original;
  }
}

function assertNoSecretsInLogs(logs) {
  for (const forbidden of ['https://', 'cdn.test', 'sig=', 'SECRET_SIGNATURE_VALUE', 'body[0..']) {
    assert.ok(!logs.includes(forbidden), `log leaked ${forbidden}`);
  }
}

test('the download-url route maps addon quality names to lossless Qobuz formats', async () => {
  const cases = [
    ['hi96', 27],
    ['hi24', 7],
    ['flac', 6],
  ];

  for (const [quality, expectedFmt] of cases) {
    fetchCalls = [];
    const id = String(1000 + expectedFmt);
    const { response, logs } = await get(
      `/download-url/${id}?quality=${quality}`,
    );

    assert.equal(response.status, 200, `${quality} should resolve`);
    const body = await response.json();
    assert.equal(body.quality, expectedFmt);
    assert.equal(body.bit_depth, 24);
    assert.equal(body.sampling_rate, 96000);
    assert.equal(body.url, signedUrlFor(id));

    const upstream = fetchCalls.find(
      (c) => c.url.pathname === '/api/download-music',
    );
    assert.ok(upstream, 'upstream Qobuz endpoint should be called');
    assert.equal(upstream.url.searchParams.get('quality'), String(expectedFmt));
    assert.equal(upstream.url.searchParams.get('track_id'), id);

    assertNoSecretsInLogs(logs);
  }
});

test('an invalid track id is refused without an upstream request', async () => {
  fetchCalls = [];
  const { response, logs } = await get('/download-url/not-a-number?quality=flac');
  assert.equal(response.status, 400);
  assert.equal(fetchCalls.length, 0);
  assertNoSecretsInLogs(logs);
});

test('manifest and health advertise the adapter without leaking provider URLs', async () => {
  const manifest = await get('/manifest.json');
  assert.equal(manifest.response.status, 200);
  const doc = await manifest.response.json();
  assert.equal(doc.version, '1.8.0');
  assert.ok(doc.resources.includes('download_url'));
  assert.ok(doc.resources.includes('addon_qobuz'));
  assertNoSecretsInLogs(manifest.logs);
});

test('the standalone addon search route returns stock-addon track fields only', async () => {
  fetchCalls = [];
  const { response, logs } = await get('/qobuz/search?q=alpha%20beta&limit=1');
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.tracks.length, 1);

  const track = body.tracks[0];
  assert.equal(track.id, '4242');
  assert.equal(track.item_type, 'track');
  assert.equal(track.name, 'Alpha Beta');
  assert.equal(track.artists, 'Test Artist');
  assert.equal(track.duration_ms, 210000);
  assert.equal(track.audio_quality, '24bit/96kHz');
  assert.ok(!('url' in track), 'catalog rows must not carry signed URLs');

  const upstream = fetchCalls.find((c) => c.url.pathname === '/api/get-music');
  assert.ok(upstream, 'Qobuz catalogue endpoint should be called');
  assertNoSecretsInLogs(logs);
  assert.ok(!logs.includes('alpha'), 'search text must not be logged');
});

test('the standalone addon track fallback uses the same mapper', async () => {
  fetchCalls = [];
  const { response, logs } = await get('/qobuz/track/4242');
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.track.id, '4242');
  assert.equal(body.track.name, 'Alpha Beta');
  assertNoSecretsInLogs(logs);
});

test('an invalid addon track id is refused without an upstream request', async () => {
  fetchCalls = [];
  const { response, logs } = await get('/qobuz/track/bad-id');
  assert.equal(response.status, 400);
  assert.equal(fetchCalls.length, 0);
  assertNoSecretsInLogs(logs);
});
