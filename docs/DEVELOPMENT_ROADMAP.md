# Development roadmap

## Phase 1 — Android skeleton and simulator

Complete: Android Studio Compose project, dark simulator UI, animated pixel pet, fictional offline sample tracks, and mock playback controls.

## Phase 2 — Adaptive lyrics engine

Complete: normalized timing models, sync-level detection, LRC line timestamp parsing, provider abstraction/fallback, playback-position lyric frames, and unit tests. No provider network calls are configured.

## Phase 3 — Android media-session detection

Implemented: notification-listener special-access experience, active `MediaSessionManager` observation, metadata and playback-state mapping, progress projection while playing, lifecycle callback registration, and mock fallback.

Not yet device-verified: actual metadata availability from Apple Music or other music apps. Perform the manual steps in [ANDROID_MEDIA_SESSION.md](ANDROID_MEDIA_SESSION.md).

## Phase 4 — Lyrics provider integration

Partially implemented: provider-independent result states, exact title/artist matching with album/duration validation, persistent successful-result cache, offline cache lookup, sample fallback, loading/error/retry UI, and fake-provider tests. Real network requests are intentionally disabled.

LRCLIB's official API documentation and a documented `/api/get` example were verified, including query fields, response schema, approximate rate limits, and `Retry-After` guidance. Its documentation requires an identified `User-Agent` with a project URL or email. Bag Buddy is not published and no contact email was provided, so LRCLIB remains unconfigured. Before enabling it, add a truthful project URL or project contact email and implement/test the HTTP adapter against the verified API contract.

LiriQo's repository README documents a public API, but the documented API host did not resolve during verification, so its adapter remains unconfigured. No real provider response or network fallback is claimed. See [LYRICS_ENGINE.md](LYRICS_ENGINE.md) for research sources, limitations, and setup.

## Phase 5 — BLE communication architecture

Design and test the versioned transport protocol and Android BLE abstraction without requiring hardware for the simulator.

## Phase 6 — ESP32 hardware

Implement and validate firmware and physical-device communication only after the hardware and protocol are ready.
