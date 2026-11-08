package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.LyricLine
import com.saurav.bagbuddy.data.LyricWord
import com.saurav.bagbuddy.data.LyricsTrack
import com.saurav.bagbuddy.data.Song
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.StringReader

data class LyricsFileParseReport(
    val track: LyricsTrack?,
    val reason: String,
    val lineCount: Int = 0,
    val wordCount: Int = 0,
    val fullyTimedWordCount: Int = 0,
    val wordTextMismatchCount: Int = 0
)

/**
 * Reads the current Lyricsfile 1.0 draft. Source word boundaries are kept as
 * provided; incomplete intervals remain incomplete so sync detection rejects
 * WORD rather than inferring the missing values.
 */
class LyricsFileParser {
    private val yaml = Yaml(
        LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            nestingDepthLimit = MAX_NESTING_DEPTH
            codePointLimit = MAX_DOCUMENT_LENGTH
        }
    )

    fun parse(song: Song, source: String): LyricsTrack? =
        parseWithReport(song, source).track

    fun parseWithReport(song: Song, source: String): LyricsFileParseReport {
        if (source.isBlank()) return failure("empty_document")
        if (source.length > MAX_DOCUMENT_LENGTH) return failure("document_too_large")
        val documents = try {
            yaml.composeAll(StringReader(source)).toList()
        } catch (_: YAMLException) {
            return failure("malformed_yaml")
        }
        val root = documents.singleOrNull()
            ?.takeIf(::usesStandardYamlTags)
            ?.asStringMap()
            ?: return failure("invalid_root_or_yaml_tags")

        if (root["version"].asScalar() != SUPPORTED_VERSION) {
            return failure("unsupported_version")
        }
        val metadata = root["metadata"]?.asStringMap()
            ?: return failure("missing_metadata")
        if (metadata["title"].asScalar().isNullOrBlank() ||
            metadata["artist"].asScalar().isNullOrBlank()
        ) return failure("incomplete_metadata")

        val linesNode = root["lines"]
        val lines = mutableListOf<LyricLine>()
        var wordTextMismatchCount = 0
        when {
            linesNode == null || linesNode.asScalar() == "null" -> Unit
            else -> {
                val sequence = linesNode.asSequence()
                    ?: return failure("invalid_lines_structure")
                for (lineNode in sequence) {
                    val lineMap = lineNode.asStringMap()
                        ?: return failure("invalid_line_structure")
                    val text = lineMap["text"].asScalar()
                        ?: return failure("line_missing_text")
                    if (text.isBlank()) continue
                    val parsedLine = parseLine(lineNode, text)
                        ?: return failure("invalid_line_or_word_structure")
                    lines += parsedLine.line
                    if (parsedLine.wordTextMismatch) wordTextMismatchCount++
                }
            }
        }

        val plainText = root["plain"].asScalar()
        val outputLines = if (lines.isNotEmpty()) {
            lines.toList()
        } else {
            plainText.orEmpty().lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(::LyricLine)
                .toList()
        }
        if (outputLines.isEmpty()) return failure("no_lyric_lines")

        val track = LyricsTrack(song = song, lines = outputLines)
        val words = outputLines.flatMap(LyricLine::words)
        return LyricsFileParseReport(
            track = track,
            reason = when {
                words.isNotEmpty() -> "parsed"
                wordTextMismatchCount > 0 -> "word_text_mismatch"
                else -> "parsed_without_word_segments"
            },
            lineCount = outputLines.size,
            wordCount = words.size,
            fullyTimedWordCount = words.count { it.startMs != null && it.endMs != null },
            wordTextMismatchCount = wordTextMismatchCount
        )
    }

    private fun parseLine(node: Node, text: String): ParsedLine? {
        val map = node.asStringMap() ?: return null

        val wordsNode = map["words"]
        val wordNodes = when {
            wordsNode == null || wordsNode.asScalar() == "null" -> emptyList()
            else -> wordsNode.asSequence() ?: return null
        }
        val parsedWords = wordNodes.mapNotNull(::parseWord)
        if (parsedWords.size != wordNodes.size) return null
        val matchedWords = parsedWords.reconstructText(text)

        return ParsedLine(
            line = LyricLine(
                text = text,
                startMs = map["start_ms"].asNonNegativeLong(),
                endMs = map["end_ms"].asNonNegativeLong(),
                words = matchedWords.orEmpty()
            ),
            wordTextMismatch = parsedWords.isNotEmpty() && matchedWords == null
        )
    }

    private fun List<LyricWord>.reconstructText(lineText: String): List<LyricWord>? {
        if (isEmpty()) return emptyList()
        if (joinToString(separator = "") { it.text } == lineText) return this

        var cursor = 0
        val reconstructed = mapNotNull { word ->
            val token = word.text.trim()
            if (token.isEmpty()) return@mapNotNull null
            val tokenStart = lineText.indexOf(token, cursor)
            if (tokenStart < cursor ||
                lineText.substring(cursor, tokenStart).any { !it.isWhitespace() }
            ) return null
            val value = lineText.substring(cursor, tokenStart) + token
            cursor = tokenStart + token.length
            word.copy(text = value)
        }
        if (reconstructed.size != size ||
            lineText.substring(cursor).any { !it.isWhitespace() }
        ) return null

        val last = reconstructed.lastOrNull() ?: return null
        val withTrailingText = reconstructed.dropLast(1) +
            last.copy(text = last.text + lineText.substring(cursor))
        return withTrailingText.takeIf { words ->
            words.joinToString(separator = "") { it.text } == lineText
        }
    }

    private data class ParsedLine(
        val line: LyricLine,
        val wordTextMismatch: Boolean
    )

    private fun parseWord(node: Node): LyricWord? {
        val map = node.asStringMap() ?: return null
        val text = map["text"].asScalar() ?: return null
        if (text.isBlank()) return null

        return LyricWord(
            text = text,
            startMs = map["start_ms"].asNonNegativeLong(),
            endMs = map["end_ms"].asNonNegativeLong()
        )
    }

    private fun Node?.asScalar(): String? = (this as? ScalarNode)?.value

    private fun Node?.asSequence(): List<Node>? = (this as? SequenceNode)?.value

    private fun Node?.asStringMap(): Map<String, Node>? {
        val mapping = this as? MappingNode ?: return null
        val fields = linkedMapOf<String, Node>()
        for (tuple: NodeTuple in mapping.value) {
            val key = tuple.keyNode.asScalar() ?: return null
            if (fields.put(key, tuple.valueNode) != null) return null
        }
        return fields
    }

    private fun Node?.asNonNegativeLong(): Long? =
        asScalar()?.toLongOrNull()?.takeIf { it >= 0L }

    private fun failure(reason: String) = LyricsFileParseReport(
        track = null,
        reason = reason
    )

    private fun usesStandardYamlTags(node: Node): Boolean {
        if (!node.tag.value.startsWith(STANDARD_TAG_PREFIX)) return false
        return when (node) {
            is MappingNode -> node.value.all { tuple ->
                usesStandardYamlTags(tuple.keyNode) && usesStandardYamlTags(tuple.valueNode)
            }
            is SequenceNode -> node.value.all(::usesStandardYamlTags)
            is ScalarNode -> true
            else -> false
        }
    }

    private companion object {
        const val SUPPORTED_VERSION = "1.0"
        const val MAX_DOCUMENT_LENGTH = 1_000_000
        const val MAX_NESTING_DEPTH = 20
        const val STANDARD_TAG_PREFIX = "tag:yaml.org,2002:"
    }
}
