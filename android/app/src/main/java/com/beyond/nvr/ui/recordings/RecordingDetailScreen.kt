package com.beyond.nvr.ui.recordings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beyond.nvr.data.api.CredentialCache
import com.beyond.nvr.data.repository.PreferencesRepository
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PLAYING
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PAUSE
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_ERROR
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_AUTO_COMPLETE
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PREPAREING
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PLAYING_BUFFERING_START
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.max
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.beyond.nvr.ui.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    recordingId: String,
    onBack: () -> Unit,
    viewModel: RecordingDetailViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDetailDialog by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun toggleFullscreen() {
        val activity = context as? Activity ?: return
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.window.insetsController?.hide(WindowInsets.Type.systemBars())
                activity.window.insetsController?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                activity.window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.window.insetsController?.show(WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    val prefsRepo: PreferencesRepository = koinInject()
    val serverUrl by prefsRepo.serverUrl.collectAsState(initial = "")

    LaunchedEffect(recordingId) {
        viewModel.loadRecording(recordingId)
    }

    // Build download URL for video playback
    val downloadUrl = remember(serverUrl, uiState.recording?.id) {
        val id = uiState.recording?.id ?: recordingId
        "${serverUrl.trimEnd('/')}/api/recordings/$id/download"
    }

    val isPlayable = uiState.recording?.format in listOf("h264", "h265")

    // NvrVideoPlayer instance — held in state so DisposableEffect can release it
    val playerRef = remember { mutableStateOf<NvrVideoPlayer?>(null) }

    // Update player source when recording is ready
    LaunchedEffect(isPlayable, uiState.recording) {
        val player = playerRef.value ?: return@LaunchedEffect
        if (isPlayable && serverUrl.isNotBlank() && uiState.recording != null) {
            val authHeader = CredentialCache.get()
            val headersJson = if (authHeader != null) {
                """{"Authorization":"$authHeader"}"""
            } else ""
            player.setUp(downloadUrl, false, headersJson)
            player.startPlayLogic()
        }
    }

    // Release player and restore system UI when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            playerRef.value?.onVideoPause()
            // Restore system bars and orientation
            val activity = context as? Activity
            activity?.let {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    it.window.insetsController?.show(WindowInsets.Type.systemBars())
                } else {
                    @Suppress("DEPRECATION")
                    it.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        }
    }

    if (uiState.deleted) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PlayCircle,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("录像详情")
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDetailDialog = true }) {
                            Icon(Icons.Default.Info, contentDescription = "详情")
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "加载中…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (uiState.error != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = uiState.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    FilledTonalButton(onClick = { viewModel.loadRecording(recordingId) }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("重试")
                    }
                }
            }
        } else {
            uiState.recording?.let { recording ->
                if (isFullscreen) {
                    // Fullscreen: only video player, no padding, no other UI
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                    ) {
                        VideoPlayerCard(
                            modifier = Modifier.fillMaxSize(),
                            playerRef = playerRef,
                            downloadUrl = downloadUrl,
                            onFirstReady = { url, player ->
                                val authHeader = CredentialCache.get()
                                val headersJson = if (authHeader != null) {
                                    """{"Authorization":"$authHeader"}"""
                                } else ""
                                player.setUp(url, false, headersJson)
                                player.startPlayLogic()
                            },
                            isFullscreen = true,
                            onToggleFullscreen = ::toggleFullscreen,
                        )
                    }
                } else {
                    // Normal mode: player + episode grid
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // ── Video Player (custom controls) ──
                        if (isPlayable && serverUrl.isNotBlank()) {
                            VideoPlayerCard(
                                modifier = Modifier.fillMaxWidth(),
                                playerRef = playerRef,
                                downloadUrl = downloadUrl,
                                onFirstReady = { url, player ->
                                    val authHeader = CredentialCache.get()
                                    val headersJson = if (authHeader != null) {
                                        """{"Authorization":"$authHeader"}"""
                                    } else ""
                                    player.setUp(url, false, headersJson)
                                    player.startPlayLogic()
                                },
                                isFullscreen = false,
                                onToggleFullscreen = ::toggleFullscreen,
                            )
                        }

                        // ── Episode Grid ──
                        if (uiState.cameraRecordings.isNotEmpty()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                                ) {
                                    // Title bar with accent line
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .height(18.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(MaterialTheme.colorScheme.primary),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "片段列表",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                        ) {
                                            Text(
                                                text = "${uiState.currentIndex + 1} / ${uiState.cameraRecordings.size}",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    LazyVerticalGrid(
                                        columns = GridCells.Adaptive(minSize = 160.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    ) {
                                        itemsIndexed(uiState.cameraRecordings) { index, rec ->
                                            val isCurrent = index == uiState.currentIndex
                                            val startTime = FormatUtils.formatTimestamp(rec.startedAt, "HH:mm:ss")
                                            val endTime = FormatUtils.formatTimestamp(rec.endedAt, "HH:mm:ss")
                                            Card(
                                                onClick = { viewModel.selectRecording(rec.id) },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (isCurrent)
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    else
                                                        MaterialTheme.colorScheme.surface,
                                                ),
                                                border = if (isCurrent) {
                                                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                                                } else {
                                                    BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                                },
                                                elevation = CardDefaults.cardElevation(
                                                    defaultElevation = if (isCurrent) 4.dp else 1.dp,
                                                ),
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .height(IntrinsicSize.Min)
                                                        .defaultMinSize(minHeight = 64.dp),
                                                ) {
                                                    // Left accent bar
                                                    Box(
                                                        modifier = Modifier
                                                            .width(4.dp)
                                                            .fillMaxHeight()
                                                            .background(
                                                                if (isCurrent)
                                                                    MaterialTheme.colorScheme.primary
                                                                else
                                                                    MaterialTheme.colorScheme.outlineVariant,
                                                            ),
                                                    )
                                                    // Content
                                                    Column(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .padding(12.dp),
                                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                                    ) {
                                                        // Time range: "14:30:00 → 14:35:00"
                                                        Text(
                                                            text = "$startTime → $endTime",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = if (isCurrent)
                                                                MaterialTheme.colorScheme.onPrimaryContainer
                                                            else
                                                                MaterialTheme.colorScheme.onSurface,
                                                        )
                                                        // Duration
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Icon(
                                                                Icons.Default.Schedule,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(12.dp),
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text(
                                                                text = FormatUtils.formatDurationShort(rec.duration),
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDetailDialog && uiState.recording != null) {
        val recording = uiState.recording!!
        val startedDisplay = FormatUtils.formatTimestamp(recording.startedAt, "yyyy-MM-dd HH:mm:ss")
        val endedDisplay = FormatUtils.formatTimestamp(recording.endedAt, "yyyy-MM-dd HH:mm:ss")

        AlertDialog(
            onDismissRequest = { showDetailDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("录像详情")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    DetailRow(Icons.Default.Fingerprint, "ID", recording.id)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    DetailRow(Icons.Default.AccountCircle, "设备 ID", recording.cameraId)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    DetailRow(Icons.Default.Code, "格式", recording.format.uppercase())
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    DetailRow(Icons.Default.Timer, "时长", FormatUtils.formatDuration(recording.duration))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    DetailRow(Icons.Default.Storage, "文件大小", FormatUtils.formatFileSize(recording.fileSize))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    DetailRow(
                        if (recording.merged) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        "已合并",
                        if (recording.merged) "是" else "否",
                    )
                    if (recording.frameCount != null) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        DetailRow(Icons.Default.Image, "帧数", recording.frameCount.toString())
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    DetailRow(Icons.Default.Schedule, "开始时间", startedDisplay)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    DetailRow(Icons.Default.Schedule, "结束时间", endedDisplay)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDetailDialog = false
                        viewModel.deleteRecording()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除", fontWeight = FontWeight.Medium)
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetailDialog = false }) {
                    Text("确定")
                }
            },
        )
    }
}

/** Format millis to MM:SS or HH:MM:SS */
private fun formatPlayerTime(ms: Long): String {
    val totalSecs = ms / 1000
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}

/**
 * Custom seek bar for video playback, based on the reference project's ArkSeekBar.
 * Uses Canvas to draw three layers (background/buffered/active track).
 * Supports both drag and tap for seeking.
 */
@Composable
private fun RecordingSeekBar(
    duration: Long,
    position: Long,
    onPositionChange: (position: Long, pressing: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trackWidth: Float = 32f,
    colors: SliderColors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
        disabledActiveTrackColor = Color.White.copy(alpha = 0.5f),
    ),
) {
    val density = LocalDensity.current
    var sliderWidth by remember { mutableStateOf(0.dp) }
    var thumbOffsetX by remember { mutableFloatStateOf(0f) }
    var pressing by remember { mutableStateOf(false) }
    var previewPosition by remember { mutableLongStateOf(0L) }
    var thumbOffsetXMax by remember { mutableFloatStateOf(1f) }
    val thumbOffsetXMin by remember { mutableFloatStateOf(0f) }

    val draggableState = rememberDraggableState {
        pressing = true
        thumbOffsetX = (thumbOffsetX + it).coerceIn(thumbOffsetXMin, thumbOffsetXMax)
        val percent = thumbOffsetX / thumbOffsetXMax
        previewPosition = (percent * duration).toLong()
        onPositionChange(previewPosition, true)
    }

    LaunchedEffect(sliderWidth) {
        thumbOffsetXMax = with(density) { max(1f, (sliderWidth - 32.dp).toPx()) }
    }

    LaunchedEffect(position) {
        if (pressing) return@LaunchedEffect
        val percent = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        thumbOffsetX = (percent * thumbOffsetXMax).coerceIn(thumbOffsetXMin, thumbOffsetXMax)
    }

    BoxWithConstraints(
        modifier = modifier
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    onPositionChange(previewPosition, false)
                    delay(200)
                    pressing = false
                }
            )
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val xDp = with(density) { offset.x.toDp() }
                    val percent =
                        (xDp.coerceIn(16.dp, sliderWidth - 16.dp) - 16.dp) / (sliderWidth - 32.dp)
                    val newPosition = (percent * duration).toLong()
                    onPositionChange(newPosition, false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        sliderWidth = this.maxWidth
        SeekBarTrack(
            modifier = Modifier.padding(horizontal = 5.dp),
            duration = duration,
            position = if (pressing) previewPosition else position,
            bufferedPercentage = 0,
            trackWidth = trackWidth,
            colors = colors,
        )
    }
}

@Composable
private fun SeekBarTrack(
    modifier: Modifier = Modifier,
    duration: Long,
    position: Long,
    bufferedPercentage: Int,
    trackWidth: Float = 32f,
    colors: SliderColors = SliderDefaults.colors(),
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(trackWidth.dp)
    ) {
        // 1) Background track
        drawLine(
            color = colors.inactiveTrackColor,
            start = Offset(0f, center.y),
            end = Offset(size.width, center.y),
            strokeWidth = trackWidth,
            cap = StrokeCap.Round,
        )
        // 2) Buffered track
        if (bufferedPercentage > 0) {
            drawLine(
                color = colors.disabledActiveTrackColor,
                start = Offset(0f, center.y),
                end = Offset(size.width * bufferedPercentage / 100f, center.y),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round,
            )
        }
        // 3) Active (played) track
        val fraction = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        drawLine(
            color = colors.activeTrackColor,
            start = Offset(0f, center.y),
            end = Offset(size.width * fraction, center.y),
            strokeWidth = trackWidth,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * GSYVideoPlayer card with custom Compose control overlay.
 *
 * Uses [NvrVideoPlayer] (minimal layout + listener pattern) instead of polling,
 * matching the architecture from the reference video-player project.
 */
@Composable
private fun VideoPlayerCard(
    modifier: Modifier,
    playerRef: MutableState<NvrVideoPlayer?>,
    downloadUrl: String,
    onFirstReady: (url: String, player: NvrVideoPlayer) -> Unit,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    // ── state tracked via GSY listener callbacks ──
    var currentState by remember { mutableIntStateOf(-1) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(false) }

    val listener = remember {
        object : NvrPlayerListener {
            override fun onPlayerStateChanged(state: Int) {
                currentState = state
            }

            override fun onPlayerPositionChanged(position: Long, total: Long) {
                currentPosition = position
                totalDuration = total
            }
        }
    }

    val isPlaying = currentState == CURRENT_STATE_PLAYING
    val isBuffering = currentState == CURRENT_STATE_PREPAREING
        || currentState == CURRENT_STATE_PLAYING_BUFFERING_START

    // Auto-hide controls after 4s
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(4000)
            showControls = false
        }
    }

    // Inner content: player surface + control overlay
    @Composable
    fun PlayerInner() {
        Box(modifier = Modifier.fillMaxSize()) {
            // GSYVideoPlayer surface
            AndroidView(
                factory = { ctx ->
                    NvrVideoPlayer(ctx).apply {
                        setPlayerListener(listener)
                        playerRef.value = this
                        onFirstReady(downloadUrl, this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // ── Control overlay ──
            Box(modifier = Modifier.fillMaxSize()) {
                if (!showControls) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { showControls = true },
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(300)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    Pair(0f, Color.Black),
                                    Pair(.2f, Color.Transparent),
                                    Pair(.7f, Color.Transparent),
                                    Pair(1f, Color.Black),
                                ),
                                alpha = 0.8f,
                            )
                            .clickable { showControls = false },
                    ) {
                        // ── Center play/pause ──
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            FilledIconButton(
                                onClick = { playerRef.value?.clickStartIcon() },
                                modifier = Modifier.size(56.dp),
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause
                                    else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "暂停" else "播放",
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }

                        // ── Bottom bar: seek + time + fullscreen ──
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                            color = Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = formatPlayerTime(currentPosition),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                RecordingSeekBar(
                                    duration = totalDuration,
                                    position = currentPosition,
                                    onPositionChange = { target, pressing ->
                                        playerRef.value?.seekTo(target)
                                        if (pressing) currentPosition = target
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                )
                                Text(
                                    text = formatPlayerTime(totalDuration),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                IconButton(
                                    onClick = onToggleFullscreen,
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isFullscreen)
                                            Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                        contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Buffering indicator ──
            if (isBuffering) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                    )
                }
            }
        }
    }

    // Outer wrapper: Card (normal) vs black fullscreen
    if (isFullscreen) {
        Box(modifier = modifier.background(Color.Black)) {
            PlayerInner()
        }
    } else {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                PlayerInner()
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
