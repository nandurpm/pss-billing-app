# Main

## Purpose

Android runtime source and bundled application assets.

## Contents

Manifest, Java activities, Android resources, and the WebView-hosted billing application.

## Responsibilities

Keep platform integration separate from the embedded web application.

## Important Notes

Changes here affect the installed APK and should be validated through the Android build workflow.

## Resource Documentation

Android resource definitions live under `res/` and must use Android-supported resource filenames and extensions. Documentation for drawable and values resources belongs outside the `res/` tree because the Android resource merger treats files inside resource directories as resource inputs.

### Drawable Resources

Keep vector or XML drawable resources used by the Android shell under `res/drawable/`. Large web artwork belongs under the embedded web assets.

### Values Resources

Keep application strings, themes, and style values under `res/values/`. Business data and billing configuration belong in the application layer rather than Android resource XML.
