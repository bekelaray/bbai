# Architecture

## Scope

This repository now contains an Android-first, read-only prototype under `/home/runner/work/bbai/bbai/android-app`.

The implementation is intentionally limited to lawful identification, browsing, metadata inspection, media preview, and raw export. It does not bundle keys, bypass DRM, fetch network content, or execute extracted binaries.

## Module layout

```text
android-app/
├── app/
│   ├── MainActivity + Compose UI screens
│   ├── SAF integration and persisted URI permissions
│   ├── export progress / cancellation / logging state
│   └── media preview orchestration
└── nxreader-core/
    ├── random-access reader abstractions
    ├── file-kind detection
    ├── PFS0 / HFS0 parsing
    ├── CNMT / NACP / NPDM basic metadata parsing
    └── NRO / NSO / KIP header recognition placeholders
```

## Independent rewrite strategy

The code in this repository is an independent rewrite. It uses public format documentation and high-level behavioral references, but does not copy closed-source binaries or embed third-party extraction executables.

The first implementation pass favors the most stable and maintainable approach over minimal APK size:

- pure Kotlin/JVM parsing core
- Android Storage Access Framework instead of direct filesystem paths
- streaming export and cache materialization instead of full-file memory loading
- explicit unsupported / encrypted states instead of speculative parsing

## Supported behaviors in this revision

- Detect container and media types from extension and magic bytes
- Read actual entry tables for clear-text PFS0/NSP and HFS0 containers
- Inspect the root HFS0 inside XCI when discoverable
- Display metadata, tree structure, offsets, sizes, and support status
- Preview common Android-supported media after safe cache materialization
- Export raw files with progress, cancellation, and error logging

## Deferred behaviors

- RomFS / ExeFS filesystem walking
- lawful key-provider abstraction wired into NCA parsing
- Switch-specific texture/audio/video decoding
- persistent background workers beyond in-process coroutines
- format conformance verification against sample corpora

## Public references consulted

The implementation direction was informed by public documentation and permissively referenceable projects, including:

- SwitchBrew format pages for XCI, CNMT, NACP, NPDM, NSO0, and KIP1
- Kinnay Nintendo File Formats documentation
- public project documentation for hactool, nstool, NXTools, nx-archive, and related format viewers

These references informed structure and terminology only; repository code was rewritten independently for this Android prototype.
