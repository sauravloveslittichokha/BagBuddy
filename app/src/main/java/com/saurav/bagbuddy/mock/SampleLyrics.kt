package com.saurav.bagbuddy.mock

import com.saurav.bagbuddy.data.LyricLine
import com.saurav.bagbuddy.data.LyricSyllable
import com.saurav.bagbuddy.data.LyricWord
import com.saurav.bagbuddy.data.LyricsTrack
import com.saurav.bagbuddy.data.Song

// Original offline demo lyrics; these sample timings are not sourced from a music provider.
object SampleLyrics {
    val tracks = listOf(
        LyricsTrack(
            song = Song(
                id = "moonlit-pocket",
                title = "Moonlit Pocket",
                artist = "The Night Owls",
                durationMs = 28_000L,
                artworkEmoji = "🌙"
            ),
            lines = listOf(
                LyricLine(
                    text = "Little sparks are waking",
                    startMs = 0L,
                    endMs = 7_000L,
                    words = listOf(
                        LyricWord(
                            "Little", 0L, 1_400L,
                            listOf(syllable("Lit", 0L, 650L), syllable("tle", 650L, 1_400L))
                        ),
                        LyricWord("sparks", 1_400L, 2_700L, listOf(syllable("sparks", 1_400L, 2_700L))),
                        LyricWord("are", 2_700L, 3_500L, listOf(syllable("are", 2_700L, 3_500L))),
                        LyricWord(
                            "waking", 3_500L, 6_500L,
                            listOf(syllable("wak", 3_500L, 4_800L), syllable("ing", 4_800L, 6_500L))
                        )
                    )
                ),
                LyricLine(
                    text = "The night turns soft and blue",
                    startMs = 7_000L,
                    endMs = 14_000L,
                    words = listOf(
                        LyricWord("The", 7_000L, 7_700L, listOf(syllable("The", 7_000L, 7_700L))),
                        LyricWord("night", 7_700L, 9_000L, listOf(syllable("night", 7_700L, 9_000L))),
                        LyricWord("turns", 9_000L, 10_500L, listOf(syllable("turns", 9_000L, 10_500L))),
                        LyricWord("soft", 10_500L, 11_600L, listOf(syllable("soft", 10_500L, 11_600L))),
                        LyricWord("and", 11_600L, 12_300L, listOf(syllable("and", 11_600L, 12_300L))),
                        LyricWord("blue", 12_300L, 13_500L, listOf(syllable("blue", 12_300L, 13_500L)))
                    )
                ),
                LyricLine(
                    text = "Carry this small bright world",
                    startMs = 14_000L,
                    endMs = 21_000L,
                    words = listOf(
                        LyricWord(
                            "Carry", 14_000L, 15_400L,
                            listOf(syllable("Car", 14_000L, 14_700L), syllable("ry", 14_700L, 15_400L))
                        ),
                        LyricWord("this", 15_400L, 16_100L, listOf(syllable("this", 15_400L, 16_100L))),
                        LyricWord("small", 16_100L, 17_400L, listOf(syllable("small", 16_100L, 17_400L))),
                        LyricWord("bright", 17_400L, 18_700L, listOf(syllable("bright", 17_400L, 18_700L))),
                        LyricWord("world", 18_700L, 20_500L, listOf(syllable("world", 18_700L, 20_500L)))
                    )
                ),
                LyricLine(
                    text = "Wherever I go with you",
                    startMs = 21_000L,
                    endMs = 28_000L,
                    words = listOf(
                        LyricWord(
                            "Wherever", 21_000L, 23_800L,
                            listOf(
                                syllable("Where", 21_000L, 22_200L),
                                syllable("ev", 22_200L, 23_000L),
                                syllable("er", 23_000L, 23_800L)
                            )
                        ),
                        LyricWord("I", 23_800L, 24_300L, listOf(syllable("I", 23_800L, 24_300L))),
                        LyricWord("go", 24_300L, 25_300L, listOf(syllable("go", 24_300L, 25_300L))),
                        LyricWord("with", 25_300L, 26_200L, listOf(syllable("with", 25_300L, 26_200L))),
                        LyricWord("you", 26_200L, 27_400L, listOf(syllable("you", 26_200L, 27_400L)))
                    )
                )
            )
        ),
        LyricsTrack(
            song = Song(
                id = "slow-sunday",
                title = "Slow Sunday",
                artist = "Paper Planets",
                durationMs = 24_000L,
                artworkEmoji = "☁️"
            ),
            lines = listOf(
                line("Windows catch the morning", 0L, 6_000L, listOf(
                    word("Windows", 0L, 1_800L), word("catch", 1_800L, 2_700L),
                    word("the", 2_700L, 3_200L), word("morning", 3_200L, 5_500L)
                )),
                line("Tea is getting cold", 6_000L, 12_000L, listOf(
                    word("Tea", 6_000L, 7_200L), word("is", 7_200L, 7_800L),
                    word("getting", 7_800L, 9_700L), word("cold", 9_700L, 11_400L)
                )),
                line("We have nowhere else to be", 12_000L, 18_000L, listOf(
                    word("We", 12_000L, 12_600L), word("have", 12_600L, 13_500L),
                    word("nowhere", 13_500L, 15_300L), word("else", 15_300L, 16_100L),
                    word("to", 16_100L, 16_500L), word("be", 16_500L, 17_500L)
                )),
                line("Let the quiet take its time", 18_000L, 24_000L, listOf(
                    word("Let", 18_000L, 18_600L), word("the", 18_600L, 19_100L),
                    word("quiet", 19_100L, 20_500L), word("take", 20_500L, 21_400L),
                    word("its", 21_400L, 22_000L), word("time", 22_000L, 23_500L)
                ))
            )
        )
    )

    private fun syllable(text: String, startMs: Long, endMs: Long) =
        LyricSyllable(text, startMs, endMs)

    private fun word(text: String, startMs: Long, endMs: Long) =
        LyricWord(text, startMs, endMs)

    private fun line(text: String, startMs: Long, endMs: Long, words: List<LyricWord>) =
        LyricLine(text, startMs, endMs, words)
}
