# TandemSourceRT Overview

TandemSourceRT is an Android application that authenticates with Tandem Source, downloads BFF JSON data, adapts it locally in Kotlin, and visualizes the resulting dataset on-device.

## Goals

- No custom backend server
- Native Kotlin JSON adaptation
- Android-first architecture
- Current-pump selection
- BFF JSON as source of truth

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
Dataset / Offline Settings Cache / UI
