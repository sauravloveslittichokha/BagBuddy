# Development roadmap

## Phase 1 — Android skeleton and simulator

Complete: Android Studio Compose project, dark simulator UI, animated pixel pet, fictional offline sample tracks, and mock playback controls.

## Phase 2 — Adaptive lyrics engine

Complete: normalized timing models, sync-level detection, LRC line timestamp parsing, provider abstraction/fallback, playback-position lyric frames, and unit tests. No provider network calls are configured.

## Phase 3 — Android media-session detection

Implemented: notification-listener special-access experience, active `MediaSessionManager` observation, metadata and playback-state mapping, progress projection while playing, lifecycle callback registration, and mock fallback.

Not yet device-verified: actual metadata availability from Apple Music or other music apps. Perform the manual steps in [ANDROID_MEDIA_SESSION.md](ANDROID_MEDIA_SESSION.md).

## Phase 4 — Lyrics provider integration

Implemented: LRCLIB HTTP adapter using its documented endpoint, explicit lookup states, exact title/artist matching with album/duration validation, persistent successful-result cache, offline cache lookup, sample fallback, loading/error/retry UI, and fake-transport tests. Live LRCLIB behavior has not yet been verified on a device or with an end-to-end network test.

LRCLIB's official API documentation and a documented `/api/get` example were verified, including query fields, response schema, approximate rate limits, and `Retry-After` guidance. The adapter sends the provided public project URL in its identified `User-Agent`, uses timeouts, handles HTTP status and `Retry-After`, and maps source line-synced or plain lyrics only. LRCLIB `lyricsfile` word timing is not parsed.

LiriQo's repository README documents a public API, but the documented API host did not resolve during verification, so its adapter remains unconfigured. The unit suite uses fake transport; no live provider response is claimed. See [LYRICS_ENGINE.md](LYRICS_ENGINE.md) for research sources and limitations.

## Phase 5 — BLE communication architecture

Design and test the versioned transport protocol and Android BLE abstraction without requiring hardware for the simulator.

## Phase 6 — ESP32 hardware

Implement and validate firmware and physical-device communication only after the hardware and protocol are ready.
