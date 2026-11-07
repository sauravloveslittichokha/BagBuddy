package com.saurav.bagbuddy.data

data class LyricsTrack(
    val song: Song,
    val lines: List<LyricLine>,
    val sourceInfo: LyricsSourceInfo? = null
)

data class LyricsSourceInfo(
    val providerId: String,
    val providerName: String? = null,
    val upstreamProvider: String? = null,
    val lyricsSource: String? = null
)
