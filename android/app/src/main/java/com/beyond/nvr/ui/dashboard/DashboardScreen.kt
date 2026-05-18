package com.beyond.nvr.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DashboardScreen(
    onNavigateToCameras: () -> Unit,
    onNavigateToRecordings: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    viewModel: DashboardViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                    ),
                )
            ),
    ) {
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.primary,
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
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = uiState.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    FilledTonalButton(onClick = { viewModel.loadData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("重试")
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                // 测量 header 实际高度，用于动态定位卡片（适配不同设备状态栏高度）
                var headerHeight by remember { mutableIntStateOf(0) }

                // ═══ Header Section ═══
                DashboardHeader(
                    onlineCount = uiState.onlineCount,
                    offlineCount = uiState.offlineCount,
                    totalCameras = uiState.cameras.size,
                    recordingCount = uiState.stats?.recordingCount ?: 0,
                    onRefresh = { viewModel.loadData() },
                    showMenu = showMenu,
                    onShowMenu = { showMenu = true },
                    onDismissMenu = { showMenu = false },
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToStats = onNavigateToStats,
                    onLogout = onLogout,
                    viewModel = viewModel,
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        headerHeight = coordinates.size.height
                    },
                )

                // ═══ Navigation Cards — 叠加在头部下方，产生半包效果 ═══
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 16.dp)
                        .padding(top = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    CameraBigCard(
                        onClick = onNavigateToCameras,
                        onlineCount = uiState.onlineCount,
                        totalCameras = uiState.cameras.size,
                    )
                    RecordingsBigCard(
                        onClick = onNavigateToRecordings,
                        recordingCount = uiState.stats?.recordingCount ?: 0,
                        storageUsedPercent = if (uiState.stats != null) {
                            (uiState.stats!!.usedBytes.toFloat() / uiState.stats!!.totalBytes.coerceAtLeast(1)) * 100f
                        } else null,
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  Header — 异形渐变背景 + 状态指示 + 统计迷你卡片
// ════════════════════════════════════════════════════════════════
@Composable
private fun DashboardHeader(
    onlineCount: Int,
    offlineCount: Int,
    totalCameras: Int,
    recordingCount: Int,
    onRefresh: () -> Unit,
    showMenu: Boolean,
    onShowMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStats: () -> Unit,
    onLogout: () -> Unit,
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
) {
    // 美丽的三色渐变（深紫 → 靛蓝 → 亮紫）
    val headerGradient = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF1E1B4B),  // 深靛蓝
                Color(0xFF312E81),  // 靛蓝
                Color(0xFF6366F1),  // 亮紫蓝
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val w = size.width
                val h = size.height
                val arcH = 48.dp.toPx()  // 底部弧线拱起高度

                val path = Path().apply {
                    // 矩形主体
                    moveTo(0f, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h)
                    // 底部弧线：右侧 → 左侧，中间向上拱起
                    quadraticTo(
                        w * 0.5f, h - arcH,  // 控制点在中间上方
                        0f, h,                 // 结束于左下
                    )
                    close()
                }
                drawPath(path, headerGradient)
            }
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 60.dp),  // 底部留出弧线空白
    ) {
        // ── Top bar: avatar + status + actions ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar circle
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (totalCameras > 0 && onlineCount > 0)
                                Color(0xFF00E676)
                            else
                                Color(0xFFFF5252),
                        ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (totalCameras > 0 && onlineCount > 0)
                        "$onlineCount 个设备在线"
                    else
                        "无设备在线",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }

            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "刷新",
                    tint = Color.White.copy(alpha = 0.85f),
                )
            }

            Box {
                IconButton(onClick = onShowMenu) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "菜单",
                        tint = Color.White.copy(alpha = 0.85f),
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = onDismissMenu,
                ) {
                    DropdownMenuItem(
                        text = { Text("设置") },
                        onClick = {
                            onDismissMenu()
                            onNavigateToSettings()
                        },
                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("统计") },
                        onClick = {
                            onDismissMenu()
                            onNavigateToStats()
                        },
                        leadingIcon = { Icon(Icons.Default.Assessment, null) },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("退出登录") },
                        onClick = {
                            onDismissMenu()
                            viewModel.logout(onLogout)
                        },
                        leadingIcon = { Icon(Icons.Default.ExitToApp, null) },
                    )
                }
            }
        }

        // ── Stats mini-cards (2×2 grid) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatMiniCard(
                icon = Icons.Default.Videocam,
                label = "设备",
                value = "$totalCameras",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            StatMiniCard(
                icon = Icons.Default.CheckCircle,
                label = "在线",
                value = "$onlineCount",
                color = Color(0xFF00E676),
                modifier = Modifier.weight(1f),
            )
            StatMiniCard(
                icon = Icons.Default.ErrorOutline,
                label = "离线",
                value = "$offlineCount",
                color = if (offlineCount > 0) Color(0xFFFF5252) else Color(0xFF90A4AE),
                modifier = Modifier.weight(1f),
            )
            StatMiniCard(
                icon = Icons.Default.VideoLibrary,
                label = "录像",
                value = "$recordingCount",
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  StatMiniCard — 小巧的统计卡片
// ════════════════════════════════════════════════════════════════
@Composable
private fun StatMiniCard(
    icon: ImageVector,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  CameraBigCard — 渐变设备卡片
// ════════════════════════════════════════════════════════════════
@Composable
private fun CameraBigCard(
    onClick: () -> Unit,
    onlineCount: Int,
    totalCameras: Int,
    modifier: Modifier = Modifier,
) {
    val gradientColors = listOf(
        Color(0xFF005050),
        Color(0xFF003040),
    )
    val lightGradient = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
    )

    val isDark = MaterialTheme.colorScheme.background == Color(0xFF0A0A0F)
    val colors = if (isDark) gradientColors else lightGradient
    val textColor = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(
                    Brush.linearGradient(
                        colors = colors,
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                ),
            contentAlignment = Alignment.Center
        ) {
            // Decorative circles
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 20.dp, y = (-20).dp)
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.04f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 30.dp, y = 30.dp)
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.03f)),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: icon
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = textColor.copy(alpha = 0.9f),
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Center: text
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "设备",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = "查看和管理所有设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.85f),
                    )
                    if (totalCameras > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E676)),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "$onlineCount / $totalCameras 在线",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }
                }

                // Right: arrow
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  RecordingsBigCard — 渐变录像卡片
// ════════════════════════════════════════════════════════════════
@Composable
private fun RecordingsBigCard(
    onClick: () -> Unit,
    recordingCount: Int,
    storageUsedPercent: Float?,
    modifier: Modifier = Modifier,
) {
    val gradientColors = listOf(
        Color(0xFF4A0080),
        Color(0xFF2D0050),
    )
    val lightGradient = listOf(
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
    )

    val isDark = MaterialTheme.colorScheme.background == Color(0xFF0A0A0F)
    val colors = if (isDark) gradientColors else lightGradient
    val textColor = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(
                    Brush.linearGradient(
                        colors = colors,
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                ),
        ) {
            // Decorative circles
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-30).dp, y = (-10).dp)
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.04f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 20.dp, y = 20.dp)
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.03f)),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: icon
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = Color.White.copy(alpha = 0.9f),
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Center: text
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "录像",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = "浏览和回放录像",
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.8f),
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    if (recordingCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.VideoLibrary,
                                contentDescription = null,
                                tint = textColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "共 $recordingCount 个录像",
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.85f),
                            )
                        }
                    }

                    // Storage progress bar
                    if (storageUsedPercent != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val animatedProgress by animateFloatAsState(
                            targetValue = storageUsedPercent / 100f,
                            animationSpec = tween(1000),
                            label = "storage",
                        )
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFFFFAA33),
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                    }
                }

                // Right: arrow
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = textColor.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
