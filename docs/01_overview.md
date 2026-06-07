# TandemSourceRT Overview

TandemSourceRT is an Android application that authenticates with Tandem Source, downloads pump event data, decodes Tandem event blobs locally, and visualizes the resulting dataset on-device.

## Goals

- No custom backend server
- Local blob decoding
- Android-first architecture
- Multi-pump support
- Event blob as source of truth

## High-Level Pipeline

Android App
    ↓
Embedded Python (Chaquopy)
    ↓
Tandem APIs
    ↓
Pump Event Blob
    ↓
Local Decoder
    ↓
Dataset
    ↓
UI
