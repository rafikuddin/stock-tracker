// ============ Stock Tracker - Cloudflare Worker (single file, API only) ============
// 1) Paste your Google Apps Script Web App URL (ends with /exec) below.
const APPS_SCRIPT_URL = 'https://script.google.com/macros/s/AKfycbzWCwhJZMHIj9iQqvvh-uI-8Mr3a53yAtbPKCJy4dPLnCtJ0XYILsXtfc7oPfp9CSLd/exec';
// 2) That's all. The web page is on GitHub Pages; this Worker only forwards POST /api (the Worker link shows nothing).

// Actions the public /api endpoint is allowed to forward to Apps Script.
const ALLOWED = new Set(['login', 'master', 'submit_stock', 'submit_sales', 'dashboard', 'webdata', 'admin']);
const MAX_BODY = 200 * 1024; // 200 KB

const SECURITY = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Strict-Transport-Security': 'max-age=31536000',
};

// The page is hosted on GitHub Pages (another origin). Auth is by login token, no cookies, so '*' is safe.
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Access-Control-Max-Age': '86400',
};

function notFound() {
  return new Response('Not found', {
    status: 404,
    headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', ...SECURITY },
  });
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...SECURITY, ...CORS },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== '/api') return notFound();

    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    if (request.method !== 'POST') return json({ ok: false, error: 'Method not allowed' }, 405);
    const target = (env && env.APPS_SCRIPT_URL) || APPS_SCRIPT_URL;
    if (!target || target.indexOf('http') !== 0) return json({ ok: false, error: 'APPS_SCRIPT_URL is not set in the Worker code' }, 500);

    const raw = await request.text();
    if (raw.length > MAX_BODY) return json({ ok: false, error: 'Request too large' }, 413);
    let body;
    try { body = JSON.parse(raw); } catch (e) { return json({ ok: false, error: 'Bad JSON' }, 400); }
    if (!body || !ALLOWED.has(body.action)) return json({ ok: false, error: 'Unknown action' }, 400);

    try {
      // Apps Script answers a POST with a 302 to a result URL; fetch follows it.
      const res = await fetch(target, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain;charset=utf-8' },
        body: raw,
        redirect: 'follow',
      });
      const text = await res.text();
      try { JSON.parse(text); } catch (e) { return json({ ok: false, error: 'Apps Script returned a web page instead of data. Re-deploy the script (Anyone access, new version).' }, 502); }
      return new Response(text, { headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...SECURITY, ...CORS } });
    } catch (e) {
      return json({ ok: false, error: 'Cannot reach Apps Script' }, 502);
    }
  },
};
