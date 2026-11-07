package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.LyricLine
import com.saurav.bagbuddy.data.LyricSyllable
import com.saurav.bagbuddy.data.LyricWord
import com.saurav.bagbuddy.data.LyricsSyncLevel
import com.saurav.bagbuddy.data.LyricsTrack
import com.saurav.bagbuddy.data.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiProviderLyricsTest {
    private val request = Song(
        id = "selected-song",
        title = "Synthetic Signal",
        artist = "Example Artist",
        durationMs = 180_000L,
        artworkEmoji = "♪"
    )

    @Test
    fun repositoryChoosesValidatedWordTimingOverEarlierLineTiming() = runBlocking {
        val result = LyricsRepository(
            providers = listOf(
                provider("line-provider", LyricsProviderCapabilities.LINE_ONLY) {
                    track(listOf(LyricLine("Synthetic phrase", 0L)))
                },
                provider("word-provider", LyricsProviderCapabilities.LINE_AND_WORD) {
                    track(
                        listOf(
                            LyricLine(
                                text = "Synthetic phrase",
                                startMs = 0L,
                                endMs = 2_000L,
                                words = listOf(
                                    LyricWord("Synthetic", 0L, 1_000L),
                                    LyricWord("phrase", 1_000L, 2_000L)
                                )
                            )
                        )
                    )
                }
            )
        ).findLyrics(request) as LyricsLookupResult.Found

        assertEquals("word-provider", result.providerId)
        assertEquals(LyricsSyncLevel.WORD, SyncLevelDetector.detect(result.track))
        assertEquals("word-provider", result.track.sourceInfo?.providerId)
    }

    @Test
    fun betterProviderUpgradesCachedLineResultWithoutRepeatingWhenCacheIsSufficient() =
        runBlocking {
            val cache = InMemoryLyricsCache()
            LyricsRepository(
                providers = listOf(provider("line-provider", LyricsProviderCapabilities.LINE_ONLY) {
                    track(listOf(LyricLine("Synthetic phrase", 0L)))
                }),
                cache = cache
            ).findLyrics(request)

            var wordProviderCalls = 0
            val wordProvider = provider(
                "word-provider",
                LyricsProviderCapabilities.LINE_AND_WORD
            ) {
                wordProviderCalls++
                track(
                    listOf(
                        LyricLine(
                            text = "Synthetic phrase",
                            startMs = 0L,
                            endMs = 2_000L,
                            words = listOf(
                                LyricWord("Synthetic", 0L, 1_000L),
                                LyricWord("phrase", 1_000L, 2_000L)
                            )
                        )
                    )
                )
            }
            val repository = LyricsRepository(listOf(wordProvider), cache = cache)

            val upgraded = repository.findLyrics(request) as LyricsLookupResult.Found
            val cached = repository.findLyrics(request) as LyricsLookupResult.Found

            assertEquals(LyricsSyncLevel.WORD, SyncLevelDetector.detect(upgraded.track))
            assertEquals(LyricsResultOrigin.PROVIDER, upgraded.origin)
            assertEquals(LyricsResultOrigin.CACHE, cached.origin)
            assertEquals(1, wordProviderCalls)
        }

    @Test
    fun invalidClaimedWordTimingDoesNotOutrankValidatedLineTiming() = runBlocking {
        val result = LyricsRepository(
            providers = listOf(
                provider("word-claim", LyricsProviderCapabilities.LINE_AND_WORD) {
                    track(
                        listOf(
                            LyricLine(
                                text = "Synthetic phrase",
                                words = listOf(
                                    LyricWord("Synthetic", 1_000L, 500L),
                                    LyricWord("phrase", 500L, 2_000L)
                                )
                            )
                        )
                    )
                },
                provider("line-provider", LyricsProviderCapabilities.LINE_ONLY) {
                    track(listOf(LyricLine("Synthetic phrase", 0L, 2_000L)))
                }
            )
        ).findLyrics(request) as LyricsLookupResult.Found

        assertEquals("line-provider", result.providerId)
        assertEquals(LyricsSyncLevel.LINE, SyncLevelDetector.detect(result.track))
    }

    @Test
    fun liriqoParserPreservesDocumentedWordTimingAndAttribution() {
        val response = """
            {
              "metadata": {
                "title": "Synthetic Signal",
                "artist": "Example Artist",
                "album": "Demo Album",
                "duration": 180
              },
              "primary": { "syncLevel": "syllable" },
              "tracks": [{
                "provider": "example-catalog",
                "upstreamProvider": "Example Source",
                "lyricsSource": "catalog",
                "syncLevel": "syllable",
                "timed": [{
                  "text": "Synthetic phrase",
                  "start": 0,
                  "end": 2000,
                  "words": [
                    { "text": "Synthetic", "start": 0, "end": 1000 },
                    { "text": "phrase", "start": 1000, "end": 2000 }
                  ]
                }]
              }]
            }
        """.trimIndent()

        val parsed = LiriQoLyricsResponseParser().parseResponse(request, response)!!

        assertEquals(LyricsSyncLevel.WORD, SyncLevelDetector.detect(parsed))
        assertTrue(SyncLevelDetector.supportedLevels(parsed).contains(LyricsSyncLevel.WORD))
        assertFalse(SyncLevelDetector.supportedLevels(parsed).contains(LyricsSyncLevel.SYLLABLE))
        assertEquals("LiriQo", parsed.sourceInfo?.providerId)
        assertEquals("example-catalog", parsed.sourceInfo?.providerName)
        assertEquals("Example Source", parsed.sourceInfo?.upstreamProvider)
        assertEquals("catalog", parsed.sourceInfo?.lyricsSource)
        assertEquals(0L, parsed.lines.single().words.first().startMs)
    }

    @Test
    fun parserKeepsMalformedIntervalsUnavailableInsteadOfInventingTimings() {
        val response = """
            {
              "metadata": {
                "title": "Synthetic Signal",
                "artist": "Example Artist",
                "duration": 180
              },
              "tracks": [{
                "syncLevel": "word",
                "timed": [{
                  "text": "Synthetic phrase",
                  "start": 0,
                  "words": [
                    { "text": "Synthetic", "start": 1.5, "end": 1000 },
                    { "text": "phrase", "start": 1000, "end": 2000 }
                  ]
                }]
              }]
            }
        """.trimIndent()

        val parsed = LiriQoLyricsResponseParser().parseResponse(request, response)!!

        assertEquals(LyricsSyncLevel.LINE, SyncLevelDetector.detect(parsed))
        assertEquals(null, parsed.lines.single().words.first().startMs)
        assertEquals(null, parsed.lines.single().words.first().endMs)
    }

    @Test
    fun syllableLevelIsAvailableOnlyForCompleteGenuineIntervals() {
        val track = track(
            listOf(
                LyricLine(
                    text = "Singing",
                    startMs = 0L,
                    endMs = 2_000L,
                    words = listOf(
                        LyricWord(
                            text = "Singing",
                            startMs = 0L,
                            endMs = 2_000L,
                            syllables = listOf(
                                LyricSyllable("Sing", 0L, 900L),
                                LyricSyllable("ing", 900L, 2_000L)
                            )
                        )
                    )
                )
            )
        )

        assertEquals(LyricsSyncLevel.SYLLABLE, SyncLevelDetector.detect(track))
        assertTrue(
            SyncLevelDetector.supportedLevels(track).contains(LyricsSyncLevel.SYLLABLE)
        )
    }

    @Test
    fun disabledLiriqoProviderDoesNotMakeNetworkRequests() = runBlocking {
        val result = LyricsRepository(
            providers = listOf(
                LiriQoLyricsProvider(),
                provider("line-fallback", LyricsProviderCapabilities.LINE_ONLY) {
                    track(listOf(LyricLine("Synthetic phrase", 0L)))
                }
            )
        ).findLyrics(request) as LyricsLookupResult.Found

        assertEquals("line-fallback", result.providerId)
        assertEquals(LyricsSyncLevel.LINE, SyncLevelDetector.detect(result.track))
    }

    private fun track(lines: List<LyricLine>) = LyricsTrack(
        song = request,
        lines = lines
    )

    private fun provider(
        id: String,
        supported: LyricsProviderCapabilities,
        fetch: suspend () -> LyricsTrack?
    ) = object : LyricsProvider {
        override val providerId: String = id
        override val capabilities: LyricsProviderCapabilities = supported
        override suspend fun fetchLyrics(song: Song): LyricsTrack? = fetch()
    }
}
