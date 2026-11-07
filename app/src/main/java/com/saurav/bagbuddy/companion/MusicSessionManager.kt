package com.saurav.bagbuddy.companion

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager as PlatformMediaSessionManager
import android.media.session.PlaybackState as PlatformPlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MusicSessionManager(context: Context) : MediaSessionRepository {
    private val appContext = context.applicationContext
    private val listenerComponent = ComponentName(
        appContext,
        BagBuddyNotificationListenerService::class.java
    )
    private val accessHelper = NotificationAccessHelper(appContext)
    private val platformManager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE)
        as PlatformMediaSessionManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(MediaSessionState())
    override val state: StateFlow<MediaSessionState> = _state.asStateFlow()

    private var started = false
    private var activeSessionsCallbackRegistered = false
    private var selectedController: MediaController? = null
    private var positionTicker: Job? = null

    private val activeSessionsListener =
        PlatformMediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            selectController(controllers.orEmpty())
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishSelectedController()
        }

        override fun onPlaybackStateChanged(state: PlatformPlaybackState?) {
            publishSelectedController()
        }

        override fun onSessionDestroyed() {
            selectedController = null
            refreshSessions()
        }
    }

    override fun start() {
        if (started) return
        started = true
        refresh()
    }

    override fun refresh() {
        if (!started) return
        if (!accessHelper.hasAccess()) {
            unregisterSessionCallbacks()
            _state.value = MediaSessionState.fromSnapshot(accessGranted = false, snapshot = null)
            return
        }

        try {
            if (!activeSessionsCallbackRegistered) {
                platformManager.addOnActiveSessionsChangedListener(
                    activeSessionsListener,
                    listenerComponent,
                    handler
                )
                activeSessionsCallbackRegistered = true
            }
            refreshSessions()
        } catch (_: SecurityException) {
            unregisterSessionCallbacks()
            _state.value = MediaSessionState.fromSnapshot(
                accessGranted = false,
                snapshot = null,
                errorMessage = "Notification access is unavailable. Check Settings and try again."
            )
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        unregisterSessionCallbacks()
        _state.value = _state.value.copy(
            hasActiveSession = false,
            title = null,
            artist = null,
            album = null,
            artwork = null,
            playbackStatus = MediaPlaybackStatus.UNKNOWN,
            positionMs = null,
            durationMs = null,
            packageName = null
        )
    }

    private fun refreshSessions() {
        try {
            selectController(platformManager.getActiveSessions(listenerComponent))
        } catch (_: SecurityException) {
            unregisterSessionCallbacks()
            _state.value = MediaSessionState.fromSnapshot(
                accessGranted = false,
                snapshot = null,
                errorMessage = "Notification access was revoked. Enable it again in Settings."
            )
        }
    }

    private fun selectController(controllers: List<MediaController>) {
        if (!started) return
        val next = controllers.firstOrNull {
            it.playbackState?.state == PlatformPlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull {
            it.playbackState?.state == PlatformPlaybackState.STATE_PAUSED
        } ?: controllers.firstOrNull()

        if (next?.sessionToken != selectedController?.sessionToken) {
            selectedController?.unregisterCallback(controllerCallback)
            selectedController = next
            next?.registerCallback(controllerCallback, handler)
        }
        publishSelectedController()
    }

    private fun publishSelectedController() {
        if (!started) return
        val controller = selectedController
        if (controller == null) {
            stopPositionTicker()
            _state.value = MediaSessionState.fromSnapshot(
                accessGranted = true,
                snapshot = null
            )
            return
        }

        val metadata = controller.metadata
        val playback = controller.playbackState
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ?.takeIf { it > 0L }
        val position = playback?.position?.takeIf { it >= 0L }?.let { basePosition ->
            val projected = if (
                playback.state == PlatformPlaybackState.STATE_PLAYING &&
                playback.lastPositionUpdateTime > 0L
            ) {
                val elapsed = (SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime)
                    .coerceAtLeast(0L)
                basePosition + (elapsed * playback.playbackSpeed).toLong()
            } else {
                basePosition
            }
            projected.coerceAtLeast(0L).let { value ->
                if (duration != null) value.coerceAtMost(duration) else value
            }
        }
        val snapshot = MediaSessionSnapshot(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?.takeIf(String::isNotBlank)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?.takeIf(String::isNotBlank)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playbackStatus = playback.toBagBuddyStatus(),
            positionMs = position,
            durationMs = duration,
            packageName = controller.packageName
        )
        _state.value = MediaSessionState.fromSnapshot(accessGranted = true, snapshot = snapshot)
        updatePositionTicker(playback?.state == PlatformPlaybackState.STATE_PLAYING)
    }

    private fun PlatformPlaybackState?.toBagBuddyStatus(): MediaPlaybackStatus =
        when (this?.state) {
            PlatformPlaybackState.STATE_PLAYING -> MediaPlaybackStatus.PLAYING
            PlatformPlaybackState.STATE_PAUSED -> MediaPlaybackStatus.PAUSED
            PlatformPlaybackState.STATE_STOPPED,
            PlatformPlaybackState.STATE_NONE -> MediaPlaybackStatus.STOPPED
            else -> MediaPlaybackStatus.UNKNOWN
        }

    private fun updatePositionTicker(isPlaying: Boolean) {
        if (!isPlaying) {
            stopPositionTicker()
            return
        }
        if (positionTicker?.isActive == true) return

        positionTicker = scope.launch {
            while (started && selectedController?.playbackState?.state ==
                PlatformPlaybackState.STATE_PLAYING
            ) {
                delay(POSITION_UPDATE_INTERVAL_MS)
                publishSelectedController()
            }
        }
    }

    private fun stopPositionTicker() {
        positionTicker?.cancel()
        positionTicker = null
    }

    private fun unregisterSessionCallbacks() {
        stopPositionTicker()
        selectedController?.unregisterCallback(controllerCallback)
        selectedController = null
        if (activeSessionsCallbackRegistered) {
            platformManager.removeOnActiveSessionsChangedListener(activeSessionsListener)
            activeSessionsCallbackRegistered = false
        }
    }

    private companion object {
        const val POSITION_UPDATE_INTERVAL_MS = 500L
    }
}
