package com.beyond.nvr.ui.recordings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beyond.nvr.data.model.Recording
import org.koin.compose.viewmodel.koinViewModel
import com.beyond.nvr.ui.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    onBack: () -> Unit,
    onRecordingClick: (String) -> Unit,
    viewModel: RecordingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showSearch) {
                        TextField(
                            value = searchText,
                            onValueChange = {
                                searchText = it
                                viewModel.search(it)
                            },
                            placeholder = { Text("搜索录像…") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            ),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PlayCircle,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("录像")
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showSearch = !showSearch
                        if (!showSearch) {
                            searchText = ""
                            viewModel.search("")
                        }
                    }) {
                        Icon(
                            if (showSearch) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "搜索",
                        )
                    }
                    IconButton(onClick = { viewModel.loadRecordings() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    if (uiState.selectedIds.isNotEmpty()) {
                        IconButton(onClick = { viewModel.deleteSelected() }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除选中")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Camera filter chips
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        FilterChip(
                            selected = uiState.selectedCameraId == null,
                            onClick = { viewModel.selectCamera(null) },
                            label = { Text("全部") },
                            leadingIcon = if (uiState.selectedCameraId == null) {
                                { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                            } else null,
                        )
                        uiState.cameras.forEach { camera ->
                            FilterChip(
                                selected = uiState.selectedCameraId == camera.id,
                                onClick = { viewModel.selectCamera(camera.id) },
                                label = { Text(camera.name) },
                                leadingIcon = if (uiState.selectedCameraId == camera.id) {
                                    { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                                } else null,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (uiState.isLoading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    strokeWidth = 4.dp,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "加载中…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else if (uiState.recordings.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.VideoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (uiState.selectedCameraId != null) "未找到录像" else "暂无录像",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "换个设备试试？",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            text = "共 ${uiState.totalRecordings} 条录像 · 第 ${uiState.currentPage + 1} 页",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    items(uiState.recordings, key = { it.id }) { recording ->
                        RecordingListItem(
                            recording = recording,
                            isSelected = recording.id in uiState.selectedIds,
                            onClick = { onRecordingClick(recording.id) },
                            onToggleSelect = { viewModel.toggleSelection(recording.id) },
                        )
                    }

                    // Pagination controls
                    item {
                        val totalPages = if (uiState.totalRecordings > 0)
                            (uiState.totalRecordings + uiState.pageSize - 1) / uiState.pageSize
                        else 1
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { viewModel.previousPage() },
                                enabled = uiState.currentPage > 0,
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("上一页")
                            }

                            Text(
                                text = "第 ${uiState.currentPage + 1}/${totalPages} 页",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            TextButton(
                                onClick = { viewModel.nextPage() },
                                enabled = uiState.currentPage + 1 < totalPages,
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("下一页")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingListItem(
    recording: Recording,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    val displayDate = FormatUtils.formatTimestamp(recording.startedAt)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Play icon
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = recording.cameraId.take(8),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Format badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (recording.merged) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = recording.format.uppercase(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (recording.merged) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = " · ${FormatUtils.formatDurationShort(recording.duration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = FormatUtils.formatFileSize(recording.fileSize),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                if (recording.merged) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SuggestionChip(
                        onClick = {},
                        label = { Text("已合并", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(24.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
            )
        }
    }
}


