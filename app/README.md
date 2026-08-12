# App

## Purpose

Android application module for Purple Signature Salon Billing.

## Contents

The Gradle module, Android manifest, Java bridge activity, resources, and embedded web billing interface.

## Responsibilities

Keep Android lifecycle and WebView integration in the Java package; keep business UI and billing behavior in `src/main/assets/www/`.

## Important Notes

Build with the repository workflows or `gradle :app:assembleDebug`; signing credentials must come from environment variables.
