# Billing

## Purpose

Small Android host layer for the billing WebView.

## Contents

`MainActivity` and `BackupEnabledActivity` configure the Android shell around the bundled web application.

## Responsibilities

Keep platform permissions, lifecycle, file access, and WebView behavior here; keep billing domain behavior in the web assets.

## Important Notes

Do not place customer records or credentials in Java source.
