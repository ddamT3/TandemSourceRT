# TandemSourceRT Overview

TandemSourceRT is an Android application that authenticates with Tandem Source, downloads BFF JSON data, adapts it locally in Kotlin, and visualizes the resulting dataset on-device.

## Goals

- No custom backend server
- Native Kotlin JSON adaptation
- Android-first architecture
- Current-pump selection and historical browsing
- BFF JSON as source of truth
- Offline access to the latest current dataset and device settings

## High-Level Pipeline

Android App
    ↓
Kotlin OAuth/PKCE Client
    ↓
Tandem Source APIs
    ↓
Pump Events / Pump Settings JSON
    ↓
Kotlin Repository and Adapters
    ↓
Normalized Dataset / Latest-Data Caches / UI

The app stores the latest current chart dataset, pump settings, and Sensor Set
snapshot in Android private storage. Historical
requests are displayed temporarily and never replace the current cache.
