package com.saurav.bagbuddy.mock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockMusicPlayerTest {
    @Test
    fun supportsPlayPauseSeekAndTrackChanges() {
        val player = MockMusicPlayer()

        player.playPause()
        assertTrue(player.state.value.isPlaying)

        player.seekTo(5_000L)
        assertEquals(5_000L, player.state.value.positionMs)

        player.previous()
        assertEquals(0L, player.state.value.positionMs)

        player.next()
        assertEquals("slow-sunday", player.state.value.track.song.id)
    }

    @Test
    fun advancingPastTrackEndStartsNextTrackWithoutPausing() {
        val player = MockMusicPlayer()
        player.playPause()
        player.seekTo(player.state.value.track.song.durationMs - 100L)

        player.advanceBy(250L)

        assertEquals("slow-sunday", player.state.value.track.song.id)
        assertEquals(0L, player.state.value.positionMs)
        assertTrue(player.state.value.isPlaying)
    }

    @Test
    fun doesNotAdvanceWhilePaused() {
        val player = MockMusicPlayer()

        player.advanceBy(1_000L)

        assertEquals(0L, player.state.value.positionMs)
        assertFalse(player.state.value.isPlaying)
    }
}
