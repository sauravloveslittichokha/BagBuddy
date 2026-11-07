package com.saurav.bagbuddy.mock

import com.saurav.bagbuddy.data.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MockMusicPlayer {
    private val tracks = SampleLyrics.tracks
    private val _state = MutableStateFlow(
        PlaybackState(track = tracks.first(), trackIndex = 0)
    )
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun playPause() {
        _state.value = _state.value.copy(isPlaying = !_state.value.isPlaying)
    }

    fun next() {
        setTrack((_state.value.trackIndex + 1) % tracks.size)
    }

    fun previous() {
        val current = _state.value
        if (current.positionMs > 3_000L) {
            _state.value = current.copy(positionMs = 0L)
        } else {
            setTrack((current.trackIndex - 1 + tracks.size) % tracks.size)
        }
    }

    fun seekTo(positionMs: Long) {
        val current = _state.value
        _state.value = current.copy(
            positionMs = positionMs.coerceIn(0L, current.track.song.durationMs)
        )
    }

    fun advanceBy(elapsedMs: Long) {
        val current = _state.value
        if (!current.isPlaying || elapsedMs <= 0L) return

        val newPosition = current.positionMs + elapsedMs
        if (newPosition >= current.track.song.durationMs) {
            setTrack((current.trackIndex + 1) % tracks.size, keepPlaying = true)
        } else {
            _state.value = current.copy(positionMs = newPosition)
        }
    }

    private fun setTrack(index: Int, keepPlaying: Boolean = _state.value.isPlaying) {
        _state.value = PlaybackState(
            track = tracks[index],
            trackIndex = index,
            isPlaying = keepPlaying
        )
    }
}
