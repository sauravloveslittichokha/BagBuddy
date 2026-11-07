package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.LyricLine
import com.saurav.bagbuddy.data.LyricWord
import com.saurav.bagbuddy.data.LyricsSyncLevel
import com.saurav.bagbuddy.data.LyricsTrack

data class LyricsFrame(
    val syncLevel: LyricsSyncLevel,
    val currentLineIndex: Int?,
    val currentLine: LyricLine?,
    val nextLine: LyricLine?,
    val activeWordIndex: Int? = null,
    val activeSyllableIndex: Int? = null
)

class LyricsSyncEngine {
    fun frameAt(
        track: LyricsTrack,
        positionMs: Long,
        requestedLevel: LyricsSyncLevel? = null
    ): LyricsFrame {
        val supported = SyncLevelDetector.supportedLevels(track)
        val level = requestedLevel?.takeIf { it in supported }
            ?: SyncLevelDetector.detect(track)
        if (track.lines.isEmpty()) return emptyFrame(level)

        val position = positionMs.coerceAtLeast(0L)
        return when (level) {
            LyricsSyncLevel.PLAIN -> plainFrame(track)
            LyricsSyncLevel.LINE -> {
                val index = activeLineIndex(track, position)
                frameForLine(track, level, index, position)
            }
            LyricsSyncLevel.WORD -> wordFrame(track, position, level)
            LyricsSyncLevel.SYLLABLE -> syllableFrame(track, position, level)
        }
    }

    private fun wordFrame(
        track: LyricsTrack,
        position: Long,
        level: LyricsSyncLevel
    ): LyricsFrame {
        val activeWord = track.lines.withIndex().firstNotNullOfOrNull { (lineIndex, line) ->
            line.words.withIndex().firstOrNull { (_, word) -> contains(word.startMs, word.endMs, position) }
                ?.let { (wordIndex, _) -> lineIndex to wordIndex }
        }
        val lineIndex = activeWord?.first ?: activeLineIndex(track, position)
            ?: latestWordLineIndex(track, position)
        return frameForLine(track, level, lineIndex, position, activeWord?.second)
    }

    private fun syllableFrame(
        track: LyricsTrack,
        position: Long,
        level: LyricsSyncLevel
    ): LyricsFrame {
        val active = track.lines.withIndex().firstNotNullOfOrNull { (lineIndex, line) ->
            line.words.withIndex().firstNotNullOfOrNull { (wordIndex, word) ->
                word.syllables.indexOfFirst { syllable ->
                    contains(syllable.startMs, syllable.endMs, position)
                }.takeIf { it >= 0 }?.let { syllableIndex ->
                    Triple(lineIndex, wordIndex, syllableIndex)
                }
            }
        }
        if (active != null) {
            return frameForLine(track, level, active.first, position, active.second, active.third)
        }

        val activeWord = findActiveWord(track, position)
        val lineIndex = activeWord?.first ?: activeLineIndex(track, position)
            ?: latestWordLineIndex(track, position)
        return frameForLine(track, level, lineIndex, position, activeWord?.second)
    }

    private fun findActiveWord(track: LyricsTrack, position: Long): Pair<Int, Int>? =
        track.lines.withIndex().firstNotNullOfOrNull { (lineIndex, line) ->
            line.words.indexOfFirst { contains(it.startMs, it.endMs, position) }
                .takeIf { it >= 0 }?.let { lineIndex to it }
        }

    private fun activeLineIndex(track: LyricsTrack, position: Long): Int? {
        val startedLine = track.lines.indexOfLast { line ->
            line.startMs != null && line.startMs <= position
        }
        if (startedLine < 0) return null

        val line = track.lines[startedLine]
        if (line.endMs != null && position >= line.endMs) return null
        val nextStart = track.lines.getOrNull(startedLine + 1)?.startMs
        if (line.endMs == null && nextStart != null && position >= nextStart) return null
        return startedLine
    }

    private fun latestWordLineIndex(track: LyricsTrack, position: Long): Int? {
        var latestLineIndex: Int? = null
        var latestStart = -1L
        track.lines.forEachIndexed { index, line ->
            line.words.forEach { word ->
                val start = word.startMs
                if (start != null && start <= position && start >= latestStart) {
                    latestStart = start
                    latestLineIndex = index
                }
            }
        }
        return latestLineIndex
    }

    private fun frameForLine(
        track: LyricsTrack,
        level: LyricsSyncLevel,
        lineIndex: Int?,
        position: Long,
        wordIndex: Int? = null,
        syllableIndex: Int? = null
    ): LyricsFrame {
        val nextIndex = when {
            lineIndex != null -> lineIndex + 1
            else -> track.lines.indexOfFirst { line ->
                line.startMs != null && line.startMs > position
            }.takeIf { it >= 0 }
        }
        return LyricsFrame(
            syncLevel = level,
            currentLineIndex = lineIndex,
            currentLine = lineIndex?.let(track.lines::getOrNull),
            nextLine = nextIndex?.let(track.lines::getOrNull),
            activeWordIndex = wordIndex,
            activeSyllableIndex = syllableIndex
        )
    }

    private fun plainFrame(track: LyricsTrack): LyricsFrame = LyricsFrame(
        syncLevel = LyricsSyncLevel.PLAIN,
        currentLineIndex = 0.takeIf { track.lines.isNotEmpty() },
        currentLine = track.lines.firstOrNull(),
        nextLine = track.lines.getOrNull(1)
    )

    private fun emptyFrame(level: LyricsSyncLevel) = LyricsFrame(
        syncLevel = level,
        currentLineIndex = null,
        currentLine = null,
        nextLine = null
    )

    private fun contains(startMs: Long?, endMs: Long?, position: Long): Boolean =
        startMs != null && endMs != null && position >= startMs && position < endMs
}
