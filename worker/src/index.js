import html from './index.html';

// Actions the public /api endpoint is allowed to forward to Apps Script.
const ALLOWED = new Set(['login', 'master', 'submit_stock', 'submit_sales', 'dashboard', 'webdata']);
const MAX_BODY = 200 * 1024; // 200 KB

const SECURITY = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Strict-Transport-Security': 'max-age=31536000',
  'Content-Security-Policy':
    "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
    "font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'",
};

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...SECURITY },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // ---- web page
    if (url.pathname === '/' || url.pathname === '/index.html') {
      if (request.method !== 'GET' && request.method !== 'HEAD') return json({ ok: false, error: 'Method not allowed' }, 405);
      return new Response(html, {
        headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache', ...SECURITY },
      });
    }

    // ---- API proxy -> Apps Script
    if (url.pathname === '/api') {
      if (request.method !== 'POST') return json({ ok: false, error: 'Method not allowed' }, 405);
      if (!env.APPS_SCRIPT_URL) return json({ ok: false, error: 'APPS_SCRIPT_URL is not set on the Worker' }, 500);

      const raw = await request.text();
      if (raw.length > MAX_BODY) return json({ ok: false, error: 'Request too large' }, 413);
      let body;
      try { body = JSON.parse(raw); } catch (e) { return json({ ok: false, error: 'Bad JSON' }, 400); }
      if (!body || !ALLOWED.has(body.action)) return json({ ok: false, error: 'Unknown action' }, 400);

      try {
        // Apps Script answers a POST with a 302 to a result URL; fetch follows it.
        const res = await fetch(env.APPS_SCRIPT_URL, {
          method: 'POST',
          headers: { 'Content-Type': 'text/plain;charset=utf-8' },
          body: raw,
          redirect: 'follow',
        });
        const text = await res.text();
        try { JSON.parse(text); } catch (e) { return json({ ok: false, error: 'Apps Script returned a web page instead of data. Re-deploy the script (Anyone access, new version).' }, 502); }
        return new Response(text, { headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...SECURITY } });
      } catch (e) {
        return json({ ok: false, error: 'Cannot reach Apps Script' }, 502);
      }
    }

    return json({ ok: false, error: 'Not found' }, 404);
  },
};
