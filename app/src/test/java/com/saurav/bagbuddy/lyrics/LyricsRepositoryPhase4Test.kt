package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.LyricLine
import com.saurav.bagbuddy.data.LyricsTrack
import com.saurav.bagbuddy.data.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class LyricsRepositoryPhase4Test {
    private val request = Song(
        id = "selected",
        title = "Café Lights!",
        artist = "The Example",
        durationMs = 180_000L,
        artworkEmoji = "♪",
        album = "Night Drive"
    )
    private val correctTrack = LyricsTrack(
        song = request.copy(id = "provider-result", title = "Cafe Lights", album = "night drive"),
        lines = listOf(LyricLine("A source lyric", startMs = 2_500L))
    )

    @Test
    fun normalizedTitleAndArtistMatchButWrongSongMetadataIsRejected() {
        assertTrue(LyricsTrackMatcher.matches(request, correctTrack.song))
        assertFalse(
            LyricsTrackMatcher.matches(
                request,
                correctTrack.song.copy(title = "Different Song")
            )
        )
        assertFalse(
            LyricsTrackMatcher.matches(
                request,
                correctTrack.song.copy(artist = "Different Artist")
            )
        )
        assertFalse(
            LyricsTrackMatcher.matches(
                request,
                correctTrack.song.copy(durationMs = 182_001L)
            )
        )
        assertTrue(
            LyricsTrackMatcher.matches(
                request,
                correctTrack.song.copy(durationMs = 182_000L)
            )
        )
    }

    @Test
    fun placeholderAndMissingTitleOrArtistAreNotSentToProviders() = runBlocking {
        var fetchCalls = 0
        val repository = LyricsRepository(
            providers = listOf(provider("fake") {
                fetchCalls++
                correctTrack
            })
        )

        assertEquals(
            LyricsLookupResult.MissingMetadata,
            repository.findLyrics(request.copy(title = "Title unavailable"))
        )
        assertEquals(
            LyricsLookupResult.MissingMetadata,
            repository.findLyrics(request.copy(artist = " "))
        )
        assertEquals(0, fetchCalls)
    }

    @Test
    fun exactProviderMatchIsFoundAndCached() = runBlocking {
        val cache = InMemoryLyricsCache()
        val result = LyricsRepository(
            providers = listOf(provider("primary") { correctTrack }),
            cache = cache
        ).findLyrics(request)

        assertEquals("primary", (result as LyricsLookupResult.Found).providerId)
        assertEquals(LyricsResultOrigin.PROVIDER, result.origin)
        assertEquals(correctTrack.lines, result.track.lines)
    }

    @Test
    fun primaryFailureFallsBackToNextProviderAndNoMatchIsNotFound() = runBlocking {
        val result = LyricsRepository(
            providers = listOf(
                provider("primary") { throw SocketTimeoutException("timed out") },
                provider("fallback") { correctTrack }
            )
        ).findLyrics(request)

        assertEquals("fallback", (result as LyricsLookupResult.Found).providerId)
        assertEquals(
            LyricsLookupResult.NotFound,
            LyricsRepository(providers = listOf(provider("empty") { null }))
                .findLyrics(request)
        )
    }

    @Test
    fun wrongProviderMatchIsRejectedAndReportedExplicitly() = runBlocking {
        val wrongSong = correctTrack.copy(
            song = correctTrack.song.copy(artist = "Not the artist")
        )

        val result = LyricsRepository(
            providers = listOf(provider("wrong-result") { wrongSong })
        ).findLyrics(request)

        assertEquals(
            LyricsLookupResult.InvalidMatch(listOf("wrong-result")),
            result
        )
    }

    @Test
    fun providerErrorsAreExplicitAndAnUnconfiguredProviderIsNotReportedAsConnected() =
        runBlocking {
            val error = LyricsRepository(
                providers = listOf(provider("unconfigured") {
                    throw LyricsProviderNotConfiguredException("unconfigured")
                })
            ).findLyrics(request) as LyricsLookupResult.ProviderError

            assertTrue(error.failures.single().configurationMissing)
            assertTrue(error.failures.single().message.contains("not configured"))
        }

    @Test
    fun successfulLyricsAreAvailableFromCacheWithoutProviderRequests() = runBlocking {
        val cache = InMemoryLyricsCache()
        LyricsRepository(
            providers = listOf(provider("lrclib-fake") { correctTrack }),
            cache = cache
        ).findLyrics(request)
        var providerCalled = false

        val offlineResult = LyricsRepository(
            providers = listOf(provider("offline-provider") {
                providerCalled = true
                null
            }),
            cache = cache
        ).findLyrics(request)

        val found = offlineResult as LyricsLookupResult.Found
        assertEquals(LyricsResultOrigin.CACHE, found.origin)
        assertEquals("lrclib-fake", found.providerId)
        assertFalse(providerCalled)
    }

    @Test
    fun unsuccessfulProviderResultsAreNeverCached() = runBlocking {
        val cache = InMemoryLyricsCache()
        val result = LyricsRepository(
            providers = listOf(provider("empty") { null }),
            cache = cache
        ).findLyrics(request)
        val cached = cache.get(request)

        assertEquals(LyricsLookupResult.NotFound, result)
        assertNull(cached)
    }

    @Test
    fun presentationKeepsSampleLyricsAndReportsLookupStateAndSource() {
        val sample = correctTrack.copy(lines = listOf(LyricLine("Offline sample")))
        val loading = LyricsPresentationState.loading(sample)
        val fallback = LyricsPresentationState.fromResult(
            LyricsLookupResult.ProviderError(
                listOf(ProviderFailure("LRCLIB", "not configured", configurationMissing = true))
            ),
            sample
        )
        val providerLyrics = LyricsPresentationState.fromResult(
            LyricsLookupResult.Found(correctTrack, "LRCLIB"),
            sampleTrack = null
        )

        assertEquals(LyricsLoadStatus.LOADING, loading.status)
        assertEquals(LyricsDisplaySource.SAMPLE, loading.source)
        assertEquals("Offline sample", loading.track?.lines?.single()?.text)
        assertEquals(LyricsLoadStatus.PROVIDER_ERROR, fallback.status)
        assertEquals(LyricsDisplaySource.SAMPLE, fallback.source)
        assertTrue(fallback.message.contains("not configured"))
        assertEquals(LyricsDisplaySource.PROVIDER, providerLyrics.source)
        assertEquals("PROVIDER LYRICS", providerLyrics.source.label)
    }

    @Test
    fun presentationMovesFromLoadingToNotFoundAndErrorWithoutStayingLoading() {
        val loading = LyricsPresentationState.loading(sampleTrack = null)
        val notFound = LyricsPresentationState.fromResult(
            LyricsLookupResult.NotFound,
            sampleTrack = null
        )
        val error = LyricsPresentationState.fromResult(
            LyricsLookupResult.ProviderError(
                listOf(ProviderFailure("LRCLIB", "service unavailable", httpStatusCode = 503))
            ),
            sampleTrack = null
        )
        val unexpected = LyricsPresentationState.unexpectedFailure(
            sampleTrack = null,
            IllegalStateException("unexpected failure")
        )

        assertEquals(LyricsLoadStatus.LOADING, loading.status)
        assertEquals(LyricsDisplaySource.NONE, loading.source)
        assertEquals(LyricsLoadStatus.NOT_FOUND, notFound.status)
        assertTrue(notFound.message.contains("No provider lyrics"))
        assertEquals(LyricsDisplaySource.NONE, notFound.source)
        assertEquals(LyricsLoadStatus.PROVIDER_ERROR, error.status)
        assertTrue(error.message.contains("retry"))
        assertEquals(LyricsLoadStatus.PROVIDER_ERROR, unexpected.status)
        assertFalse(unexpected.message.contains("Looking for lyrics"))
    }

    @Test
    fun retryCanRecoverFromProviderFailureAndReachesSuccessState() = runBlocking {
        var attempts = 0
        val flakyProvider = provider("retry-provider") {
            attempts++
            if (attempts == 1) throw IllegalStateException("temporary error")
            correctTrack
        }
        val repository = LyricsRepository(
            providers = listOf(flakyProvider),
            cache = InMemoryLyricsCache()
        )

        val firstState = LyricsPresentationState.fromResult(
            repository.findLyrics(request),
            sampleTrack = null
        )
        val retryLoading = LyricsPresentationState.loading(sampleTrack = null)
        val retryState = LyricsPresentationState.fromResult(
            repository.findLyrics(request),
            sampleTrack = null
        )

        assertEquals(LyricsLoadStatus.PROVIDER_ERROR, firstState.status)
        assertEquals(LyricsLoadStatus.LOADING, retryLoading.status)
        assertEquals(LyricsLoadStatus.FOUND, retryState.status)
        assertEquals(LyricsDisplaySource.PROVIDER, retryState.source)
        assertEquals(2, attempts)
    }

    private fun provider(
        id: String,
        fetch: suspend () -> LyricsTrack?
    ) = object : LyricsProvider {
        override val providerId: String = id
        override suspend fun fetchLyrics(song: Song): LyricsTrack? = fetch()
    }
}
