package com.beyond.nvr.ui.recordings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beyond.nvr.data.api.CredentialCache
import com.beyond.nvr.data.repository.PreferencesRepository
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.beyond.nvr.ui.util.FormatUtils
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    recordingId: String,
    onBack: () -> Unit,
    viewModel: RecordingDetailViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
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

    // GSYVideoPlayer instance — held in state so DisposableEffect can release it
    val playerRef = remember { mutableStateOf<StandardGSYVideoPlayer?>(null) }

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

    // Release player when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            playerRef.value?.onVideoPause()
        }
    }

    if (uiState.deleted) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        topBar = {
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
                    if (uiState.cameraRecordings.isNotEmpty()) {
                        val prevIndex = uiState.currentIndex - 1
                        val nextIndex = uiState.currentIndex + 1
                        IconButton(
                            onClick = { viewModel.loadRecording(uiState.cameraRecordings[prevIndex].id) },
                            enabled = prevIndex >= 0,
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "上一个")
                        }
                        IconButton(
                            onClick = { viewModel.loadRecording(uiState.cameraRecordings[nextIndex].id) },
                            enabled = nextIndex < uiState.cameraRecordings.size,
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "下一个")
                        }
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                },
            )
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
                val startedDisplay = FormatUtils.formatTimestamp(recording.startedAt, "yyyy-MM-dd HH:mm:ss")
                val endedDisplay = FormatUtils.formatTimestamp(recording.endedAt, "yyyy-MM-dd HH:mm:ss")

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // ── Video Player ──
                    if (isPlayable && serverUrl.isNotBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
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
                                AndroidView(
                                    factory = { ctx ->
                                        StandardGSYVideoPlayer(ctx).apply {
                                            playerRef.value = this
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    } else if (uiState.recording?.format == "mjpeg") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Image,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "MJPEG 录像",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "不支持视频播放",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }

                    // Info card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            DetailRow(Icons.Default.Fingerprint, "ID", recording.id)
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                            DetailRow(Icons.Default.AccountCircle, "摄像头 ID", recording.cameraId)
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
                    }

                    // MJPEG frame info
                    if (recording.format == "mjpeg" && uiState.frames.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Collections,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "帧列表",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "总帧数：${uiState.frames.size}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    // ── Episode Selector ──
                    if (uiState.cameraRecordings.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.List,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "片段列表",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    itemsIndexed(uiState.cameraRecordings) { index, rec ->
                                        val isCurrent = index == uiState.currentIndex
                                        SuggestionChip(
                                            onClick = { viewModel.loadRecording(rec.id) },
                                            label = {
                                                Text(
                                                    text = FormatUtils.formatTimestamp(rec.startedAt, "HH:mm:ss"),
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            },
                                            icon = if (isCurrent) {
                                                { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                            } else null,
                                            shape = RoundedCornerShape(8.dp),
                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                containerColor = if (isCurrent)
                                                    MaterialTheme.colorScheme.primaryContainer
                                                else
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                labelColor = if (isCurrent)
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("返回")
                        }
                        Button(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("删除")
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("删除录像？")
                }
            },
            text = { Text("此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteRecording()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
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

