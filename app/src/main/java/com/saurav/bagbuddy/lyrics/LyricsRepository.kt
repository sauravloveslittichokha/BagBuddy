package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.LyricsTrack
import com.saurav.bagbuddy.data.LyricsSourceInfo
import com.saurav.bagbuddy.data.Song
import kotlinx.coroutines.CancellationException

sealed interface LyricsLookupResult {
    data class Found(
        val track: LyricsTrack,
        val providerId: String,
        val origin: LyricsResultOrigin = LyricsResultOrigin.PROVIDER,
        val cacheWarning: String? = null
    ) : LyricsLookupResult

    data object NotFound : LyricsLookupResult

    data class InvalidMatch(
        val providerIds: List<String>
    ) : LyricsLookupResult

    data class ProviderError(
        val failures: List<ProviderFailure>
    ) : LyricsLookupResult

    data object MissingMetadata : LyricsLookupResult
}

enum class LyricsResultOrigin {
    PROVIDER,
    CACHE
}

data class ProviderFailure(
    val providerId: String,
    val message: String,
    val configurationMissing: Boolean = false,
    val httpStatusCode: Int? = null,
    val retryAfterSeconds: Long? = null
)

class LyricsRepository(
    private val providers: List<LyricsProvider>,
    private val parser: LyricsParser = LyricsParser(),
    private val cache: LyricsCache = InMemoryLyricsCache()
) {
    suspend fun findLyrics(song: Song): LyricsLookupResult {
        if (!LyricsTrackMatcher.hasSearchMetadata(song)) {
            return LyricsLookupResult.MissingMetadata
        }

        val failures = mutableListOf<ProviderFailure>()
        val rejectedProviders = mutableListOf<String>()
        var bestCandidate: Candidate? = null

        try {
            cache.get(song)?.let { cached ->
                val normalizedTrack = parser.normalize(cached.track).withProvider(cached.providerId)
                if (normalizedTrack.lines.isNotEmpty() &&
                    LyricsTrackMatcher.matches(song, normalizedTrack.song)
                ) {
                    val cachedCandidate = Candidate(
                        track = normalizedTrack,
                        providerId = cached.providerId,
                        origin = LyricsResultOrigin.CACHE
                    )
                    bestCandidate = cachedCandidate

                    // Skip network work only when the cached timing is at least as
                    // rich as every configured provider says it may return.
                    val bestAdvertisedQuality = providers.maxOfOrNull {
                        it.capabilities.bestAdvertisedQuality
                    } ?: 0
                    if (cachedCandidate.quality >= bestAdvertisedQuality) {
                        return cachedCandidate.toLookupResult()
                    }
                }
                if (bestCandidate == null) cache.remove(song)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures += ProviderFailure(
                providerId = "lyrics-cache",
                message = failure.message?.takeIf(String::isNotBlank)
                    ?: failure::class.simpleName.orEmpty()
            )
        }

        for (provider in providers) {
            try {
                val sourceTrack = provider.fetchLyrics(song) ?: continue
                val normalizedTrack = parser.normalize(sourceTrack)
                if (normalizedTrack.lines.isEmpty() ||
                    !LyricsTrackMatcher.matches(song, normalizedTrack.song)
                ) {
                    rejectedProviders += provider.providerId
                    continue
                }

                val candidate = Candidate(
                    track = normalizedTrack.withProvider(provider.providerId),
                    providerId = provider.providerId,
                    origin = LyricsResultOrigin.PROVIDER
                )
                val currentBest = bestCandidate
                if (currentBest == null || candidate.quality > currentBest.quality) {
                    bestCandidate = candidate
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (notConfigured: LyricsProviderNotConfiguredException) {
                failures += ProviderFailure(
                    providerId = provider.providerId,
                    message = notConfigured.message.orEmpty(),
                    configurationMissing = true
                )
            } catch (httpFailure: LyricsHttpException) {
                failures += ProviderFailure(
                    providerId = provider.providerId,
                    message = httpFailure.message.orEmpty(),
                    httpStatusCode = httpFailure.statusCode,
                    retryAfterSeconds = httpFailure.retryAfterSeconds
                )
            } catch (failure: Exception) {
                failures += ProviderFailure(
                    providerId = provider.providerId,
                    message = failure.message?.takeIf(String::isNotBlank)
                        ?: failure::class.simpleName.orEmpty()
                )
            }
        }

        bestCandidate?.let { candidate ->
            if (candidate.origin == LyricsResultOrigin.PROVIDER) {
                val cacheWarning = try {
                    cache.put(song, CachedLyrics(candidate.track, candidate.providerId))
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failure.message?.takeIf(String::isNotBlank)
                        ?: failure::class.simpleName.orEmpty()
                }
                return candidate.toLookupResult(cacheWarning)
            }
            return candidate.toLookupResult()
        }

        return when {
            failures.isNotEmpty() -> LyricsLookupResult.ProviderError(failures)
            rejectedProviders.isNotEmpty() -> LyricsLookupResult.InvalidMatch(rejectedProviders)
            else -> LyricsLookupResult.NotFound
        }
    }

    private fun LyricsTrack.withProvider(providerId: String): LyricsTrack =
        if (sourceInfo == null) {
            copy(sourceInfo = LyricsSourceInfo(providerId))
        } else {
            this
        }

    private data class Candidate(
        val track: LyricsTrack,
        val providerId: String,
        val origin: LyricsResultOrigin
    ) {
        val quality: Int = track.timingQuality()

        fun toLookupResult(cacheWarning: String? = null) = LyricsLookupResult.Found(
            track = track,
            providerId = providerId,
            origin = origin,
            cacheWarning = cacheWarning
        )
    }
}
