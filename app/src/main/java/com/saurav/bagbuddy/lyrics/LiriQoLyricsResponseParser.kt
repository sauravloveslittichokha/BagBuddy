package com.saurav.bagbuddy.lyrics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.saurav.bagbuddy.data.LyricLine
import com.saurav.bagbuddy.data.LyricWord
import com.saurav.bagbuddy.data.LyricsSourceInfo
import com.saurav.bagbuddy.data.LyricsTrack
import com.saurav.bagbuddy.data.Song

/**
 * Parsing boundary for documented LiriQo JSON responses. It performs no
 * networking; a provider can only be enabled after service-use terms are clear.
 */
interface LiriQoLyricsAdapter {
    fun parseResponse(song: Song, responseBody: String): LyricsTrack?
}

class LiriQoLyricsResponseParser(
    private val normalizer: LyricsParser = LyricsParser()
) : LiriQoLyricsAdapter {
    override fun parseResponse(song: Song, responseBody: String): LyricsTrack? {
        val response = try {
            JsonParser.parseString(responseBody).takeIf { it.isJsonObject }?.asJsonObject
        } catch (failure: JsonSyntaxException) {
            throw LyricsResponseException("LiriQo returned invalid JSON.", failure)
        } ?: throw LyricsResponseException("LiriQo returned an unexpected response.")

        if (response.has("error") && !response.get("error").isJsonNull) {
            throw LyricsResponseException("LiriQo returned an error: ${response.get("error")}")
        }

        val metadata = response.objectOrNull("metadata")
            ?: throw LyricsResponseException("LiriQo response is missing track metadata.")
        val title = metadata.stringOrNull("title")?.trim().orEmpty()
        val artist = metadata.stringOrNull("artist")?.trim().orEmpty()
        if (title.isEmpty() || artist.isEmpty()) return null

        val durationMs = metadata.numberOrNull("duration")
            ?.takeIf { it > 0.0 && it <= 3_600.0 }
            ?.let { (it * 1_000.0).toLong() }
            ?: 0L
        val resultSong = Song(
            id = metadata.stringOrNull("isrc") ?: song.id,
            title = title,
            artist = artist,
            album = metadata.stringOrNull("album").orEmpty(),
            durationMs = durationMs,
            artworkEmoji = song.artworkEmoji
        )
        val tracks = response.get("tracks")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: throw LyricsResponseException("LiriQo response is missing its tracks array.")

        return tracks.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            parseTrack(resultSong, element.asJsonObject)
        }.map(normalizer::normalize)
            .maxByOrNull { it.timingQuality() }
    }

    private fun parseTrack(song: Song, track: JsonObject): LyricsTrack? {
        val timed = track.get("timed")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return null
        val lines = timed.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val line = element.asJsonObject
            val text = line.stringOrNull("text")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val words = line.get("words")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { wordElement ->
                    if (!wordElement.isJsonObject) return@mapNotNull null
                    val word = wordElement.asJsonObject
                    val wordText = word.stringOrNull("text")
                        ?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    LyricWord(
                        text = wordText,
                        startMs = word.numberOrNull("start")?.toLongExactOrNull(),
                        endMs = word.numberOrNull("end")?.toLongExactOrNull()
                    )
                }
                .orEmpty()
            LyricLine(
                text = text,
                startMs = line.numberOrNull("start")?.toLongExactOrNull(),
                endMs = line.numberOrNull("end")?.toLongExactOrNull(),
                words = words
            )
        }
        if (lines.isEmpty()) return null

        return LyricsTrack(
            song = song,
            lines = lines,
            sourceInfo = LyricsSourceInfo(
                providerId = "LiriQo",
                providerName = track.stringOrNull("provider"),
                upstreamProvider = track.stringOrNull("upstreamProvider"),
                lyricsSource = track.stringOrNull("lyricsSource")
            )
        )
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.numberOrNull(name: String): Double? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun Double.toLongExactOrNull(): Long? =
        takeIf {
            isFinite() && this >= 0.0 && this <= Long.MAX_VALUE.toDouble() &&
                this % 1.0 == 0.0
        }
            ?.toLong()
}
