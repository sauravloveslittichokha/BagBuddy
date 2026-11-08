package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.LyricsSyncLevel
import com.saurav.bagbuddy.data.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrclibLyricsProviderTest {
    private val song = Song(
        id = "selected",
        title = "Night & Day",
        artist = "A Person",
        album = "An Album",
        durationMs = 180_000L,
        artworkEmoji = "♪"
    )

    @Test
    fun buildsDocumentedRequestAndMapsSourceLrcWithoutInventingTimes() = runBlocking {
        var observedUrl = ""
        var observedUserAgent = ""
        var observedConnectTimeout = 0
        var observedReadTimeout = 0
        val provider = provider(
            response = {
                LyricsHttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "id": 88,
                          "trackName": "Night & Day",
                          "artistName": "A Person",
                          "albumName": "An Album",
                          "duration": 180,
                          "instrumental": false,
                          "plainLyrics": "First line\nSecond line",
                          "syncedLyrics": "[00:01.25]First line\n[00:04.5]Second line"
                        }
                    """.trimIndent()
                )
            },
            onRequest = { url, userAgent, connectTimeoutMs, readTimeoutMs ->
                observedUrl = url
                observedUserAgent = userAgent
                observedConnectTimeout = connectTimeoutMs
                observedReadTimeout = readTimeoutMs
            }
        )

        val result = provider.fetchLyrics(song)!!

        assertTrue(observedUrl.startsWith("https://lrclib.net/api/get?"))
        assertTrue(observedUrl.contains("track_name=Night+%26+Day"))
        assertTrue(observedUrl.contains("artist_name=A+Person"))
        assertTrue(observedUrl.contains("album_name=An+Album"))
        assertTrue(observedUrl.contains("duration=180"))
        assertEquals(
            "Bag Buddy/1.0 (https://github.com/sauravloveslittichokha/BagBuddy)",
            observedUserAgent
        )
        assertEquals(4_000, observedConnectTimeout)
        assertEquals(5_000, observedReadTimeout)
        assertEquals(listOf(1_250L, 4_500L), result.lines.map { it.startMs })
        assertTrue(result.lines.all { it.endMs == null && it.words.isEmpty() })
        assertEquals(LyricsSyncLevel.LINE, SyncLevelDetector.detect(result))
        assertEquals(
            listOf(LyricsSyncLevel.LINE, LyricsSyncLevel.PLAIN),
            SyncLevelDetector.supportedLevels(result)
        )
        val frame = LyricsSyncEngine().frameAt(result, 1_300L)
        assertEquals(LyricsSyncLevel.LINE, frame.syncLevel)
        assertEquals("First line", frame.currentLine?.text)
        assertNull(frame.activeWordIndex)
        assertNull(frame.activeSyllableIndex)
        assertEquals("88", result.song.id)
    }

    @Test
    fun parsesSyntheticLyricsfileWordIntervalsAndUsesThemForPlaybackHighlighting() = runBlocking {
        val result = provider(
            response = {
                LyricsHttpResponse(
                    200,
                    """
                        {
                          "id": 89,
                          "trackName": "Night & Day",
                          "artistName": "A Person",
                          "duration": 180,
                          "hasWordSync": false,
                          "syncedLyrics": "[00:01.00]Synthetic phrase",
                          "lyricsfile": "version: '1.0'\nmetadata:\n  title: 'Night & Day'\n  artist: 'A Person'\nlines:\n  - text: 'Synthetic phrase'\n    start_ms: 1000\n    end_ms: 3000\n    words:\n      - text: 'Synthetic '\n        start_ms: 1000\n        end_ms: 1900\n      - text: phrase\n        start_ms: 1900\n        end_ms: 3000\n"
                        }
                    """.trimIndent()
                )
            }
        ).fetchLyrics(song)!!

        assertEquals(LyricsSyncLevel.WORD, SyncLevelDetector.detect(result))
        assertEquals("LRCLIB", result.sourceInfo?.providerId)
        assertEquals(listOf(1_000L, 1_900L), result.lines.single().words.map { it.startMs })
        assertEquals(listOf(1_900L, 3_000L), result.lines.single().words.map { it.endMs })
        val frame = LyricsSyncEngine().frameAt(result, 2_000L)
        assertEquals(LyricsSyncLevel.WORD, frame.syncLevel)
        assertEquals(1, frame.activeWordIndex)
    }

    @Test
    fun trailingEmptyLyricsfileLineDoesNotDiscardWordTiming() = runBlocking {
        val result = provider(
            response = {
                LyricsHttpResponse(
                    200,
                    """
                        {
                          "trackName": "Night & Day",
                          "artistName": "A Person",
                          "hasWordSync": true,
                          "lyricsfile": "version: '1.0'\nmetadata: {title: 'Night & Day', artist: 'A Person'}\nlines:\n  - text: 'First phrase'\n    start_ms: 1000\n    end_ms: 3000\n    words:\n      - text: 'First '\n        start_ms: 1000\n        end_ms: 1900\n      - text: phrase\n        start_ms: 1900\n        end_ms: 3000\n  - text: ''\n    start_ms: 3000\n"
                        }
                    """.trimIndent()
                )
            }
        ).fetchLyrics(song)!!

        assertEquals(LyricsSyncLevel.WORD, SyncLevelDetector.detect(result))
        assertEquals(1, result.lines.size)
        assertEquals(listOf(1_000L, 1_900L), result.lines.single().words.map { it.startMs })
    }

    @Test
    fun wordTokensWithoutWhitespaceAreAlignedToTheOriginalLineText() = runBlocking {
        val result = provider(
            response = {
                LyricsHttpResponse(
                    200,
                    """
                        {
                          "trackName": "Night & Day",
                          "artistName": "A Person",
                          "hasWordSync": true,
                          "lyricsfile": "version: '1.0'\nmetadata: {title: 'Night & Day', artist: 'A Person'}\nlines:\n  - text: 'First phrase'\n    start_ms: 1000\n    end_ms: 3000\n    words:\n      - text: First\n        start_ms: 1000\n        end_ms: 1900\n      - text: phrase\n        start_ms: 1900\n        end_ms: 3000\n"
                        }
                    """.trimIndent()
                )
            }
        ).fetchLyrics(song)!!

        assertEquals(LyricsSyncLevel.WORD, SyncLevelDetector.detect(result))
        assertEquals("First phrase", result.lines.single().words.joinToString("") { it.text })
        assertEquals(1_900L, result.lines.single().words[1].startMs)
    }

    @Test
    fun diagnosticsReportProviderAndTimingStagesWithoutLoggingLyrics() = runBlocking {
        val diagnostics = mutableListOf<String>()
        val provider = provider(
            diagnostics = LyricsDiagnostics(diagnostics::add),
            response = {
                LyricsHttpResponse(
                    200,
                    """
                        {
                          "trackName": "Night & Day",
                          "artistName": "A Person",
                          "hasWordSync": true,
                          "lyricsfile": "version: '1.0'\nmetadata: {title: 'Night & Day', artist: 'A Person'}\nlines:\n  - text: 'Synthetic phrase'\n    start_ms: 1000\n    end_ms: 3000\n    words:\n      - text: 'Synthetic '\n        start_ms: 1000\n        end_ms: 1900\n      - text: phrase\n        start_ms: 1900\n        end_ms: 3000\n"
                        }
                    """.trimIndent()
                )
            }
        )

        val lookup = LyricsRepository(
            providers = listOf(provider),
            cache = InMemoryLyricsCache(),
            diagnostics = LyricsDiagnostics(diagnostics::add)
        ).findLyrics(song)
        val result = (lookup as LyricsLookupResult.Found).track

        assertEquals(LyricsSyncLevel.WORD, SyncLevelDetector.detect(result))
        assertTrue(diagnostics.any { "hasWordSync=true" in it && "lyricsfilePresent=true" in it })
        assertTrue(diagnostics.any { "fullyTimedWords=2" in it })
        assertTrue(diagnostics.any { "selected source=lyricsfile sync=WORD" in it })
        assertTrue(diagnostics.any { "lyrics selection provider=LRCLIB" in it && "sync=WORD" in it })
        assertTrue(diagnostics.none { "Synthetic phrase" in it })
    }

    @Test
    fun lyricsfileWithoutWordArraysIsReportedAsLineOnlyAtProviderAndRepository() = runBlocking {
        val diagnostics = mutableListOf<String>()
        val provider = provider(
            diagnostics = LyricsDiagnostics(diagnostics::add),
            response = {
                LyricsHttpResponse(
                    200,
                    """
                        {
                          "trackName": "Night & Day",
                          "artistName": "A Person",
                          "hasWordSync": false,
                          "lyricsfile": "version: '1.0'\nmetadata: {title: 'Night & Day', artist: 'A Person'}\nlines:\n  - text: 'Synthetic line'\n    start_ms: 1000\n    end_ms: 2000\n"
                        }
                    """.trimIndent()
                )
            }
        )

        val lookup = LyricsRepository(
            providers = listOf(provider),
            cache = InMemoryLyricsCache(),
            diagnostics = LyricsDiagnostics(diagnostics::add)
        ).findLyrics(song)
        val result = (lookup as LyricsLookupResult.Found).track

        assertEquals(LyricsSyncLevel.LINE, SyncLevelDetector.detect(result))
        assertEquals(
            "line_without_word_segments",
            SyncLevelDetector.wordTimingRejectionReason(result)
        )
        assertTrue(diagnostics.any {
            "hasWordSync=false" in it && "lyricsfilePresent=true" in it
        })
        assertTrue(diagnostics.any {
            "parse=parsed_without_word_segments" in it && "words=0" in it
        })
        assertTrue(diagnostics.any {
            "selected source=lyricsfile sync=LINE" in it &&
                "wordTiming=line_without_word_segments" in it
        })
    }

    @Test
    fun incompleteWordIntervalsFallBackToLineSynchronization() = runBlocking {
        val result = provider(
            response = {
                LyricsHttpResponse(
                    200,
                    """
                        {
                          "trackName": "Night & Day",
                          "artistName": "A Person",
                          "duration": 180,
                          "hasWordSync": true,
                          "syncedLyrics": "[00:01.00]Synthetic phrase",
                          "lyricsfile": "version: '1.0'\nmetadata: {title: 'Night & Day', artist: 'A Person'}\nlines:\n  - text: 'Synthetic phrase'\n    start_ms: 1000\n    words:\n      - text: 'Synthetic '\n        start_ms: 1000\n        end_ms: 1900\n      - text: phrase\n        start_ms: 1900\n"
                        }
                    """.trimIndent()
                )
            }
        ).fetchLyrics(song)!!

        assertEquals(LyricsSyncLevel.LINE, SyncLevelDetector.detect(result))
        assertEquals(null, result.lines.single().words.last().endMs)
    }

    @Test
    fun incompleteWordCoverageFallsBackToLineSynchronization() = runBlocking {
        val result = provider(
            response = {
                LyricsHttpResponse(
                    200,
                    """
                        {
                          "trackName": "Night & Day",
                          "artistName": "A Person",
                          "duration": 180,
                          "hasWordSync": true,
                          "syncedLyrics": "[00:01.00]Synthetic phrase",
                          "lyricsfile": "version: '1.0'\nmetadata: {title: 'Night & Day', artist: 'A Person'}\nlines:\n  - text: 'Synthetic phrase'\n    start_ms: 1000\n    words:\n      - text: Synthetic\n        start_ms: 1000\n        end_ms: 1900\n"
                        }
                    """.trimIndent()
                )
            }
        ).fetchLyrics(song)!!

        assertEquals(LyricsSyncLevel.LINE, SyncLevelDetector.detect(result))
        assertTrue(result.lines.single().words.isEmpty())
    }

    @Test
    fun malformedLyricsfileFallsBackToDocumentedLrcWithoutInventingWords() = runBlocking {
        val result = provider(
            response = {
                LyricsHttpResponse(
                    200,
                    """
                        {
                          "trackName": "Night & Day",
                          "artistName": "A Person",
                          "duration": 180,
                          "hasWordSync": true,
                          "syncedLyrics": "[00:01.00]Synthetic phrase",
                          "lyricsfile": "version: '1.0'\nlines:\n  - text: [unclosed"
                        }
                    """.trimIndent()
                )
            }
        ).fetchLyrics(song)!!

        assertEquals(LyricsSyncLevel.LINE, SyncLevelDetector.detect(result))
        assertTrue(result.lines.single().words.isEmpty())
    }

    @Test
    fun plainLyricsAreUsedWhenSyncedLyricsAreUnavailable() = runBlocking {
        val provider = provider(
            response = {
                LyricsHttpResponse(
                    200,
                    """{"trackName":"Night & Day","artistName":"A Person","plainLyrics":"Line one\n\nLine two","syncedLyrics":null}"""
                )
            }
        )

        val result = provider.fetchLyrics(song)!!

        assertEquals(listOf("Line one", "Line two"), result.lines.map { it.text })
        assertTrue(result.lines.all { it.startMs == null && it.endMs == null })
        assertEquals(LyricsSyncLevel.PLAIN, SyncLevelDetector.detect(result))
    }

    @Test
    fun notFoundAndRecordsWithoutLyricsReturnNoMatch() = runBlocking {
        assertNull(
            provider(response = { LyricsHttpResponse(404, "") }).fetchLyrics(song)
        )
        assertNull(
            provider(
                response = {
                    LyricsHttpResponse(
                        200,
                        """{"trackName":"Night & Day","artistName":"A Person","plainLyrics":null,"syncedLyrics":null}"""
                    )
                }
            ).fetchLyrics(song)
        )
    }

    @Test
    fun httpErrorsAndMalformedJsonAreReportedInsteadOfFakedAsNoResult() = runBlocking {
        val serverError = runCatching {
            provider(response = { LyricsHttpResponse(503, "") }).fetchLyrics(song)
        }.exceptionOrNull()
        val invalidJson = runCatching {
            provider(response = { LyricsHttpResponse(200, "{") }).fetchLyrics(song)
        }.exceptionOrNull()

        assertTrue(serverError is LyricsHttpException)
        assertEquals(503, (serverError as LyricsHttpException).statusCode)
        assertTrue(invalidJson is LyricsResponseException)
    }

    @Test
    fun rateLimitSurfacesRetryAfterAndPreventsEarlyFollowupRequest() = runBlocking {
        var requestCount = 0
        val provider = LrclibLyricsProvider(
            transport = LyricsHttpTransport { _, _, _, _ ->
                requestCount++
                LyricsHttpResponse(429, "", retryAfter = "30")
            },
            userAgent = "Bag Buddy/1.0 (https://github.com/sauravloveslittichokha/BagBuddy)"
        )

        val firstFailure = runCatching { provider.fetchLyrics(song) }.exceptionOrNull()
        val secondFailure = runCatching { provider.fetchLyrics(song) }.exceptionOrNull()

        assertTrue(firstFailure is LyricsRateLimitException)
        assertEquals(30L, (firstFailure as LyricsRateLimitException).retryAfterSeconds)
        assertTrue(secondFailure is LyricsRateLimitException)
        assertEquals(1, requestCount)
    }

    @Test
    fun networkTimeoutPropagatesForRepositoryErrorReporting() = runBlocking {
        val provider = LrclibLyricsProvider(
            transport = LyricsHttpTransport { _, _, _, _ ->
                throw java.net.SocketTimeoutException("read timed out")
            },
            userAgent = "Bag Buddy/1.0 (https://github.com/sauravloveslittichokha/BagBuddy)"
        )

        val failure = runCatching { provider.fetchLyrics(song) }.exceptionOrNull()

        assertTrue(failure is java.net.SocketTimeoutException)
    }

    @Test
    fun repositorySurfacesHttpStatusAndRetryDelay() = runBlocking {
        val provider = provider(
            response = {
                LyricsHttpResponse(429, "", retryAfter = "25")
            }
        )

        val result = LyricsRepository(
            providers = listOf(provider),
            cache = InMemoryLyricsCache()
        ).findLyrics(song)

        val failure = (result as LyricsLookupResult.ProviderError).failures.single()
        assertEquals(429, failure.httpStatusCode)
        assertTrue(failure.retryAfterSeconds != null && failure.retryAfterSeconds!! in 1L..25L)
        assertTrue(
            LyricsPresentationState.fromResult(result, sampleTrack = null)
                .message.contains("rate limited")
        )
    }

    @Test
    fun wrongResponseMetadataIsRejectedByRepositoryMatchValidation() = runBlocking {
        val provider = provider(
            response = {
                LyricsHttpResponse(
                    200,
                    """{"trackName":"Different Song","artistName":"A Person","plainLyrics":"Wrong lyrics"}"""
                )
            }
        )

        val result = LyricsRepository(
            providers = listOf(provider),
            cache = InMemoryLyricsCache()
        ).findLyrics(song)

        assertEquals(
            LyricsLookupResult.InvalidMatch(listOf("LRCLIB")),
            result
        )
    }

    @Test
    fun instrumentalRecordsAndMissingProviderMetadataCannotBecomeFakeLyrics() = runBlocking {
        val instrumental = provider(
            response = {
                LyricsHttpResponse(
                    200,
                    """{"trackName":"Night & Day","artistName":"A Person","instrumental":true,"plainLyrics":null,"syncedLyrics":null}"""
                )
            }
        )
        val missingMetadata = provider(
            response = {
                LyricsHttpResponse(200, """{"plainLyrics":"not matched","syncedLyrics":null}""")
            }
        )

        assertNull(instrumental.fetchLyrics(song))
        assertTrue(
            runCatching { missingMetadata.fetchLyrics(song) }.exceptionOrNull()
                is LyricsResponseException
        )
        assertFalse(LyricsTrackMatcher.matches(song, song.copy(title = "")))
    }

    private fun provider(
        response: suspend () -> LyricsHttpResponse,
        onRequest: (String, String, Int, Int) -> Unit = { _, _, _, _ -> },
        diagnostics: LyricsDiagnostics = LyricsDiagnostics.NONE
    ) = LrclibLyricsProvider(
        transport = LyricsHttpTransport { url, userAgent, connectTimeoutMs, readTimeoutMs ->
            onRequest(url, userAgent, connectTimeoutMs, readTimeoutMs)
            response()
        },
        userAgent = "Bag Buddy/1.0 (https://github.com/sauravloveslittichokha/BagBuddy)",
        connectTimeoutMs = 4_000,
        readTimeoutMs = 5_000,
        diagnostics = diagnostics
    )
}
