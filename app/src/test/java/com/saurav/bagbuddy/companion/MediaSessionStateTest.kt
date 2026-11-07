package com.saurav.bagbuddy.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionStateTest {
    @Test
    fun notificationListenerSettingDetectsEnabledComponent() {
        val ownComponent = "com.saurav.bagbuddy/.companion.BagBuddyNotificationListenerService"

        assertTrue(NotificationAccessHelper.isListenerEnabled("other/.Listener:$ownComponent", ownComponent))
        assertFalse(NotificationAccessHelper.isListenerEnabled("other/.Listener", ownComponent))
        assertFalse(NotificationAccessHelper.isListenerEnabled(null, ownComponent))
    }

    @Test
    fun notificationAccessDeniedAndGrantedAreRepresentedExplicitly() {
        val denied = MediaSessionState.fromSnapshot(accessGranted = false, snapshot = null)
        val granted = MediaSessionState.fromSnapshot(accessGranted = true, snapshot = null)

        assertEquals(NotificationAccessStatus.DENIED, denied.notificationAccess)
        assertEquals(NotificationAccessStatus.GRANTED, granted.notificationAccess)
        assertFalse(denied.hasActiveSession)
        assertFalse(granted.hasActiveSession)
    }

    @Test
    fun activeSessionSupportsMissingTitleArtistArtworkAndDuration() {
        val state = MediaSessionState.fromSnapshot(
            accessGranted = true,
            snapshot = MediaSessionSnapshot(
                title = " ",
                artist = null,
                album = "Album",
                playbackStatus = MediaPlaybackStatus.PAUSED,
                positionMs = 12_000L,
                durationMs = null,
                packageName = "example.music"
            )
        )

        assertTrue(state.hasActiveSession)
        assertNull(state.title)
        assertNull(state.artist)
        assertEquals("Album", state.album)
        assertNull(state.artwork)
        assertNull(state.durationMs)
        assertEquals(12_000L, state.positionMs)
        assertEquals(MediaPlaybackStatus.PAUSED, state.playbackStatus)
    }

    @Test
    fun invalidPositionAndDurationAreNotPublished() {
        val state = MediaSessionState.fromSnapshot(
            accessGranted = true,
            snapshot = MediaSessionSnapshot(
                positionMs = -1L,
                durationMs = 0L
            )
        )

        assertNull(state.positionMs)
        assertNull(state.durationMs)
    }

    @Test
    fun playbackStatusesAndPositionUpdatesArePreservedAcrossTrackChanges() {
        val firstTrack = MediaSessionState.fromSnapshot(
            accessGranted = true,
            snapshot = MediaSessionSnapshot(
                title = "First",
                playbackStatus = MediaPlaybackStatus.PLAYING,
                positionMs = 2_500L,
                durationMs = 60_000L
            )
        )
        val secondTrack = MediaSessionState.fromSnapshot(
            accessGranted = true,
            snapshot = MediaSessionSnapshot(
                title = "Second",
                playbackStatus = MediaPlaybackStatus.PAUSED,
                positionMs = 17_000L,
                durationMs = 90_000L
            )
        )

        assertEquals("First", firstTrack.title)
        assertEquals(MediaPlaybackStatus.PLAYING, firstTrack.playbackStatus)
        assertEquals(2_500L, firstTrack.positionMs)
        assertEquals("Second", secondTrack.title)
        assertEquals(MediaPlaybackStatus.PAUSED, secondTrack.playbackStatus)
        assertEquals(17_000L, secondTrack.positionMs)
    }

    @Test
    fun sourceSelectorFallsBackToMockAndSwitchesOnlyForGrantedActiveSession() {
        val deniedActive = MediaSessionState.fromSnapshot(
            accessGranted = false,
            snapshot = MediaSessionSnapshot(title = "Live")
        )
        val noSession = MediaSessionState.fromSnapshot(accessGranted = true, snapshot = null)
        val live = MediaSessionState.fromSnapshot(
            accessGranted = true,
            snapshot = MediaSessionSnapshot(title = "Live")
        )

        assertEquals(PlaybackSource.MOCK_PLAYER, PlaybackSourceSelector.select(deniedActive))
        assertEquals(PlaybackSource.MOCK_PLAYER, PlaybackSourceSelector.select(noSession))
        assertEquals(PlaybackSource.LIVE_MEDIA_SESSION, PlaybackSourceSelector.select(live))
        assertEquals(PlaybackSource.MOCK_PLAYER, PlaybackSourceSelector.select(noSession))
    }
}
