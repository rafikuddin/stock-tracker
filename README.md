# Stock Tracker
- app/      Android app (Kotlin + Compose). Build: Actions > "Build APK".
- backend/  Reference copies of the Google Apps Script (Code.gs, Index.html) and Master_SKU.csv.
- worker/   Cloudflare Worker (web viewer + /api proxy). worker-single-file.js = paste-into-dashboard version.
- docs/     GitHub Pages copy of the web viewer (index.html). Works if the repo is public (Settings > Pages > main /docs).

## Auto-update
Upload `update.zip` to the repo root. The "Apply update.zip" workflow unpacks it, commits the changes,
deletes the zip and starts the APK build.
