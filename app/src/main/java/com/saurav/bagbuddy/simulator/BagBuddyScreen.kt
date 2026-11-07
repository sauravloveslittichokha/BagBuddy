package com.saurav.bagbuddy.simulator

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurav.bagbuddy.data.LyricLine
import com.saurav.bagbuddy.data.LyricWord
import com.saurav.bagbuddy.data.LyricsSyncLevel
import com.saurav.bagbuddy.data.LyricsTrack
import com.saurav.bagbuddy.data.Song
import com.saurav.bagbuddy.companion.MediaPlaybackStatus
import com.saurav.bagbuddy.companion.MediaSessionRepository
import com.saurav.bagbuddy.companion.MediaSessionState
import com.saurav.bagbuddy.companion.NotificationAccessHelper
import com.saurav.bagbuddy.companion.NotificationAccessStatus
import com.saurav.bagbuddy.companion.PlaybackSource
import com.saurav.bagbuddy.companion.PlaybackSourceSelector
import com.saurav.bagbuddy.lyrics.LyricsDisplaySource
import com.saurav.bagbuddy.lyrics.LyricsFrame
import com.saurav.bagbuddy.lyrics.LyricsLoadStatus
import com.saurav.bagbuddy.lyrics.LyricsPresentationState
import com.saurav.bagbuddy.lyrics.LyricsRepository
import com.saurav.bagbuddy.lyrics.LyricsTrackMatcher
import com.saurav.bagbuddy.lyrics.LyricsSyncEngine
import com.saurav.bagbuddy.lyrics.LrclibLyricsProvider
import com.saurav.bagbuddy.lyrics.SharedPreferencesLyricsCache
import com.saurav.bagbuddy.lyrics.SyncLevelDetector
import com.saurav.bagbuddy.mock.MockMusicPlayer
import com.saurav.bagbuddy.mock.SampleLyrics
import com.saurav.bagbuddy.ui.theme.BagBuddyAccent
import com.saurav.bagbuddy.ui.theme.BagBuddyMuted
import com.saurav.bagbuddy.ui.theme.BagBuddyText
import kotlinx.coroutines.delay

@Composable
fun BagBuddyScreen(mediaSessionRepository: MediaSessionRepository) {
    val player = remember { MockMusicPlayer() }
    val lyricsEngine = remember { LyricsSyncEngine() }
    val context = LocalContext.current
    val lyricsRepository = remember(context) {
        LyricsRepository(
            providers = listOf(LrclibLyricsProvider()),
            cache = SharedPreferencesLyricsCache(context.applicationContext)
        )
    }
    val playback by player.state.collectAsState()
    val mediaSession by mediaSessionRepository.state.collectAsState()
    var selectedLevel by remember { mutableStateOf<LyricsSyncLevel?>(null) }
    var retryLyrics by remember { mutableIntStateOf(0) }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        mediaSessionRepository.refresh()
    }

    LaunchedEffect(player) {
        while (true) {
            delay(250L)
            player.advanceBy(250L)
        }
    }
    LaunchedEffect(playback.trackIndex) {
        selectedLevel = null
    }

    val playbackSource = PlaybackSourceSelector.select(mediaSession)
    val isLive = playbackSource == PlaybackSource.LIVE_MEDIA_SESSION
    val track = if (isLive) mediaTrack(mediaSession) else playback.track
    val sampleTrack = SampleLyrics.tracks.firstOrNull {
        LyricsTrackMatcher.matches(track.song, it.song)
    }
    var lyricsState by remember(
        track.song.id,
        track.song.title,
        track.song.artist,
        track.song.album,
        track.song.durationMs
    ) {
        mutableStateOf(LyricsPresentationState.loading(sampleTrack))
    }
    LaunchedEffect(
        track.song.id,
        track.song.title,
        track.song.artist,
        track.song.album,
        track.song.durationMs,
        retryLyrics
    ) {
        lyricsState = LyricsPresentationState.loading(sampleTrack)
        try {
            val result = lyricsRepository.findLyrics(track.song)
            lyricsState = LyricsPresentationState.fromResult(result, sampleTrack)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            lyricsState = LyricsPresentationState.unexpectedFailure(sampleTrack, failure)
        }
    }
    val lyricsTrack = track.copy(lines = lyricsState.track?.lines.orEmpty())
    val positionMs = if (isLive) mediaSession.positionMs else playback.positionMs
    val isPlaying = if (isLive) {
        mediaSession.playbackStatus == MediaPlaybackStatus.PLAYING
    } else {
        playback.isPlaying
    }
    val supportedLevels = SyncLevelDetector.supportedLevels(lyricsTrack)
    val selectedSyncLevel = selectedLevel
        ?.takeIf { it in supportedLevels }
        ?: SyncLevelDetector.detect(lyricsTrack)
    val lyricsFrame = lyricsEngine.frameAt(
        track = lyricsTrack,
        positionMs = positionMs ?: 0L,
        requestedLevel = selectedSyncLevel
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07080B))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ScreenHeader()
        Spacer(Modifier.height(12.dp))
        MediaSessionStatus(
            state = mediaSession,
            onGrantAccess = {
                settingsLauncher.launch(NotificationAccessHelper(context).settingsIntent())
            }
        )
        Spacer(Modifier.height(20.dp))
        DevicePreview(
            track = lyricsTrack,
            lyricsFrame = lyricsFrame,
            positionMs = positionMs,
            playbackSource = playbackSource,
            mediaSession = mediaSession,
            lyricsState = lyricsState
        )
        LyricsLookupNotice(
            state = lyricsState,
            onRetry = { retryLyrics++ }
        )
        Spacer(Modifier.height(18.dp))
        PlaybackControls(
            player = player,
            positionMs = positionMs,
            durationMs = track.song.durationMs,
            isPlaying = isPlaying,
            enabled = !isLive,
            playbackSource = playbackSource
        )
        Spacer(Modifier.height(18.dp))
        SyncLevelPicker(
            supportedLevels = supportedLevels,
            selectedLevel = selectedSyncLevel,
            onSelect = { selectedLevel = it }
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = "DEMO CONTENT • WORKS OFFLINE",
            color = BagBuddyMuted,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp
        )
    }
}

private fun mediaTrack(state: MediaSessionState) = LyricsTrack(
    song = Song(
        id = state.packageName ?: "live-session",
        title = state.title ?: "Title unavailable",
        artist = state.artist ?: "Artist unavailable",
        durationMs = state.durationMs ?: 0L,
        artworkEmoji = "♪",
        album = state.album ?: ""
    ),
    lines = emptyList()
)

@Composable
private fun MediaSessionStatus(
    state: MediaSessionState,
    onGrantAccess: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF11141A))
            .padding(12.dp)
    ) {
        when (state.notificationAccess) {
            NotificationAccessStatus.DENIED -> {
                Text(
                    "Media session access is off",
                    color = BagBuddyText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Allow notification access to read available song and playback details. " +
                        "This does not provide synchronized lyrics.",
                    color = BagBuddyMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                state.errorMessage?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Text(error, color = Color(0xFFFFB4AB), fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onGrantAccess) {
                    Text("Open notification access settings")
                }
            }
            NotificationAccessStatus.GRANTED -> {
                val message = if (state.hasActiveSession) {
                    "Live media session • ${state.playbackStatus.name.lowercase()}"
                } else {
                    "No active media session • Using mock player"
                }
                Text(message, color = BagBuddyMuted, fontSize = 11.sp)
                state.errorMessage?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Text(error, color = Color(0xFFFFB4AB), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ScreenHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "BAG BUDDY",
                color = BagBuddyText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                text = "your pocket-sized music pal",
                color = BagBuddyMuted,
                fontSize = 12.sp
            )
        }
        Surface(
            color = Color(0xFF151920),
            shape = RoundedCornerShape(50)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(6.dp).background(Color(0xFF9A8BFF), CircleShape))
                Text("SIMULATOR", color = Color(0xFFC9C2FF), fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun DevicePreview(
    track: LyricsTrack,
    lyricsFrame: LyricsFrame,
    positionMs: Long?,
    playbackSource: PlaybackSource,
    mediaSession: MediaSessionState,
    lyricsState: LyricsPresentationState
) {
    val duration = track.song.durationMs.coerceAtLeast(1L)
    val progress = positionMs?.let { (it.toFloat() / duration).coerceIn(0f, 1f) } ?: 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 338.dp)
            .aspectRatio(280f / 456f)
            .clip(RoundedCornerShape(34.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF343941), Color(0xFF16191E), Color(0xFF292D34))
                )
            )
            .border(1.dp, Color(0xFF525761), RoundedCornerShape(34.dp))
            .padding(9.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(Color(0xFF050609))
            .padding(horizontal = 17.dp, vertical = 15.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF171A22)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✦", color = BagBuddyAccent, fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "BAG BUDDY",
                    color = BagBuddyText,
                    fontSize = 10.sp,
                    letterSpacing = 1.7.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (playbackSource == PlaybackSource.LIVE_MEDIA_SESSION) {
                        "LIVE SESSION"
                    } else {
                        "MOCK PLAYER"
                    },
                    color = BagBuddyMuted,
                    fontSize = 8.sp,
                    letterSpacing = .5.sp
                )
            }

            SongDetails(track, mediaSession.artwork.takeIf {
                playbackSource == PlaybackSource.LIVE_MEDIA_SESSION
            })
            PixelPet()
            LyricsPreview(track, lyricsFrame, lyricsState)
            ProgressPreview(progress, positionMs, track.song.durationMs)
        }
    }
}

@Composable
private fun SongDetails(track: LyricsTrack, artwork: android.graphics.Bitmap?) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF11141A))
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(listOf(Color(0xFF37314E), Color(0xFF1B202A)))
                ),
            contentAlignment = Alignment.Center
        ) {
            if (artwork != null) {
                Image(
                    bitmap = artwork.asImageBitmap(),
                    contentDescription = "Album artwork",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(track.song.artworkEmoji, fontSize = 22.sp)
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.song.title,
                color = BagBuddyText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = track.song.artist,
                color = BagBuddyMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (track.song.album.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = track.song.album,
                    color = BagBuddyMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PixelPet() {
    val transition = rememberInfiniteTransition(label = "pet-bob")
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pet-bob-offset"
    )

    Column(
        modifier = Modifier.fillMaxWidth().offset(y = bob.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(width = 92.dp, height = 88.dp)
                .semantics { contentDescription = "Animated pixel pet, a small purple cat" }
        ) {
            drawPixelCat()
        }
        Text("feeling curious", color = Color(0xFF8F8BA3), fontSize = 10.sp, letterSpacing = .4.sp)
    }
}

private fun DrawScope.drawPixelCat() {
    val cell = size.minDimension / 16f
    val widths = listOf(4, 6, 8, 10, 12, 14, 14, 14, 14, 14, 12, 10)
    widths.forEachIndexed { row, width ->
        val left = (16 - width) / 2
        for (column in 0 until width) {
            val color = when {
                row < 3 && (column == 0 || column == width - 1) -> Color(0xFFFFB5CF)
                row == 7 && (column == 3 || column == width - 4) -> Color(0xFF222033)
                row == 8 && (column == 4 || column == width - 5) -> Color(0xFFFF9DBB)
                row >= 10 && (column < 2 || column >= width - 2) -> Color.Transparent
                else -> Color(0xFF9C8BFF)
            }
            if (color != Color.Transparent) {
                drawRect(
                    color = color,
                    topLeft = Offset((left + column) * cell, row * cell),
                    size = Size(cell - 1.5f, cell - 1.5f)
                )
            }
        }
    }
    drawRect(
        color = Color(0xFF29223C),
        topLeft = Offset(5 * cell, 7 * cell),
        size = Size(cell, cell)
    )
    drawRect(
        color = Color(0xFF29223C),
        topLeft = Offset(10 * cell, 7 * cell),
        size = Size(cell, cell)
    )
}

@Composable
private fun LyricsPreview(
    track: LyricsTrack,
    frame: LyricsFrame,
    lyricsState: LyricsPresentationState
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0xFF101218))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = lyricsState.source.label,
                color = BagBuddyMuted,
                fontSize = 8.sp,
                letterSpacing = 1.2.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = frame.syncLevel.label.uppercase(),
                color = BagBuddyAccent,
                fontSize = 8.sp,
                letterSpacing = .8.sp
            )
        }
        Spacer(Modifier.height(7.dp))
        when {
            lyricsState.status == LyricsLoadStatus.LOADING -> LyricsLoadingIndicator()
            track.lines.isEmpty() ->
                Text(
                    text = lyricsState.emptyCardMessage(),
                    color = BagBuddyMuted,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Medium
                )
            frame.currentLine != null -> Text(
                text = highlightedLyric(
                    frame.currentLine,
                    frame.currentLineIndex ?: 0,
                    frame
                ),
                color = BagBuddyText,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            frame.nextLine != null -> Column {
                Text(
                    "UP NEXT",
                    color = BagBuddyMuted,
                    fontSize = 8.sp,
                    letterSpacing = 1.1.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    frame.nextLine.text,
                    color = BagBuddyText,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            else -> Text(
                "Lyrics finished",
                color = BagBuddyMuted,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = when {
                lyricsState.status == LyricsLoadStatus.LOADING -> " "
                track.lines.isEmpty() -> " "
                frame.currentLine != null -> frame.nextLine?.text ?: "End of lyrics"
                frame.nextLine != null -> "Lyrics are synced to playback"
                else -> "End of lyrics"
            },
            color = BagBuddyMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LyricsLoadingIndicator() {
    val transition = rememberInfiniteTransition(label = "lyrics-loading")
    Row(
        modifier = Modifier.semantics {
            contentDescription = "Loading lyrics"
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(
                        offsetMillis = index * 160,
                        offsetType = StartOffsetType.Delay
                    )
                ),
                label = "lyrics-loading-dot-$index"
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(BagBuddyAccent.copy(alpha = alpha))
            )
        }
        Spacer(Modifier.width(4.dp))
        Text("Searching lyrics", color = BagBuddyMuted, fontSize = 12.sp)
    }
}

private fun LyricsPresentationState.emptyCardMessage(): String = when (status) {
    LyricsLoadStatus.LOADING -> "Searching lyrics"
    LyricsLoadStatus.FOUND -> "No lyric lines were returned."
    LyricsLoadStatus.NOT_FOUND -> "No lyrics found for this track."
    LyricsLoadStatus.INVALID_MATCH -> "A matching lyrics result was not verified."
    LyricsLoadStatus.PROVIDER_ERROR -> "Lyrics could not be loaded."
    LyricsLoadStatus.MISSING_METADATA -> "Track title or artist is unavailable."
}

@Composable
private fun LyricsLookupNotice(
    state: LyricsPresentationState,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            state.message,
            color = BagBuddyMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
        if (state.status != LyricsLoadStatus.LOADING &&
            state.status != LyricsLoadStatus.FOUND
        ) {
            Button(onClick = onRetry) {
                Text("RETRY LYRICS")
            }
        }
    }
}

private fun highlightedLyric(
    line: LyricLine,
    lineIndex: Int,
    frame: LyricsFrame
): AnnotatedString = buildAnnotatedString {
    val activeColor = Color(0xFFBDB1FF)
    val quietColor = Color(0xFF717483)
    when (frame.syncLevel) {
        LyricsSyncLevel.PLAIN -> {
            withStyle(SpanStyle(color = BagBuddyText)) { append(line.text) }
        }
        LyricsSyncLevel.LINE -> {
            val active = frame.currentLineIndex == lineIndex
            withStyle(SpanStyle(color = if (active) activeColor else quietColor)) {
                append(line.text)
            }
        }
        LyricsSyncLevel.WORD -> appendWords(
            line.words,
            frame.activeWordIndex,
            activeColor,
            quietColor
        )
        LyricsSyncLevel.SYLLABLE -> {
            appendSyllables(
                line.words,
                frame.activeWordIndex,
                frame.activeSyllableIndex,
                activeColor,
                quietColor
            )
        }
    }
}

private fun AnnotatedString.Builder.appendWords(
    words: List<LyricWord>,
    activeWordIndex: Int?,
    activeColor: Color,
    quietColor: Color
) {
    words.forEachIndexed { index, word ->
        if (index > 0) append(" ")
        val color = if (index == activeWordIndex) activeColor else quietColor
        withStyle(SpanStyle(color = color)) { append(word.text) }
    }
}

private fun AnnotatedString.Builder.appendSyllables(
    words: List<LyricWord>,
    activeWordIndex: Int?,
    activeSyllableIndex: Int?,
    activeColor: Color,
    quietColor: Color
) {
    words.forEachIndexed { wordIndex, word ->
        if (wordIndex > 0) append(" ")
        if (word.syllables.isEmpty()) {
            val color = if (wordIndex == activeWordIndex) activeColor else quietColor
            withStyle(SpanStyle(color = color)) { append(word.text) }
        } else {
            word.syllables.forEachIndexed { syllableIndex, syllable ->
                val color = if (
                    wordIndex == activeWordIndex && syllableIndex == activeSyllableIndex
                ) activeColor else quietColor
                withStyle(SpanStyle(color = color)) { append(syllable.text) }
            }
        }
    }
}

@Composable
private fun ProgressPreview(progress: Float, positionMs: Long?, durationMs: Long) {
    Column {
        Box(
            modifier = Modifier.fillMaxWidth().height(3.dp)
                .clip(CircleShape)
                .background(Color(0xFF262934))
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(progress).height(3.dp)
                    .clip(CircleShape)
                    .background(BagBuddyAccent)
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatAvailableTime(positionMs), color = BagBuddyMuted, fontSize = 9.sp)
            Text(formatAvailableTime(durationMs.takeIf { it > 0L }), color = BagBuddyMuted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun PlaybackControls(
    player: MockMusicPlayer,
    positionMs: Long?,
    durationMs: Long,
    isPlaying: Boolean,
    enabled: Boolean,
    playbackSource: PlaybackSource
) {
    Column(modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatAvailableTime(positionMs), color = BagBuddyMuted, fontSize = 11.sp)
            Text(
                if (playbackSource == PlaybackSource.LIVE_MEDIA_SESSION) "LIVE SESSION" else "MOCK PLAYER",
                color = BagBuddyMuted,
                fontSize = 9.sp,
                letterSpacing = 1.3.sp
            )
            Text(formatAvailableTime(durationMs.takeIf { it > 0L }), color = BagBuddyMuted, fontSize = 11.sp)
        }
        Slider(
            value = positionMs?.let {
                (it.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            } ?: 0f,
            onValueChange = { fraction -> player.seekTo((fraction * durationMs).toLong()) },
            enabled = enabled && durationMs > 0L && positionMs != null
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            PlayerButton(
                label = "◀|",
                description = "Previous track",
                enabled = enabled,
                onClick = player::previous
            )
            Spacer(Modifier.width(30.dp))
            IconButton(
                onClick = player::playPause,
                enabled = enabled,
                modifier = Modifier.size(58.dp).background(BagBuddyAccent, CircleShape)
            ) {
                Text(
                    text = if (isPlaying) "Ⅱ" else "▶",
                    color = Color(0xFF15111F),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(30.dp))
            PlayerButton(
                label = "|▶",
                description = "Next track",
                enabled = enabled,
                onClick = player::next
            )
        }
    }
}

@Composable
private fun PlayerButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Text(
            text = label,
            modifier = Modifier.semantics { contentDescription = description },
            color = BagBuddyText,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SyncLevelPicker(
    supportedLevels: List<LyricsSyncLevel>,
    selectedLevel: LyricsSyncLevel,
    onSelect: (LyricsSyncLevel) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)) {
        Text(
            text = "LYRIC DETAIL",
            color = BagBuddyMuted,
            fontSize = 9.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            LyricsSyncLevel.entries.forEach { level ->
                FilterChip(
                    selected = selectedLevel == level,
                    onClick = { onSelect(level) },
                    enabled = level in supportedLevels,
                    label = {
                        Text(
                            text = level.label,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                )
            }
        }
        Text(
            text = "Unavailable timing stays disabled; no timestamps are guessed.",
            color = BagBuddyMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0L) / 1_000L).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatAvailableTime(timeMs: Long?): String =
    timeMs?.let(::formatTime) ?: "--:--"
