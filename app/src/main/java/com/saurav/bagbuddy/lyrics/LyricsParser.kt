package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.LyricLine
import com.saurav.bagbuddy.data.LyricSyllable
import com.saurav.bagbuddy.data.LyricWord
import com.saurav.bagbuddy.data.LyricsTrack
import com.saurav.bagbuddy.data.Song

class LyricsParser {
    fun normalize(track: LyricsTrack): LyricsTrack = track.copy(
        lines = track.lines
            .filter { it.text.isNotBlank() }
            .map { line ->
                val start = validStart(line.startMs)
                val end = validEnd(start, line.endMs)
                line.copy(
                    startMs = start,
                    endMs = end,
                    words = line.words
                        .filter { it.text.isNotBlank() }
                        .map { word ->
                            val wordStart = validStart(word.startMs)
                            val wordEnd = validEnd(wordStart, word.endMs)
                            word.copy(
                                startMs = wordStart,
                                endMs = wordEnd,
                                syllables = word.syllables
                                    .filter { it.text.isNotBlank() }
                                    .map { syllable ->
                                        val syllableStart = validStart(syllable.startMs)
                                        syllable.copy(
                                            startMs = syllableStart,
                                            endMs = validEnd(syllableStart, syllable.endMs)
                                        )
                                    }
                            )
                        }
                )
            }
    )

    /**
     * Parses standard LRC line timestamps. LRC supplies line start times only;
     * this parser intentionally leaves line ends and all word timings unset.
     */
    fun parseLrc(song: Song, source: String): LyricsTrack {
        val lines = source.lineSequence()
            .flatMap { parseLrcLine(it).asSequence() }
            .toList()
        return normalize(LyricsTrack(song = song, lines = lines))
    }

    private fun parseLrcLine(sourceLine: String): List<LyricLine> {
        val line = sourceLine.trim()
        if (line.isEmpty()) return emptyList()

        val tags = mutableListOf<String>()
        var contentStart = 0
        while (contentStart < line.length && line[contentStart] == '[') {
            val closingBracket = line.indexOf(']', startIndex = contentStart + 1)
            if (closingBracket < 0) break
            tags += line.substring(contentStart + 1, closingBracket)
            contentStart = closingBracket + 1
        }

        if (tags.isEmpty()) return listOf(LyricLine(text = line))
        val text = line.substring(contentStart).trim()
        if (tags.all { it.matches(Regex("^[a-zA-Z]{2}:.*$")) }) return emptyList()

        val timestamps = tags.mapNotNull(::parseTimestamp)
        if (timestamps.isEmpty()) {
            return text.takeIf(String::isNotBlank)?.let { listOf(LyricLine(text = it)) }
                ?: emptyList()
        }

        return timestamps.map { timestamp ->
            LyricLine(text = text, startMs = timestamp)
        }
    }

    private fun parseTimestamp(tag: String): Long? {
        val match = TIMESTAMP.matchEntire(tag) ?: return null
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toIntOrNull() ?: return null
        if (seconds !in 0..59) return null

        val fraction = match.groupValues[3]
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            else -> fraction.toLong()
        }
        return (minutes * 60L + seconds) * 1_000L + fractionMs
    }

    private fun validStart(startMs: Long?): Long? = startMs?.takeIf { it >= 0L }

    private fun validEnd(startMs: Long?, endMs: Long?): Long? =
        endMs?.takeIf { startMs != null && it > startMs }

    private companion object {
        val TIMESTAMP = Regex("""(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?""")
    }
}
