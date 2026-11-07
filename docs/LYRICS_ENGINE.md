# Adaptive lyrics engine

The adaptive timing engine remains offline-first and does not invent timing data. Phase 4 adds provider-independent lookup, track matching, cache, and simulator states. The app currently sends no lyrics network requests: both live provider adapters remain explicitly unconfigured.

## Data flow

1. `LyricsRepository` requires a usable title and artist; placeholder media-session metadata is treated as missing.
2. The repository checks the persistent cache first, then tries `LyricsProvider` implementations in order.
3. `LyricsTrackMatcher` requires normalized title and artist equality, rejects clearly incompatible durations, and uses album agreement as an additional matching signal. Rejected results are not displayed or cached.
4. The repository normalizes accepted lyrics and caches successful matches only. Its explicit results are `Found`, `NotFound`, `InvalidMatch`, `ProviderError`, and `MissingMetadata`.
5. The Compose simulator displays provider lyrics only for a verified match; otherwise it preserves matching bundled sample lyrics or explains why lyrics are unavailable. It shows `SAMPLE LYRICS`, `PROVIDER LYRICS`, or `CACHED LYRICS`, with loading and retry states.
6. `SharedPreferencesLyricsCache` keeps successful normalized results on-device for later offline lookup. Cache contents are not encrypted. Cache failures are surfaced rather than reported as successful persistence.
7. `LyricsParser` preserves valid source timestamps and clears invalid or incomplete interval ends. Its LRC parser reads line start tags only; it does not create line ends, word times, or syllable times.
8. `SyncLevelDetector` checks completeness, non-negative times, chronological order, overlap, and parent containment before offering a timed mode.
9. `LyricsSyncEngine` selects a supported mode and produces a `LyricsFrame` for the supplied playback position. The simulator renders that frame, including current/next line and active word or syllable.

## Sync-level selection

Automatic priority is `SYLLABLE`, `WORD`, `LINE`, then `PLAIN`.

- **SYLLABLE** requires every lyric word to have syllables with valid start/end intervals. The syllable intervals must be ordered and non-overlapping. Word timestamps are not required to claim syllable timing.
- **WORD** requires each line to contain words, and every word to have a valid start/end interval. Words must be ordered, non-overlapping, and contained by any valid parent line boundaries.
- **LINE** requires a valid, ordered start timestamp for every line. A line end may be absent, as in common line-timestamp formats; an omitted end remains `null`. When selecting the active line, the next source line start acts as the boundary without being written back as a synthetic end timestamp.
- **PLAIN** is always available, including for empty or untimed lyrics. It displays the first lines without suggesting playback synchronization.

A requested mode that is not supported falls back to the most detailed mode the source actually supports. Invalid intervals (negative starts, end at/before start, out-of-order or overlapping intervals) do not qualify for that timing level. No duration is split to derive word or syllable times.

## Providers

`LyricsProvider` is the shared asynchronous contract. Both provider classes currently fail with a clear configuration error and make no HTTP requests.

### Provider research and current setup

- **LRCLIB:** The official API reference is [lrclib.net/docs](https://lrclib.net/docs). It documents `GET https://lrclib.net/api/get` with `track_name`, `artist_name`, and optional `album_name` and `duration` (seconds), returning the documented lyric-record JSON fields. A documented example request was reachable during verification. The docs say no API key is required, require client identification via `User-Agent` (or documented alternatives), and report approximate limits of 90 requests/minute per identified client IP; clients must honor `Retry-After` on `429` and throttle requests. Registration is optional. No separate license granting rights to redistribute lyric text was identified in the API documentation. The adapter is **not configured or called** because the app has no published project URL and the user has not supplied a contact email. Do not invent either value.
- **LiriQo:** The [official repository README](https://github.com/AlFarrizi-Studio/LiriQo) documents a `/v1/lyrics` API and a normalized response format. The README's API host did not resolve during verification, and current service accessibility/usage terms could not be confirmed; it remains **UNCONFIGURED**. Its repository MIT license is not treated as a license to lyric content.

To enable LRCLIB later, publish the app/project or provide a dedicated project contact email, then add a truthful app-name/version plus URL/email `User-Agent` value, implement the documented query and JSON mapping, handle timeouts/HTTP errors/`429 Retry-After`, and test the adapter against fake transport fixtures before enabling network access. No API key, Internet permission, or provider endpoint is currently wired into the app.

Provider errors do not prevent later providers from being tried. The repository returns `ProviderError` with failures if providers fail, `NotFound` if all report no match, `InvalidMatch` if returned candidates are rejected, and `MissingMetadata` when title or artist is absent. Coroutine cancellation is propagated rather than converted to a provider failure.

## Tests

Run the local JVM suite from the `BagBuddy` project folder:

```bash
./gradlew :app:testDebugUnitTest
```

Tests cover timing priority, incomplete and malformed timestamps, LRC parsing without fabricated timestamps, playback highlighting, empty lyrics, provider fallback/failure behavior, normalized matching, invalid-match rejection, persistent-cache behavior through a fake cache, offline lookup, and presentation states. They do not prove live provider access. Live HTTP handling and real-provider rate-limit behavior remain unimplemented and unverified.
