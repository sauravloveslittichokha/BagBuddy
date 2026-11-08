# Bag Buddy

Bag Buddy is an Android app and virtual simulator for a future music-and-pixel-pet bag charm. The simulator works offline with fictional sample songs. Phase 3 adds optional Android media-session metadata detection when the user grants notification-listener access.

## Current features

- AMOLED-inspired Jetpack Compose simulator and animated pixel pet.
- Local mock playback with play/pause, skip, seek, and sample lyrics.
- Adaptive lyric detail based only on timestamps actually present in the source data.
- Multi-provider capability declarations with per-result timing validation and quality-aware selection. LRCLIB Lyricsfile records can provide genuine word timing; LiriQo response parsing is prepared, but its network provider remains disabled pending rights and attribution clarification.
- Lyrics lookup states, strict track matching, persistent successful-result caching, and retry UI.
- LRCLIB lyrics lookup with exact metadata matching, Lyricsfile word timing when complete source intervals are present, LINE/PLAIN fallback, and timeout/rate-limit handling. LiriQo remains unconfigured.
- Optional live media-session title, artist, album, artwork, playback status, and position when Android exposes them.
- Mock playback remains active as a separate fallback when access is denied or no media session is active.

## Open and run

1. Open the `BagBuddy` folder in Android Studio and let Gradle sync finish.
2. Select an Android emulator or connected Android device.
3. Choose the `app` run configuration and press **Run**.

The project uses the Android Studio-generated Gradle wrapper. No network connection is needed while using the simulator after the project and dependencies are available locally.

## Run checks

From this folder, run:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Test Android media sessions

1. Install and launch Bag Buddy on an Android phone.
2. Tap **Open notification access settings** and enable Bag Buddy's notification listener.
3. Return to Bag Buddy; it rechecks the special access when the activity resumes.
4. Start music in another app. If Android exposes an active session, the simulator switches to **LIVE MEDIA SESSION** and displays only fields supplied by that session.
5. Pause, resume, change tracks, or stop playback to observe available state updates.
6. If access is off or there is no active session, the app clearly indicates **MOCK PLAYER** and keeps the offline simulator controls working.

The app reads media-session metadata only. It does not get Apple Music's native synchronized lyrics through this API, and Apple Music behavior has not been verified on a real device. See [docs/ANDROID_MEDIA_SESSION.md](docs/ANDROID_MEDIA_SESSION.md) for details.

## Project areas

- `app/src/main/java/com/saurav/bagbuddy/data`: immutable song, lyric, and playback models.
- `app/src/main/java/com/saurav/bagbuddy/lyrics`: parsing, provider fallback, sync-level detection, and playback-position selection.
- `app/src/main/java/com/saurav/bagbuddy/mock`: offline mock player and sample lyrics.
- `app/src/main/java/com/saurav/bagbuddy/simulator`: Compose simulator UI.
- `app/src/test`: local JVM tests for playback and lyric behavior.

See [docs/LYRICS_ENGINE.md](docs/LYRICS_ENGINE.md) for timing rules, verified provider details, lookup behavior, and known limitations.

## Not included yet

LRCLIB network requests are implemented against its documented API. Physical-device testing confirms LRCLIB lyrics load and LINE synchronization works; the new Lyricsfile WORD parser is covered by synthetic fixtures, but a live word-synced LRCLIB record has not yet been confirmed on-device. LRCLIB does not document a separate lyric-text redistribution license. LiriQo's current API documentation and word-timing response format are available, but its network adapter remains disabled until lyrics usage rights and required attribution are clarified. BLE and ESP32 firmware are future work.
