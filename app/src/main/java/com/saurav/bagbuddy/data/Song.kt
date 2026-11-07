package com.saurav.bagbuddy.data

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artworkEmoji: String,
    val album: String = ""
)
