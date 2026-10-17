import clientHeaders from '../../frontend/client-headers.json' with { type: 'json' };

export async function handle(request, env, originFetch = fetch) {
  const url = new URL(request.url);
  if (url.protocol !== 'https:') {
    url.protocol = 'https:';
    return new Response(null, { status: 308, headers: { Location: url.href } });
  }
  if (url.pathname === '/api' || url.pathname.startsWith('/api/')) {
    if (!env.ORIGIN_URL || !/^[a-f0-9]{64}$/.test(env.ORIGIN_SECRET ?? '')) {
      return new Response(null, { status: 503, headers: { 'Cache-Control': 'no-store' } });
    }
    let origin;
    try { origin = new URL(env.ORIGIN_URL); } catch {
      return new Response(null, { status: 503, headers: { 'Cache-Control': 'no-store' } });
    }
    if (origin.protocol !== 'https:' || origin.host === url.host || origin.pathname !== '/' || origin.search || origin.hash || origin.username || origin.password) {
      return new Response(null, { status: 503, headers: { 'Cache-Control': 'no-store' } });
    }
    url.protocol = origin.protocol;
    url.host = origin.host;
    const headers = new Headers(request.headers);
    // Always overwrite attacker-supplied origin verification and forwarding headers.
    headers.set('X-Origin-Verify', env.ORIGIN_SECRET);
    headers.delete('Forwarded');
    headers.delete('X-Forwarded-For');
    headers.delete('X-Forwarded-Host');
    headers.set('X-Forwarded-Proto', 'https');
    // The visitor's address for per-client quotas, only from Cloudflare's own connecting address.
    headers.delete('X-Otdog-Client-Address');
    const visitor = request.headers.get('CF-Connecting-IP');
    if (visitor) headers.set('X-Otdog-Client-Address', visitor);
    let upstream;
    try {
      upstream = await originFetch(new Request(url, {
        method: request.method, headers,
        body: ['GET', 'HEAD'].includes(request.method) ? undefined : request.body,
        redirect: 'manual', duplex: 'half'
      }), { cf: { cacheTtl: 0, cacheEverything: false } });
    } catch {
      return new Response(null, { status: 502, headers: { 'Cache-Control': 'no-store' } });
    }
    const response = new Response(upstream.body, upstream);
    response.headers.set('Cache-Control', 'no-store');
    response.headers.set('Strict-Transport-Security', clientHeaders['Strict-Transport-Security']);
    // Preserve API/export CSP, cookies and other privacy headers. Never cache via Cache API.
    return response;
  }
  const asset = await env.ASSETS.fetch(request);
  const response = new Response(asset.body, asset);
  for (const [name, value] of Object.entries(clientHeaders)) response.headers.set(name, value);
  return response;
}

export default {
  fetch(request, env) { return handle(request, env); }
};
