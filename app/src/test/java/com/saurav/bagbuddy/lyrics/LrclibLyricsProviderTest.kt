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
        assertEquals("88", result.song.id)
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
        onRequest: (String, String, Int, Int) -> Unit = { _, _, _, _ -> }
    ) = LrclibLyricsProvider(
        transport = LyricsHttpTransport { url, userAgent, connectTimeoutMs, readTimeoutMs ->
            onRequest(url, userAgent, connectTimeoutMs, readTimeoutMs)
            response()
        },
        userAgent = "Bag Buddy/1.0 (https://github.com/sauravloveslittichokha/BagBuddy)",
        connectTimeoutMs = 4_000,
        readTimeoutMs = 5_000
    )
}
