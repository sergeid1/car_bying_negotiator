import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker, { handle } from './worker.mjs';
import headers from '../../frontend/client-headers.json' with { type: 'json' };

const env = { ORIGIN_URL: 'https://origin.invalid', ORIGIN_SECRET: 'a'.repeat(64) };
test('Cloudflare execution context is not mistaken for the upstream fetch function', async () => {
  const previous = globalThis.fetch;
  globalThis.fetch = async () => new Response('origin');
  try {
    const response = await worker.fetch(new Request('https://otdog.com/api/test'), env, { waitUntil() {} });
    assert.equal(response.status, 200);
    assert.equal(await response.text(), 'origin');
  } finally { globalThis.fetch = previous; }
});
test('API streams uploads, replaces origin credential, preserves cookies and export CSP, disables cache', async () => {
  const response = await handle(new Request('https://otdog.com/api/v1/documents?q=1', {
    method: 'POST', body: 'document', headers: { 'X-Origin-Verify': 'forged', 'X-Forwarded-For': 'forged', Cookie: 'account_session=test' }
  }), env, async (request, options) => {
    assert.equal(request.url, 'https://origin.invalid/api/v1/documents?q=1');
    assert.equal(request.headers.get('X-Origin-Verify'), env.ORIGIN_SECRET);
    assert.equal(request.headers.get('X-Forwarded-For'), null);
    assert.equal(request.headers.get('Cookie'), 'account_session=test');
    assert.equal(request.redirect, 'manual');
    assert.equal(await request.text(), 'document');
    assert.equal(options.cf.cacheEverything, false);
    return new Response('export', { headers: { 'Content-Security-Policy': 'sandbox', 'Set-Cookie': 'account_session=new; Path=/api/v1; Secure; SameSite=Strict' } });
  });
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
  assert.equal(response.headers.get('Content-Security-Policy'), 'sandbox');
  assert.match(response.headers.get('Set-Cookie'), /SameSite=Strict/);
});
test('static and SPA responses receive the canonical client headers', async () => {
  for (const path of ['/', '/assets/main.js', '/saved-case']) {
    const response = await handle(new Request(`https://otdog.com${path}`), {
      ASSETS: { fetch: async () => new Response('asset') }
    });
    for (const [name, value] of Object.entries(headers)) assert.equal(response.headers.get(name), value);
  }
});
test('HTTP redirects before processing credentials; bad origin config and outages fail closed', async () => {
  assert.equal((await handle(new Request('http://otdog.com/api/x'), env)).status, 308);
  for (const config of [{}, { ...env, ORIGIN_URL: 'http://origin.invalid' }, { ...env, ORIGIN_URL: 'invalid' }, { ...env, ORIGIN_URL: 'https://otdog.com' }, { ...env, ORIGIN_SECRET: 'weak' }]) {
    assert.equal((await handle(new Request('https://otdog.com/api/x'), config)).status, 503);
  }
  const response = await handle(new Request('https://otdog.com/api/x'), env, async () => { throw Error('private'); });
  assert.equal(response.status, 502);
  assert.equal(await response.text(), '');
});
test('visitor address comes only from Cloudflare connecting address, replacing any client copy', async () => {
  const seen = [];
  const forward = async (request) => { seen.push(request.headers.get('X-Otdog-Client-Address')); return new Response(null); };
  await handle(new Request('https://otdog.com/api/v1/toto/answers', {
    method: 'POST', body: '{}', headers: { 'CF-Connecting-IP': '2001:db8::1', 'X-Otdog-Client-Address': '192.0.2.9', 'X-Forwarded-For': '192.0.2.8' }
  }), env, forward);
  await handle(new Request('https://otdog.com/api/v1/toto', { headers: { 'CF-Connecting-IP': '198.51.100.7' } }), env, forward);
  // Without Cloudflare's address the forged copy is still removed, so the origin keys on its peer.
  await handle(new Request('https://otdog.com/api/v1/toto', { headers: { 'X-Otdog-Client-Address': '192.0.2.9' } }), env, forward);
  assert.deepEqual(seen, ['2001:db8::1', '198.51.100.7', null]);
});
