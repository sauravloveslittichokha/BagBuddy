package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.LyricsTrack
import com.saurav.bagbuddy.data.Song

interface LyricsProvider {
    val providerId: String

    /**
     * Returns normalized source lyrics or null when the provider has no match.
     * Implementations must preserve source timing and must not infer missing times.
     */
    suspend fun fetchLyrics(song: Song): LyricsTrack?
}

class LyricsProviderNotConfiguredException(providerId: String) :
    IllegalStateException("$providerId network integration is not configured.")

class LiriQoLyricsProvider : LyricsProvider {
    override val providerId: String = "LiriQo"

    override suspend fun fetchLyrics(song: Song): LyricsTrack? {
        throw LyricsProviderNotConfiguredException(providerId)
    }
}

class LrclibLyricsProvider : LyricsProvider {
    override val providerId: String = "LRCLIB"

    override suspend fun fetchLyrics(song: Song): LyricsTrack? {
        throw LyricsProviderNotConfiguredException(providerId)
    }
}
