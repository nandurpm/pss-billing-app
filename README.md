# Purple Signature Salon Billing Android App

This repository is prepared for building the Android APK from `pss-android-project.zip`.

## What I already added

- `.github/workflows/unpack-project.yml`
- This workflow automatically unzips `pss-android-project.zip`, moves the project files into the repository root, fixes the APK build workflow to use JDK 21, commits the extracted project, and pushes it back to `main`.

## What you need to do now from phone

1. Open this repository on GitHub:
   `nandurpm/pss-billing-app`
2. Tap **Add file** → **Upload files**.
3. Upload the file named exactly:
   `pss-android-project.zip`
4. Tap **Commit changes**.
5. Go to **Actions**.
6. Open **Unpack Uploaded Android Project**.
7. It should run automatically after the ZIP upload. If not, open it and tap **Run workflow**.
8. After it completes, another workflow named **Build Android APK** will start.
9. Open **Build Android APK** after it turns green.
10. Download artifact:
    `pss-billing-debug-apk`
11. Extract the downloaded zip and install:
    `app-debug.apk`

## Important

The ZIP must contain the folder `pss-app/` inside it. Your uploaded ZIP already has that structure.

The APK is a debug APK. Android may ask you to allow **Install unknown apps** before installing.
