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

## Admin access

Admin access is configured by the application and must not be documented with a shared username or password. Set or rotate credentials through the project’s secure administrative process; never commit real credentials to this repository or distribute them in an APK README.

## Repository Architecture

The APK is a native Android shell around a bundled web billing application. `MainActivity.java` and `BackupEnabledActivity.java` provide the Android/WebView host layer, while `app/src/main/assets/www/` contains the business interface and local billing behavior. Gradle and GitHub Actions build the Android package; `release-staging/` stores generated distribution artifacts.

| Area | Responsibility | Change boundary |
| --- | --- | --- |
| `app/src/main/java/com/purplesignature/billing/` | Android lifecycle and WebView host | Platform integration only |
| `app/src/main/assets/www/` | Billing screens, invoice workflows, salon settings, and local data handling | Web application behavior |
| `app/src/main/res/` | Android strings, styles, and launcher resources | Platform resources |
| `app/build.gradle` | Android namespace, SDK levels, version, and signing environment contract | Build configuration |
| `.github/workflows/` | Debug and release automation | CI/CD behavior |
| `release-staging/` | Generated APKs, checksums, instructions, and chunks | Distribution artifacts, not source |

## Local Development and Validation

The repository is built with Gradle and Android SDK 35 using Java 17. The canonical CI command is:

```bash
gradle :app:assembleDebug --no-daemon --stacktrace
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The repository also provides separate workflows for unsigned and signed releases. Release signing is conditional on `PSS_RELEASE_STORE_FILE`, `PSS_RELEASE_STORE_PASSWORD`, `PSS_RELEASE_KEY_ALIAS`, and `PSS_RELEASE_KEY_PASSWORD`; keep those values in protected CI secrets or a secure local environment, never in tracked files.

## Data and Security Boundary

The embedded web app stores billing history locally through IndexedDB and can display or modify salon configuration. Treat invoices, customer details, payment QR assets, and admin settings as sensitive business data. Do not publish real customer information, credentials, private signing material, or production database exports in source control or release artifacts.

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
