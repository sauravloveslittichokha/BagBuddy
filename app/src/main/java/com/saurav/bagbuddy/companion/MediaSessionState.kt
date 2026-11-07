package com.saurav.bagbuddy.companion

import android.graphics.Bitmap

enum class MediaPlaybackStatus {
    PLAYING,
    PAUSED,
    STOPPED,
    UNKNOWN
}

enum class NotificationAccessStatus {
    GRANTED,
    DENIED
}

data class MediaSessionSnapshot(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: Bitmap? = null,
    val playbackStatus: MediaPlaybackStatus = MediaPlaybackStatus.UNKNOWN,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val packageName: String? = null
)

data class MediaSessionState(
    val notificationAccess: NotificationAccessStatus = NotificationAccessStatus.DENIED,
    val hasActiveSession: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: Bitmap? = null,
    val playbackStatus: MediaPlaybackStatus = MediaPlaybackStatus.UNKNOWN,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val packageName: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun fromSnapshot(
            accessGranted: Boolean,
            snapshot: MediaSessionSnapshot?,
            errorMessage: String? = null
        ) = if (snapshot == null) {
            MediaSessionState(
                notificationAccess = if (accessGranted) {
                    NotificationAccessStatus.GRANTED
                } else {
                    NotificationAccessStatus.DENIED
                },
                errorMessage = errorMessage
            )
        } else {
            MediaSessionState(
                notificationAccess = if (accessGranted) {
                    NotificationAccessStatus.GRANTED
                } else {
                    NotificationAccessStatus.DENIED
                },
                hasActiveSession = true,
                title = snapshot.title?.takeIf(String::isNotBlank),
                artist = snapshot.artist?.takeIf(String::isNotBlank),
                album = snapshot.album?.takeIf(String::isNotBlank),
                artwork = snapshot.artwork,
                playbackStatus = snapshot.playbackStatus,
                positionMs = snapshot.positionMs?.takeIf { it >= 0L },
                durationMs = snapshot.durationMs?.takeIf { it > 0L },
                packageName = snapshot.packageName,
                errorMessage = errorMessage
            )
        }
    }
}

interface MediaSessionRepository {
    val state: kotlinx.coroutines.flow.StateFlow<MediaSessionState>

    fun start()

    fun stop()

    fun refresh()
}

enum class PlaybackSource {
    MOCK_PLAYER,
    LIVE_MEDIA_SESSION
}

object PlaybackSourceSelector {
    fun select(state: MediaSessionState): PlaybackSource =
        if (
            state.notificationAccess == NotificationAccessStatus.GRANTED &&
            state.hasActiveSession
        ) {
            PlaybackSource.LIVE_MEDIA_SESSION
        } else {
            PlaybackSource.MOCK_PLAYER
        }
}
