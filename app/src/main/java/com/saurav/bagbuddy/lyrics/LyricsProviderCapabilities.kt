package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.LyricsSyncLevel
import com.saurav.bagbuddy.data.LyricsTrack

enum class LyricsTimingCapability(val quality: Int) {
    LINE(1),
    WORD(2),
    SYLLABLE(3)
}

/**
 * Describes a provider's possible output only. A capability is usable for a
 * particular result only when SyncLevelDetector validates that track's timings.
 */
data class LyricsProviderCapabilities(
    val timing: Set<LyricsTimingCapability>
) {
    val bestAdvertisedQuality: Int
        get() = timing.maxOfOrNull(LyricsTimingCapability::quality) ?: 0

    companion object {
        val LINE_ONLY = LyricsProviderCapabilities(setOf(LyricsTimingCapability.LINE))
        val LINE_AND_WORD = LyricsProviderCapabilities(
            setOf(LyricsTimingCapability.LINE, LyricsTimingCapability.WORD)
        )
    }
}

internal fun LyricsTrack.timingQuality(): Int =
    when (SyncLevelDetector.detect(this)) {
        LyricsSyncLevel.SYLLABLE -> LyricsTimingCapability.SYLLABLE.quality
        LyricsSyncLevel.WORD -> LyricsTimingCapability.WORD.quality
        LyricsSyncLevel.LINE -> LyricsTimingCapability.LINE.quality
        LyricsSyncLevel.PLAIN -> 0
    }
