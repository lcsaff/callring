# Call recording and AI implementation plan

> **For agentic workers:** Use subagent-driven-development for isolated protocol implementation and review; execute integration in this session. Track the steps below.

**Goal:** Record/import calls, transcribe through OpenAI-compatible APIs, analyze through OpenAI/Anthropic-compatible APIs and retain per-call archives.

**Architecture:** A separate RecordingApi contract and JVM workflow own configuration and archive task semantics. Android adapters provide encrypted settings, durable storage, network calls, system recording access and capture. Compose consumes only the public API. Preserve the existing uncommitted settings/calendar changes in this checkout; no reset, stash or copying a stale HEAD into a new worktree.

**Tech Stack:** Kotlin, coroutines, Android SQLite/Keystore/JobScheduler/MediaRecorder, HttpURLConnection, Compose Material 3, JUnit.

## 1. Public API and workflow

Files: `backend/src/main/kotlin/com/greatwall/callring/application/api/RecordingApi.kt`, `backend/src/main/kotlin/com/greatwall/callring/application/RecordingWorkflow.kt`, `backend/src/test/kotlin/com/greatwall/callring/RecordingServiceTest.kt`.

- [x] Write failing tests for invalid endpoint configuration, disabled upload, independent transcription settings, completed transcript reuse, concurrent retry exclusion, and deletion invalidating late responses. Run `.\gradlew.bat :backend:test --console=plain` and observe missing API failures.
- [x] Define `AiProtocol`, `AiConnection`, `RecordingSettings`, `CallArchive`, `CallAnalysis`, `ArchiveStatus`, `RecordingApi`; expose it through a default unavailable `CallringUiApi.recordings` property so existing fixtures remain compatible.
- [x] Implement workflow with durable generation tokens and repository compare-and-set updates. Retrying a completed transcription must not retranscribe it; changing input invalidates previous analysis. Handle transient failure with persisted attempt/next-run timestamps and a bounded retry count.
- [x] Run backend tests and contract boundary script. Keep the existing monolithic contract mirrored in docs/contracts.

## 2. Protocol adapter

Files: `app/src/main/kotlin/com/greatwall/callring/platform/ai/CompatibleAiClient.kt`, corresponding app unit tests.

- [x] Test actual request serialization and responses against a local HTTP server: custom URL prefix, Bearer versus x-api-key, Anthropic top-level system, multipart transcription, text block parsing, malformed JSON, HTTP errors, limits and redirect refusal.
- [x] Implement only OpenAI Chat Completions, Anthropic Messages and OpenAI audio/transcriptions. Keep model names user supplied. Validate analysis JSON and bound network output. Never expose credentials or response bodies in errors.
- [x] Run `.\gradlew.bat :app:testDebugUnitTest --tests '*CompatibleAiClientTest*' --console=plain` through red/green, then have a separate agent review requirements and quality.

## 3. Android storage and background execution

Files: `app/src/main/kotlin/com/greatwall/callring/platform/ai/AndroidRecordingStore.kt`, `app/src/main/kotlin/com/greatwall/callring/RecordingJobService.kt`, `app/.../bootstrap/BackendGraph.kt`.

- [x] Store key fields encrypted with AES/GCM under Android Keystore; separate settings namespace using configured database name to preserve QA isolation.
- [x] Persist archive payloads and compare-and-set generations in a dedicated SQLite database derived from configured database name. Atomic file import into application private storage, size/duration validation, no URI-derived file names. Delete only application-owned copies.
- [x] Schedule network jobs for pending work, bounded exponential backoff, reschedule on process/boot recovery. Use configured snapshots per attempt; job cancellation must not write success over deleted input.
- [x] Compose API by delegating existing backend to a graph wrapper that overrides only recordings. No network or storage opens on the Telecom deadline path.

## 4. Recording sources

Files: `app/.../platform/ai/SystemRecordingSource.kt`, `app/.../CallCaptureService.kt`, manifest and phone-state integration.

- [x] Add system document-tree authorization and scan authorized recordings after calls, with manual import/association always available. Auto-association requires a unique verified match; otherwise require explicit selection.
- [x] Probe VOICE_CALL only when capture privilege exists. Offer an explicitly labelled experimental microphone path with foreground notification and user-granted microphone permission; record actual failures and mark unverified/one-sided audio. Never silently claim dual-channel success.
- [x] Start capture only for a user-enabled supported path; finish on IDLE, release recorder on exceptions, and preserve missing-recording status. Validate real SIM sources only when the device is online. Document unsupported cases rather than pretending capture passed.

## 5. UI and documentation

Files: `ui/.../feature/recording/RecordingScreens.kt`, `RecordingViewModels.kt`, settings route/overview, report screen and app navigation, README.

- [x] Add independent transcription and analysis configuration forms, hidden API keys, protocol choice, free model input, copy connection, save, fixed-payload connection tests and an upload explanation. Keep credentials out of SavedStateHandle and rememberSaveable.
- [x] Add per-call archive route from report cards with import, source selection, play/stop, task status, transcript, identity evidence, summary, actions, retry and confirmed deletion. Require explicit user confirmation before adopting suggested profile fields.
- [x] Test configuration persistence, disabled-upload behavior, view-model save failure retention and route navigation; run all backend/app/UI tests, lint, app build and `python tools/check_backend_boundaries.py`.
- [x] Update README with new network behavior, endpoint paths and validation limits. Record actual check results and unresolved real-device checks. Do not mark the entire recording requirement complete until both source paths have passed real-device validation.


## Verification record - 2026-10-18

Implemented and verified locally in the existing working tree. Source changes are retained with the user's earlier uncommitted work; no mixed source commit or branch operation was performed.

- [x] Full command: `.\gradlew.bat :backend:test :app:testDebugUnitTest :ui:testDebugUnitTest :app:assembleDebug :app:lintDebug :ui:lintDebug :ui:assembleDebugAndroidTest --console=plain` - BUILD SUCCESSFUL, 50 seconds.
- [x] Backend 87, app 97, UI 173 tests: total **357**, zero failures/errors/skips. Includes protocol20, workflow8, connection validation3, association3, encrypted storage2, WAV chunking2, recording UI7.
- [x] `python tools/check_backend_boundaries.py` and `git diff --check` passed. Build logs: `app/build/recording-final-verification.log`.
- [x] Independent review findings fixed: strict JSON, stable archive key after system-log reconciliation, active incoming-session gating, persisted deletion tombstones, cancellation/disconnect of ongoing uploads, OS cancellation recovery and navigation playback cleanup. Reviewer found no remaining definite important issues in reviewed scope.
- [x] Speech connection-test asset is a fixed spoken sentence, not a real recording. API settings remain user supplied; no real paid requests were made.
- [ ] Target device validation: ADB 192.168.10.149:5555 remained offline. Verify document-tree access, actual system recording filenames, real SIM listening/speaker/Bluetooth audio, foreground restrictions/lock screen and process recovery when available.
- [ ] Validate actual user-configured transcription and analysis providers using both connection tests and a real call.

The initial implementation required statistics-enabled call rows. This dependency is removed by the subsequent [recording/statistics decoupling implementation](2026-10-18-recording-statistics-decoupling.md), which provides independent event identities and archive navigation. The fallback remains experimental microphone capture, not a verified universal two-way recording implementation. Final product acceptance remains open until both recording-source paths pass real-device checks. Oversized audio is decoded into bounded WAV chunks; decoded silence is rejected before cloud upload. Automatic matching recognizes only an explicit number and supported filename timestamp; ambiguous/unrecognized names require manual association.
