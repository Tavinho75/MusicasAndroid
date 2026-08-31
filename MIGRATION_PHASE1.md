# ETAPA 3 — FASE 1

## Snapshot
A versão funcional anterior foi preservada sem alterações no branch:

`snapshot/pre-android-migration-2026-08-31`

A implementação Android desta fase está isolada em:

`migration/android-phase-1`

Nenhum arquivo legado foi removido.

## Escopo
- base Capacitor preparada;
- Android com minSdk 29;
- ABI prioritária arm64-v8a;
- abstração ExtractorEngine;
- smoke test isolado do yt-dlp-android;
- abstração MediaProcessor.

FFmpeg ainda não é integrado nesta cópia da Fase 1.
