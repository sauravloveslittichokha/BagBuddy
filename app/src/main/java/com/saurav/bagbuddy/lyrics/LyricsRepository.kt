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
    private val cache: LyricsCache = InMemoryLyricsCache(),
    private val diagnostics: LyricsDiagnostics = LyricsDiagnostics.NONE
) {
    suspend fun findLyrics(song: Song): LyricsLookupResult {
        val requestIdentity =
            "title=${song.title.asDiagnosticValue()} artist=${song.artist.asDiagnosticValue()}"
        diagnostics.record("lyrics lookup status=started $requestIdentity")
        if (!LyricsTrackMatcher.hasSearchMetadata(song)) {
            diagnostics.record("lyrics lookup status=missing_metadata $requestIdentity")
            return LyricsLookupResult.MissingMetadata
        }

        val failures = mutableListOf<ProviderFailure>()
        val rejectedProviders = mutableListOf<String>()
        var bestCandidate: Candidate? = null
        val checkedCapabilities = mutableMapOf<String, Int>()

        try {
            cache.get(song)?.let { cached ->
                val normalizedTrack = parser.normalize(cached.track).withProvider(cached.providerId)
                checkedCapabilities.putAll(cached.checkedProviderCapabilities.orEmpty())
                if (normalizedTrack.lines.isNotEmpty() &&
                    LyricsTrackMatcher.matches(song, normalizedTrack.song)
                ) {
                    val cachedCandidate = Candidate(
                        track = normalizedTrack,
                        providerId = cached.providerId,
                        origin = LyricsResultOrigin.CACHE
                    )
                    bestCandidate = cachedCandidate
                    diagnostics.record(
                        "lyrics cache status=hit provider=${cached.providerId} " +
                            "sync=${SyncLevelDetector.detect(normalizedTrack)} " +
                            "wordTiming=${SyncLevelDetector.wordTimingRejectionReason(normalizedTrack) ?: "valid"}"
                    )

                    // Skip network work only when the cached timing is at least as
                    // rich as every configured provider says it may return.
                    val bestAdvertisedQuality = providers.maxOfOrNull {
                        it.capabilities.bestAdvertisedQuality
                    } ?: 0
                    if (cachedCandidate.quality >= bestAdvertisedQuality) {
                        diagnostics.record(
                            "lyrics selection provider=${cachedCandidate.providerId} " +
                                "origin=cache quality=${cachedCandidate.quality}"
                        )
                        return cachedCandidate.toLookupResult()
                    }
                }
                if (bestCandidate == null) {
                    diagnostics.record("lyrics cache status=invalidated")
                    cache.remove(song)
                    checkedCapabilities.clear()
                }
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
            val providerQuality = provider.capabilities.bestAdvertisedQuality
            if (checkedCapabilities[provider.providerId] == providerQuality) {
                diagnostics.record("lyrics provider=${provider.providerId} status=skipped_cached_check")
                continue
            }

            try {
                diagnostics.record("lyrics provider=${provider.providerId} status=requested")
                val sourceTrack = provider.fetchLyrics(song)
                checkedCapabilities[provider.providerId] = providerQuality
                if (sourceTrack == null) {
                    diagnostics.record("lyrics provider=${provider.providerId} status=no_match")
                    continue
                }
                val normalizedTrack = parser.normalize(sourceTrack)
                if (normalizedTrack.lines.isEmpty() ||
                    !LyricsTrackMatcher.matches(song, normalizedTrack.song)
                ) {
                    rejectedProviders += provider.providerId
                    diagnostics.record(
                        "lyrics provider=${provider.providerId} status=invalid_match " +
                            "empty=${normalizedTrack.lines.isEmpty()}"
                    )
                    continue
                }

                val candidate = Candidate(
                    track = normalizedTrack.withProvider(provider.providerId),
                    providerId = provider.providerId,
                    origin = LyricsResultOrigin.PROVIDER
                )
                diagnostics.record(
                    "lyrics candidate provider=${candidate.providerId} " +
                        "sync=${SyncLevelDetector.detect(candidate.track)} " +
                        "quality=${candidate.quality} " +
                        "wordTiming=${SyncLevelDetector.wordTimingRejectionReason(candidate.track) ?: "valid"}"
                )
                val currentBest = bestCandidate
                if (currentBest == null || candidate.quality > currentBest.quality) {
                    bestCandidate = candidate
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (notConfigured: LyricsProviderNotConfiguredException) {
                diagnostics.record(
                    "lyrics provider=${provider.providerId} status=unconfigured"
                )
                failures += ProviderFailure(
                    providerId = provider.providerId,
                    message = notConfigured.message.orEmpty(),
                    configurationMissing = true
                )
            } catch (httpFailure: LyricsHttpException) {
                diagnostics.record(
                    "lyrics provider=${provider.providerId} status=http_error " +
                        "code=${httpFailure.statusCode}"
                )
                failures += ProviderFailure(
                    providerId = provider.providerId,
                    message = httpFailure.message.orEmpty(),
                    httpStatusCode = httpFailure.statusCode,
                    retryAfterSeconds = httpFailure.retryAfterSeconds
                )
            } catch (failure: Exception) {
                diagnostics.record(
                    "lyrics provider=${provider.providerId} status=error " +
                        "type=${failure::class.simpleName}"
                )
                failures += ProviderFailure(
                    providerId = provider.providerId,
                    message = failure.message?.takeIf(String::isNotBlank)
                        ?: failure::class.simpleName.orEmpty()
                )
            }
        }

        bestCandidate?.let { candidate ->
            diagnostics.record(
                "lyrics selection provider=${candidate.providerId} " +
                    "origin=${candidate.origin.name.lowercase()} quality=${candidate.quality} " +
                    "sync=${SyncLevelDetector.detect(candidate.track)}"
            )
            if (candidate.origin == LyricsResultOrigin.PROVIDER) {
                val cacheWarning = try {
                    cache.put(
                        song,
                        CachedLyrics(
                            candidate.track,
                            candidate.providerId,
                            checkedCapabilities.toMap()
                        )
                    )
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failure.message?.takeIf(String::isNotBlank)
                        ?: failure::class.simpleName.orEmpty()
                }
                return candidate.toLookupResult(cacheWarning)
            }
            val cacheWarning = try {
                cache.put(
                    song,
                    CachedLyrics(
                        candidate.track,
                        candidate.providerId,
                        checkedCapabilities.toMap()
                    )
                )
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure.message?.takeIf(String::isNotBlank)
                    ?: failure::class.simpleName.orEmpty()
            }
            return candidate.toLookupResult(cacheWarning)
        }

        return when {
            failures.isNotEmpty() -> {
                diagnostics.record("lyrics lookup status=provider_error failures=${failures.size}")
                LyricsLookupResult.ProviderError(failures)
            }
            rejectedProviders.isNotEmpty() -> {
                diagnostics.record(
                    "lyrics lookup status=invalid_match providers=${rejectedProviders.joinToString(",")}"
                )
                LyricsLookupResult.InvalidMatch(rejectedProviders)
            }
            else -> {
                diagnostics.record("lyrics lookup status=not_found")
                LyricsLookupResult.NotFound
            }
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
