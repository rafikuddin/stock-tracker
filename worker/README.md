# Stock Tracker - Cloudflare Worker
Serves the web viewer at your own URL and proxies /api to your Google Apps Script.

Deploy (CLI):   npm install && npx wrangler login && npx wrangler deploy
Deploy (GitHub): Cloudflare dashboard > Workers & Pages > Create > Import a repository,
                 root directory = worker, deploy command = npx wrangler deploy
