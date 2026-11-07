package com.saurav.bagbuddy.data

data class PlaybackState(
    val track: LyricsTrack,
    val trackIndex: Int,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false
)
