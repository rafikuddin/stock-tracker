# Stock Tracker
- app/      Android app (Kotlin + Compose). Build: Actions > "Build APK".
- backend/  Reference copies of the Google Apps Script (Code.gs, Index.html) and Master_SKU.csv. Apps Script itself is deployed from script.google.com.
- worker/   Optional Cloudflare Worker (serves the web viewer + /api proxy).

## Auto-update
Upload `update.zip` to the repo root. The "Apply update.zip" workflow unpacks it, commits the changes,
deletes the zip and starts the APK build.
