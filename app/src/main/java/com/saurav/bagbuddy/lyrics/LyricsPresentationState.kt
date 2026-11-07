package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.LyricsTrack

enum class LyricsLoadStatus {
    LOADING,
    FOUND,
    NOT_FOUND,
    INVALID_MATCH,
    PROVIDER_ERROR,
    MISSING_METADATA
}

enum class LyricsDisplaySource(val label: String) {
    SAMPLE("SAMPLE LYRICS"),
    PROVIDER("PROVIDER LYRICS"),
    CACHE("CACHED LYRICS"),
    NONE("LYRICS UNAVAILABLE")
}

data class LyricsPresentationState(
    val status: LyricsLoadStatus,
    val track: LyricsTrack?,
    val source: LyricsDisplaySource,
    val message: String
) {
    companion object {
        fun loading(sampleTrack: LyricsTrack?): LyricsPresentationState =
            LyricsPresentationState(
                status = LyricsLoadStatus.LOADING,
                track = sampleTrack,
                source = sampleTrack?.let { LyricsDisplaySource.SAMPLE }
                    ?: LyricsDisplaySource.NONE,
                message = "Looking for lyrics..."
            )

        fun fromResult(
            result: LyricsLookupResult,
            sampleTrack: LyricsTrack?
        ): LyricsPresentationState = when (result) {
            is LyricsLookupResult.Found -> LyricsPresentationState(
                status = LyricsLoadStatus.FOUND,
                track = result.track,
                source = when (result.origin) {
                    LyricsResultOrigin.PROVIDER -> LyricsDisplaySource.PROVIDER
                    LyricsResultOrigin.CACHE -> LyricsDisplaySource.CACHE
                },
                message = result.cacheWarning?.let {
                    "Lyrics loaded, but offline caching failed: $it"
                }.orEmpty()
            )

            LyricsLookupResult.NotFound -> fallback(
                LyricsLoadStatus.NOT_FOUND,
                sampleTrack,
                "No provider lyrics were found for this track."
            )

            is LyricsLookupResult.InvalidMatch -> fallback(
                LyricsLoadStatus.INVALID_MATCH,
                sampleTrack,
                "A provider result did not match this track and was rejected."
            )

            is LyricsLookupResult.ProviderError -> {
                val onlyConfigurationErrors =
                    result.failures.isNotEmpty() && result.failures.all { it.configurationMissing }
                val rateLimitFailure = result.failures.firstOrNull {
                    it.httpStatusCode == 429
                }
                fallback(
                    LyricsLoadStatus.PROVIDER_ERROR,
                    sampleTrack,
                    if (onlyConfigurationErrors) {
                        "Lyrics providers are not configured. See the setup instructions."
                    } else if (rateLimitFailure != null) {
                        "LRCLIB rate limited this request. Try again in " +
                            "${rateLimitFailure.retryAfterSeconds ?: 60} seconds."
                    } else {
                        "Lyrics lookup failed. You can retry when a provider is available."
                    }
                )
            }

            LyricsLookupResult.MissingMetadata -> fallback(
                LyricsLoadStatus.MISSING_METADATA,
                sampleTrack,
                "A title and artist are needed to search for lyrics."
            )
        }

        fun unexpectedFailure(
            sampleTrack: LyricsTrack?,
            failure: Exception
        ): LyricsPresentationState = fallback(
            LyricsLoadStatus.PROVIDER_ERROR,
            sampleTrack,
            failure.message?.takeIf(String::isNotBlank)
                ?.let { "Lyrics lookup failed: $it" }
                ?: "Lyrics lookup failed. You can retry."
        )

        private fun fallback(
            status: LyricsLoadStatus,
            sampleTrack: LyricsTrack?,
            message: String
        ) = LyricsPresentationState(
            status = status,
            track = sampleTrack,
            source = sampleTrack?.let { LyricsDisplaySource.SAMPLE }
                ?: LyricsDisplaySource.NONE,
            message = message
        )
    }
}
