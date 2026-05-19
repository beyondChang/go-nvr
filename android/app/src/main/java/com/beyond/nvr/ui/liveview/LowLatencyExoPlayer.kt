package com.beyond.nvr.ui.liveview

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * 低延迟 ExoPlayer，用于 HLS 直播流播放。
 *
 * 相比 GSYVideoPlayer 默认的 ExoPlayer 配置，这里大幅缩小了缓冲区：
 * - minBufferMs: 500ms (默认 15s)
 * - maxBufferMs: 3s (默认 50s)
 * - bufferForPlayback: 300ms (默认 2.5s)
 * - bufferForPlaybackAfterRebuffer: 500ms (默认 5s)
 *
 * 同时设置了 LiveConfiguration 使播放器靠近直播边缘。
 */
@UnstableApi
@Composable
fun LowLatencyExoPlayerView(
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs                */ 500,
                        /* maxBufferMs                */ 3000,
                        /* bufferForPlaybackMs        */ 300,
                        /* bufferForPlaybackAfterRebufferMs */ 500,
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
            .apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    // 当 URL 变化时更新播放源
    LaunchedEffect(url) {
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(5_000) // 目标落后 5s
                    .setMinOffsetMs(3_000)    // 最小落后 3s
                    .setMaxOffsetMs(8_000)    // 最大落后 8s
                    .build()
            )
            .build()
        val dataSourceFactory = DefaultHttpDataSource.Factory()
        val hlsMediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)

        exoPlayer.apply {
            stop()
            setMediaSource(hlsMediaSource)
            prepare()
            playWhenReady = true
        }
    }

    // 清理资源
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also { surfaceView ->
                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        exoPlayer.setVideoSurface(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        exoPlayer.setVideoSurface(null)
                    }
                })
            }
        },
        modifier = modifier,
    )
}
