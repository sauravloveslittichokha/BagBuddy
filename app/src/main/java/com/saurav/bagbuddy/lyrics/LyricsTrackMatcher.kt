package com.saurav.bagbuddy.lyrics

import com.saurav.bagbuddy.data.Song
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object LyricsTrackMatcher {
    private val unavailableMetadata = setOf(
        "title unavailable",
        "artist unavailable",
        "unknown",
        "unknown title",
        "unknown artist"
    )

    fun hasSearchMetadata(song: Song): Boolean =
        meaningful(song.title) != null && meaningful(song.artist) != null

    fun matches(request: Song, candidate: Song): Boolean {
        val requestedTitle = meaningful(request.title) ?: return false
        val requestedArtist = meaningful(request.artist) ?: return false
        val candidateTitle = meaningful(candidate.title) ?: return false
        val candidateArtist = meaningful(candidate.artist) ?: return false
        if (normalize(requestedTitle) != normalize(candidateTitle) ||
            normalize(requestedArtist) != normalize(candidateArtist)
        ) return false

        val requestedDuration = request.durationMs.takeIf { it > 0L }
        val candidateDuration = candidate.durationMs.takeIf { it > 0L }
        if (requestedDuration != null && candidateDuration != null) {
            if (abs(requestedDuration - candidateDuration) > MAX_DURATION_DIFFERENCE_MS) {
                return false
            }
        }

        val requestedAlbum = meaningful(request.album)
        val candidateAlbum = meaningful(candidate.album)
        if (requestedAlbum != null && candidateAlbum != null &&
            normalize(requestedAlbum) != normalize(candidateAlbum) &&
            (requestedDuration == null || candidateDuration == null ||
                abs(requestedDuration - candidateDuration) > 5_000L)
        ) return false

        return true
    }

    private fun meaningful(value: String): String? =
        value.trim().takeIf { it.isNotEmpty() && normalize(it) !in unavailableMetadata }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private const val MAX_DURATION_DIFFERENCE_MS = 2_000L
}
