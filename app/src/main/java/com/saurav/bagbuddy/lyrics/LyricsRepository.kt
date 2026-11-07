package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.LyricsTrack
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

        try {
            cache.get(song)?.let { cached ->
                val normalizedTrack = parser.normalize(cached.track)
                if (normalizedTrack.lines.isNotEmpty() &&
                    LyricsTrackMatcher.matches(song, normalizedTrack.song)
                ) {
                    return LyricsLookupResult.Found(
                        track = normalizedTrack,
                        providerId = cached.providerId,
                        origin = LyricsResultOrigin.CACHE
                    )
                }
                cache.remove(song)
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

                val cacheWarning = try {
                    cache.put(song, CachedLyrics(normalizedTrack, provider.providerId))
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failure.message?.takeIf(String::isNotBlank)
                        ?: failure::class.simpleName.orEmpty()
                }
                return LyricsLookupResult.Found(
                    track = normalizedTrack,
                    providerId = provider.providerId,
                    cacheWarning = cacheWarning
                )
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

        return when {
            failures.isNotEmpty() -> LyricsLookupResult.ProviderError(failures)
            rejectedProviders.isNotEmpty() -> LyricsLookupResult.InvalidMatch(rejectedProviders)
            else -> LyricsLookupResult.NotFound
        }
    }
}
