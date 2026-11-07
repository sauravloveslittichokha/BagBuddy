package com.saurav.bagbuddy.data

data class LyricLine(
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val words: List<LyricWord> = emptyList()
)
