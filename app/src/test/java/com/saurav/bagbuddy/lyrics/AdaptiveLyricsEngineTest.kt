package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.LyricLine
import com.saurav.bagbuddy.data.LyricSyllable
import com.saurav.bagbuddy.data.LyricWord
import com.saurav.bagbuddy.data.LyricsSyncLevel
import com.saurav.bagbuddy.data.LyricsTrack
import com.saurav.bagbuddy.data.Song
import com.saurav.bagbuddy.mock.SampleLyrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLyricsEngineTest {
    private val song = Song(
        id = "test-song",
        title = "Test Song",
        artist = "Test Artist",
        durationMs = 10_000L,
        artworkEmoji = "♪"
    )

    @Test
    fun detectsSyllableWordLineAndPlainLevelsInPriorityOrder() {
        assertEquals(
            listOf(
                LyricsSyncLevel.SYLLABLE,
                LyricsSyncLevel.WORD,
                LyricsSyncLevel.LINE,
                LyricsSyncLevel.PLAIN
            ),
            SyncLevelDetector.supportedLevels(SampleLyrics.tracks[0])
        )
        assertEquals(
            listOf(LyricsSyncLevel.WORD, LyricsSyncLevel.LINE, LyricsSyncLevel.PLAIN),
            SyncLevelDetector.supportedLevels(SampleLyrics.tracks[1])
        )
        assertEquals(
            listOf(LyricsSyncLevel.LINE, LyricsSyncLevel.PLAIN),
            SyncLevelDetector.supportedLevels(lineTrack())
        )
        assertEquals(
            listOf(LyricsSyncLevel.PLAIN),
            SyncLevelDetector.supportedLevels(plainTrack())
        )
    }

    @Test
    fun incompleteWordTimingFallsBackWithoutInventingTimestamps() {
        val source = lineTrack(
            lines = listOf(
                line(
                    "one two",
                    0L,
                    2_000L,
                    words = listOf(word("one", 0L, 800L), word("two", 800L, null))
                )
            )
        )

        assertEquals(LyricsSyncLevel.LINE, SyncLevelDetector.detect(source))
        assertNull(source.lines.single().words.last().endMs)
        assertEquals(
            "word_end_missing_or_invalid",
            SyncLevelDetector.wordTimingRejectionReason(source)
        )
    }

    @Test
    fun invalidAndOverlappingIntervalsAreNotReportedAsTimedLevels() {
        val invalidEnd = lineTrack(
            lines = listOf(
                line("bad end", 0L, 2_000L, listOf(word("bad", 500L, 400L)))
            )
        )
        val overlappingWords = lineTrack(
            lines = listOf(
                line("overlap", 0L, 3_000L, listOf(word("one", 0L, 1_500L), word("two", 1_000L, 2_000L)))
            )
        )
        val negativeLine = track(listOf(line("negative", -1L, 2_000L)))

        assertEquals(LyricsSyncLevel.LINE, SyncLevelDetector.detect(invalidEnd))
        assertEquals(LyricsSyncLevel.LINE, SyncLevelDetector.detect(overlappingWords))
        assertEquals(LyricsSyncLevel.PLAIN, SyncLevelDetector.detect(negativeLine))
        assertEquals(
            "word_end_missing_or_invalid",
            SyncLevelDetector.wordTimingRejectionReason(invalidEnd)
        )
        assertEquals(
            "word_intervals_unordered_or_overlapping",
            SyncLevelDetector.wordTimingRejectionReason(overlappingWords)
        )
    }

    @Test
    fun syllablesCanBeUsedOnlyWhenEverySyllableHasRealIntervals() {
        val syllableOnly = track(
            listOf(
                line(
                    "sunrise",
                    words = listOf(
                        LyricWord(
                            text = "sunrise",
                            syllables = listOf(
                                LyricSyllable("sun", 0L, 500L),
                                LyricSyllable("rise", 500L, 1_000L)
                            )
                        )
                    )
                )
            )
        )
        val partialSyllables = track(
            listOf(
                line(
                    "sunrise",
                    0L,
                    1_000L,
                    listOf(
                        LyricWord(
                            "sunrise", 0L, 1_000L,
                            listOf(LyricSyllable("sun", 0L, 500L), LyricSyllable("rise", 500L, null))
                        )
                    )
                )
            )
        )

        assertEquals(LyricsSyncLevel.SYLLABLE, SyncLevelDetector.detect(syllableOnly))
        assertEquals(
            listOf(LyricsSyncLevel.WORD, LyricsSyncLevel.LINE, LyricsSyncLevel.PLAIN),
            SyncLevelDetector.supportedLevels(partialSyllables)
        )
    }

    @Test
    fun parserKeepsSourceLineTimesAndDoesNotCreateEndOrWordTimes() {
        val parsed = LyricsParser().parseLrc(
            song,
            "[00:01.20]First line\n[00:03.5]Second line"
        )

        assertEquals(listOf(1_200L, 3_500L), parsed.lines.map { it.startMs })
        assertTrue(parsed.lines.all { it.endMs == null && it.words.isEmpty() })
        assertEquals(LyricsSyncLevel.LINE, SyncLevelDetector.detect(parsed))
    }

    @Test
    fun parserKeepsMalformedTimestampAsPlainLyric() {
        val parsed = LyricsParser().parseLrc(song, "[00:99.1]Keep these words")

        assertEquals("Keep these words", parsed.lines.single().text)
        assertNull(parsed.lines.single().startMs)
        assertEquals(LyricsSyncLevel.PLAIN, SyncLevelDetector.detect(parsed))
    }

    @Test
    fun parserDropsInvalidEndsButPreservesValidSourceStart() {
        val normalized = LyricsParser().normalize(
            lineTrack(
                lines = listOf(
                    line("line", 1_000L, 500L, listOf(word("word", -1L, 2_000L)))
                )
            )
        )

        assertEquals(1_000L, normalized.lines.single().startMs)
        assertNull(normalized.lines.single().endMs)
        assertNull(normalized.lines.single().words.single().startMs)
        assertNull(normalized.lines.single().words.single().endMs)
        assertEquals(LyricsSyncLevel.LINE, SyncLevelDetector.detect(normalized))
    }

    @Test
    fun syncEngineReturnsActiveWordSyllableAndNextLineFromPlaybackPosition() {
        val engine = LyricsSyncEngine()
        val syllableFrame = engine.frameAt(SampleLyrics.tracks[0], 800L)
        val wordFrame = engine.frameAt(SampleLyrics.tracks[1], 2_000L)

        assertEquals(LyricsSyncLevel.SYLLABLE, syllableFrame.syncLevel)
        assertEquals(0, syllableFrame.currentLineIndex)
        assertEquals(0, syllableFrame.activeWordIndex)
        assertEquals(1, syllableFrame.activeSyllableIndex)
        assertEquals("The night turns soft and blue", syllableFrame.nextLine?.text)

        assertEquals(LyricsSyncLevel.WORD, wordFrame.syncLevel)
        assertEquals(0, wordFrame.currentLineIndex)
        assertEquals(1, wordFrame.activeWordIndex)
        assertNull(wordFrame.activeSyllableIndex)
    }

    @Test
    fun lineEngineChangesAtNextSourceStartAndPlainEngineDoesNotPretendToSync() {
        val engine = LyricsSyncEngine()
        val timed = lineTrack()
        val nextLine = engine.frameAt(timed, 1_000L, LyricsSyncLevel.LINE)
        val plain = engine.frameAt(plainTrack(), 8_000L)

        assertEquals(1, nextLine.currentLineIndex)
        assertEquals("second", nextLine.currentLine?.text)
        assertEquals(LyricsSyncLevel.PLAIN, plain.syncLevel)
        assertEquals("plain words", plain.currentLine?.text)
        assertNull(plain.activeWordIndex)
    }

    @Test
    fun emptyLyricsReturnSafeEmptyFrame() {
        val frame = LyricsSyncEngine().frameAt(track(emptyList()), 1_000L)

        assertEquals(LyricsSyncLevel.PLAIN, frame.syncLevel)
        assertNull(frame.currentLine)
        assertNull(frame.nextLine)
    }

    @Test
    fun repositoryContinuesAfterProviderFailureAndReturnsNextMatch() = runBlocking {
        val repository = LyricsRepository(
            providers = listOf(
                fakeProvider("offline-failure") { throw IllegalStateException("offline") },
                fakeProvider("sample") {
                    SampleLyrics.tracks[1].copy(song = song)
                }
            )
        )

        val result = repository.findLyrics(song)

        assertTrue(result is LyricsLookupResult.Found)
        assertEquals("sample", (result as LyricsLookupResult.Found).providerId)
    }

    @Test
    fun repositoryReportsFailuresAndNotFoundSeparately() = runBlocking {
        val failed = LyricsRepository(
            providers = listOf(fakeProvider("failed") { throw IllegalStateException("request failed") })
        ).findLyrics(song)
        val notFound = LyricsRepository(
            providers = listOf(fakeProvider("empty") { null })
        ).findLyrics(song)

        assertEquals(
            "request failed",
            (failed as LyricsLookupResult.ProviderError).failures.single().message
        )
        assertEquals(LyricsLookupResult.NotFound, notFound)
    }

    private fun fakeProvider(
        id: String,
        fetch: suspend () -> LyricsTrack?
    ) = object : LyricsProvider {
        override val providerId: String = id
        override suspend fun fetchLyrics(song: Song): LyricsTrack? = fetch()
    }

    private fun lineTrack(lines: List<LyricLine> = listOf(
        line("first", 0L, 1_000L),
        line("second", 1_000L, 2_000L)
    )) = track(lines)

    private fun plainTrack() = track(listOf(LyricLine("plain words")))

    private fun track(lines: List<LyricLine>) = LyricsTrack(song, lines)

    private fun line(
        text: String,
        startMs: Long? = null,
        endMs: Long? = null,
        words: List<LyricWord> = emptyList()
    ) = LyricLine(text, startMs, endMs, words)

    private fun word(text: String, startMs: Long?, endMs: Long?) =
        LyricWord(text, startMs, endMs)
}
