package com.saurav.bagbuddy.data

data class LyricWord(
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val syllables: List<LyricSyllable> = emptyList()
)
