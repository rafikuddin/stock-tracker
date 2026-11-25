# Stock Tracker
- app/      Android app (Kotlin + Compose). Build: Actions > "Build APK".
- backend/  Reference copies of the Google Apps Script (Code.gs, Index.html) and Master_SKU.csv.
- worker/   Cloudflare Worker (API proxy only - the page itself is on GitHub Pages / Apps Script). worker-single-file.js = paste-into-dashboard version.
- docs/     GitHub Pages copy of the web viewer (index.html). Works if the repo is public (Settings > Pages > main /docs).

## Auto-update
Upload `update.zip` to the repo root. The "Apply update.zip" workflow unpacks it, commits the changes,
deletes the zip and starts the APK build.

## Roles (web page)
Everyone who can log in can view and download on the web page. Only an **admin** sees *Add* and *Edit* there
(the Apps Script checks the role again on every save, so hiding buttons is not the only protection).
- Make an admin: Sheet menu **Stock App > Set user role (admin / user)**, or type `admin` in column E (Role) of the Users tab.
- Edits are marked in the Sheet (EditedBy / EditedAt columns) and the original submitter stays unchanged.
- The Android app is unchanged for admins: every user submits stock and sales from the phone.

## Look & feel
Web page and Android app share one design (rounded pill navigation, BU cards, tiles, hatched charts). The web page also has a light/dark switch (left rail or the account menu); the app is light only.
