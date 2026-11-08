package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.LyricLine
import com.saurav.bagbuddy.data.LyricSyllable
import com.saurav.bagbuddy.data.LyricWord
import com.saurav.bagbuddy.data.LyricsSyncLevel
import com.saurav.bagbuddy.data.LyricsTrack

object SyncLevelDetector {
    fun detect(track: LyricsTrack): LyricsSyncLevel =
        supportedLevels(track).first()

    fun wordTimingRejectionReason(track: LyricsTrack): String? =
        wordTimingRejectionReason(track.lines)

    private fun wordTimingRejectionReason(lines: List<LyricLine>): String? {
        if (lines.isEmpty()) return "no_lines"
        if (lines.any { it.words.isEmpty() }) return "line_without_word_segments"

        val words = lines.flatMap(LyricLine::words)
        if (words.any { !isValidStart(it.startMs) }) return "word_start_missing_or_invalid"
        if (words.any { word ->
                val start = word.startMs
                val end = word.endMs
                start == null || end == null || end <= start
            }
        ) return "word_end_missing_or_invalid"
        if (!orderedWithoutOverlap(words.map { it.startMs to it.endMs })) {
            return "word_intervals_unordered_or_overlapping"
        }
        if (lines.any { line ->
                line.words.any { word ->
                    (line.startMs != null && word.startMs!! < line.startMs) ||
                        (line.endMs != null && word.endMs!! > line.endMs)
                }
            }
        ) return "word_outside_line_interval"

        return null
    }

    fun supportedLevels(track: LyricsTrack): List<LyricsSyncLevel> {
        val lines = track.lines
        if (lines.isEmpty()) return listOf(LyricsSyncLevel.PLAIN)

        val words = lines.flatMap(LyricLine::words)
        val syllables = words.flatMap(LyricWord::syllables)
        val lineTimed = supportsLines(lines)
        val wordTimed = supportsWords(lines, words)
        val syllableTimed = supportsSyllables(lines, words, syllables)

        return buildList {
            if (syllableTimed) add(LyricsSyncLevel.SYLLABLE)
            if (wordTimed) add(LyricsSyncLevel.WORD)
            if (lineTimed) add(LyricsSyncLevel.LINE)
            add(LyricsSyncLevel.PLAIN)
        }
    }

    private fun supportsLines(lines: List<LyricLine>): Boolean =
        lines.all { it.text.isNotBlank() && isValidStart(it.startMs) } &&
            orderedWithoutOverlap(
                lines.map { it.startMs!! to it.endMs },
                allowMissingEnd = true
            )

    private fun supportsWords(lines: List<LyricLine>, words: List<LyricWord>): Boolean {
        if (words.isEmpty()) return false
        return wordTimingRejectionReason(lines) == null
    }

    private fun supportsSyllables(
        lines: List<LyricLine>,
        words: List<LyricWord>,
        syllables: List<LyricSyllable>
    ): Boolean {
        if (words.isEmpty() || syllables.isEmpty() ||
            lines.any { it.words.isEmpty() } ||
            words.any { it.syllables.isEmpty() }
        ) return false
        if (!orderedWithoutOverlap(syllables.map { it.startMs to it.endMs })) return false

        return words.all { word ->
            val wordStart = word.startMs
            val wordEnd = word.endMs
            word.syllables.all { syllable ->
                val start = syllable.startMs!!
                val end = syllable.endMs!!
                (wordStart == null || start >= wordStart) &&
                    (wordEnd == null || end <= wordEnd)
            }
        }
    }

    private fun orderedWithoutOverlap(
        intervals: List<Pair<Long?, Long?>>,
        allowMissingEnd: Boolean = false
    ): Boolean {
        var previousStart = -1L
        var previousEnd: Long? = null

        for ((start, end) in intervals) {
            if (!isValidStart(start) ||
                (end != null && (end <= start!! || end < 0L)) ||
                (!allowMissingEnd && end == null)
            ) return false

            val currentStart = start!!
            if (currentStart < previousStart ||
                (previousEnd != null && currentStart < previousEnd)
            ) return false

            previousStart = currentStart
            previousEnd = end
        }
        return true
    }

    private fun isValidStart(startMs: Long?): Boolean = startMs != null && startMs >= 0L
}
