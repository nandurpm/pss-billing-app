# Purple Signature Salon Billing Android App

This repository contains the Android billing app for Purple Signature Salon.

## Current fixes added

- Camera / notch overlap fixed by removing fullscreen WebView layout flags.
- Business-only UI. No prompt text such as "offline ready" or "mobile APK" is shown inside the app.
- Saved bills now use IndexedDB instead of simple localStorage, so large bill history is safer.
- Saved bills search added by customer name, mobile number, invoice number, and date.
- After saving a bill, the app asks whether to open a new bill.
- Admin login added.
- Admin can edit service rates.
- Admin can update salon details.
- Admin can upload a payment QR image for invoice display.
- Purple Signature Salon logo asset added.
- Android launcher icon added.

## Admin login

Username: `ps`

Password: `123`

## APK build

The APK build workflow is:

`.github/workflows/build-apk.yml`

It uploads the APK artifact as:

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
