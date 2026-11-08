# Adaptive lyrics engine

The adaptive timing engine remains offline-first and does not invent timing data. Phase 4 adds provider lookup, track matching, cache, and simulator states. LRCLIB requests are now implemented using its documented API. LiriQo remains explicitly unconfigured.

## Data flow

1. `LyricsRepository` requires a usable title and artist; placeholder media-session metadata is treated as missing.
2. The repository checks the persistent cache first, then tries `LyricsProvider` implementations in order.
3. `LyricsTrackMatcher` requires normalized title and artist equality, rejects duration differences greater than LRCLIB's documented ±2-second matching window, and uses album agreement as an additional matching signal. Rejected results are not displayed or cached.
4. The repository normalizes accepted lyrics and caches successful matches only. Its explicit results are `Found`, `NotFound`, `InvalidMatch`, `ProviderError`, and `MissingMetadata`.
5. The Compose simulator displays provider lyrics only for a verified match; otherwise it preserves matching bundled sample lyrics or explains why lyrics are unavailable. It shows an animated three-dot indicator only while the request is in progress, then reports not-found, invalid-match, missing-metadata, or provider-error states with retry. Before the first timed line, it shows the next lyric instead of a stale “Waiting for lyrics” placeholder.
6. `SharedPreferencesLyricsCache` keeps successful normalized results on-device for later offline lookup. Cache contents are not encrypted. Cache failures are surfaced rather than reported as successful persistence.
7. `LyricsParser` preserves valid source timestamps and clears invalid or incomplete interval ends. Its LRC parser reads line start tags only. `LyricsFileParser` reads explicit millisecond intervals from LRCLIB's documented `lyricsfile` YAML field; malformed, unsupported, or incomplete word data falls back to LRC/PLAIN without creating intervals.
8. `SyncLevelDetector` checks completeness, non-negative times, chronological order, overlap, and parent containment before offering a timed mode.
9. `LyricsSyncEngine` selects a supported mode and produces a `LyricsFrame` for the supplied playback position. The simulator renders that frame, including current/next line and active word or syllable.

## Sync-level selection

Automatic priority is `SYLLABLE`, `WORD`, `LINE`, then `PLAIN`.

- **SYLLABLE** requires every lyric word to have syllables with valid start/end intervals. The syllable intervals must be ordered and non-overlapping. Word timestamps are not required to claim syllable timing.
- **WORD** requires each line to contain words, and every word to have a valid start/end interval. Words must be ordered, non-overlapping, and contained by any valid parent line boundaries.
- **LINE** requires a valid, ordered start timestamp for every line. A line end may be absent, as in common line-timestamp formats; an omitted end remains `null`. When selecting the active line, the next source line start acts as the boundary without being written back as a synthetic end timestamp.
- **PLAIN** is always available, including for empty or untimed lyrics. It displays the first lines without suggesting playback synchronization.

A requested mode that is not supported falls back to the most detailed mode the source actually supports. Invalid intervals (negative starts, end at/before start, out-of-order or overlapping intervals) do not qualify for that timing level. No duration is split to derive word or syllable times.

## Provider capabilities and selection

Providers declare possible timing capabilities (`LINE`, `WORD`, `SYLLABLE`) as
search hints only. These declarations do not enable a sync mode. Each normalized
result is independently checked by `SyncLevelDetector`, and the repository
selects the result with the best valid timing data: `SYLLABLE`, then `WORD`,
then `LINE`, then `PLAIN`. Ties retain provider order; a valid cached result is
kept on a tie. Invalid, incomplete, out-of-order, or overlapping intervals
cannot improve a result's rank.

The cache is returned without network work when its detected level is at least
as detailed as the best configured provider capability, or when each configured
provider has already been checked at its current capability level for that
cached track. Failed provider calls are not marked checked and can be retried.
This permits a word-capable provider to improve a cached LRCLIB line result
without repeating successful line-only lookups on every later read.

`LyricsTrack.sourceInfo` retains the adapter ID and optional provider,
upstream-provider, and source labels when present. LRCLIB results identify
LRCLIB; LiriQo parsing preserves its documented `provider`,
`upstreamProvider`, and `lyricsSource` fields. These labels are attribution
metadata, not evidence that a timing level is supported.

### Word timing from LRCLIB

LRCLIB now documents a `hasWordSync` flag and raw `lyricsfile` YAML in API
records. The Lyricsfile 1.0 draft specifies `lines[].words[]` with absolute
`start_ms` and optional `end_ms` in integer milliseconds. Bag Buddy parses
these source values (not the flag) using a safe YAML parser. WORD is enabled
only when every non-empty source line has words, every word has valid start/end
intervals, and the intervals pass ordering/containment checks. Empty lyric
records are skipped; malformed non-empty records reject the Lyricsfile and
fall back to the existing LRC and then plain lyrics. Word text must reconstruct
the line text; when source word tokens omit only inter-word whitespace, the
parser retains the exact line whitespace alongside those tokens. Incomplete
coverage or other text mismatches degrade to LINE.
LRCLIB LRC timestamps remain line-level only. The Lyricsfile format spec is a
draft and CC0 applies to the specification/examples, not automatically to
lyric text supplied in a record. The LRCLIB API docs do not identify a separate
license granting redistribution rights for lyric text.

### LiriQo preparation

`LiriQoLyricsResponseParser` parses the documented JSON `metadata`, `tracks`,
`timed`, and `words` fields. It preserves supplied millisecond values and
selects the best candidate based on validated intervals; response-level
`syncLevel` or capability labels are ignored. The current public API is
documented and its examples provide absolute millisecond word intervals. The
repository's MIT license covers its code, not upstream lyric content. The API
documentation recommends retaining provider/upstream attribution where given,
but a clear lyric-content usage/redistribution grant was not located.
Accordingly, `LiriQoLyricsProvider` has no network transport and remains
disabled until rights and attribution are clarified.

SYLLABLE is available only when actual syllable objects with complete valid
intervals exist in a normalized track. The current LiriQo normalized response
parser consumes documented word timing only, and the Lyricsfile 1.0 format
supports line and word timing, not syllable units. A provider `syncLevel`
claim cannot activate syllable highlighting.

## Providers

`LyricsProvider` is the shared asynchronous contract. `LrclibLyricsProvider` is configured with the public project identity in its `User-Agent`; `LiriQoLyricsProvider` remains unconfigured and makes no requests.

### Provider research and current setup

- **LRCLIB:** The official API reference is [lrclib.net/docs](https://lrclib.net/docs). The app calls `GET https://lrclib.net/api/get` with `track_name`, `artist_name`, and optional `album_name` and `duration` (seconds). It sends `Bag Buddy/1.0 (https://github.com/sauravloveslittichokha/BagBuddy)` as `User-Agent`; no API key is embedded. The adapter applies 10-second connect/read timeouts, maps `404` to no match, surfaces other HTTP errors, parses `Retry-After` on `429`, and suppresses further network attempts until that delay plus up to 30% random jitter (capped at five minutes) expires; a 60-second delay is used if a 429 omits the header. It reads documented metadata, `plainLyrics`, `syncedLyrics`, `hasWordSync`, and `lyricsfile`. The flag is not trusted to enable WORD: only validated `lyricsfile` `lines[].words[]` intervals can do that. LRC remains line-level only. The Lyricsfile 1.0 spec is a draft; it is CC0 as a format specification, not a lyric-content license. No separate LRCLIB lyric-text redistribution license was identified. API docs report approximate limits of 90 requests/minute per identified client IP; requests are user/track initiated, not polled. Registration is optional.
- **LiriQo:** The [official repository README](https://github.com/AlFarrizi-Studio/LiriQo) documents the public `/v1/lyrics` API and normalized response fields, including timed lines, embedded words, absolute millisecond units, and optional upstream attribution. The endpoint is currently documented and reachable. The repository is MIT-licensed, but this does not license upstream lyric content. The documented guidance says to preserve provider/source attribution when supplied; a clear lyric-content usage/redistribution grant was not found. The adapter remains **UNCONFIGURED** and makes no requests pending rights and attribution clarification.

The Android manifest includes Internet permission for LRCLIB. The adapter has an injectable HTTP transport; unit tests use fake responses and do not call LRCLIB. A successful build and fake-transport tests do not establish that the live service is available or that a live search returns usable lyrics. Live end-to-end testing remains to be done on a device/network.

### Diagnostics

The Android app writes provider, HTTP, Lyricsfile parse, validated sync-level,
cache, and repository-selection events to Logcat under the `BagBuddyLyrics`
tag. Entries include timing counts and rejection codes but never lyric text.
Use `adb logcat -s BagBuddyLyrics` while loading a track to trace whether
LRCLIB returned word timing, whether parsing retained it, and which level the
repository selected.

Provider errors do not prevent later providers from being tried. The repository returns `ProviderError` with failures if providers fail, `NotFound` if all report no match, `InvalidMatch` if returned candidates are rejected, and `MissingMetadata` when title or artist is absent. Coroutine cancellation is propagated rather than converted to a provider failure.

## Tests

Run the local JVM suite from the `BagBuddy` project folder:

```bash
./gradlew :app:testDebugUnitTest
```

Tests cover timing priority, incomplete and malformed timestamps, LRC parsing without fabricated timestamps, synthetic Lyricsfile word intervals, malformed/incomplete word timing fallback, playback word highlighting, empty lyrics, provider fallback/failure behavior, normalized matching and LRCLIB's ±2-second duration match window, invalid-match rejection, persistent-cache behavior through a fake cache, offline lookup, loading/success/not-found/error/retry presentation transitions, LRCLIB request construction and response parsing, timeout/error mapping, 404 handling, and 429 backoff. Tests use fake HTTP transport and synthetic lyrics only. Physical-device testing confirms LRCLIB loading and LINE sync; a live word-synced response has not yet been verified on-device.
