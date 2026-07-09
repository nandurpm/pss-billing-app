# Purple Signature Salon Billing Android App

This repository now contains a buildable Android billing app project.

## What is inside

- Native Android WebView app
- Offline billing screen stored at `app/src/main/assets/www/index.html`
- Local bill saving using phone browser storage inside WebView
- Print / PDF option
- Share bill text option
- GitHub Actions workflow to build a debug APK

## APK build

The APK build workflow is available here:

`.github/workflows/build-apk.yml`

It builds:

`app/build/outputs/apk/debug/app-debug.apk`

and uploads it as this artifact:

`pss-billing-debug-apk`

## How to download APK

1. Open this repository on GitHub.
2. Go to **Actions**.
3. Open **Build Android APK**.
4. If no run is visible, press **Run workflow**.
5. Open the green completed run.
6. Download artifact: `pss-billing-debug-apk`.
7. Extract the zip.
8. Install `app-debug.apk` on Android.

## Note

This is a debug APK, so Android may ask you to allow **Install unknown apps** before installing.
