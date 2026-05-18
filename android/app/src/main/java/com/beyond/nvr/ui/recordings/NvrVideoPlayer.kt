package com.beyond.nvr.ui.recordings

import android.content.Context
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.beyond.nvr.R

/**
 * Custom GSYVideoPlayer that:
 *  - Uses a minimal layout (no default GSY control widgets)
 *  - Disables default touch handling (touchSurfaceMove, showWifiDialog)
 *  - Reports state/position changes via [NvrPlayerListener]
 */
class NvrVideoPlayer(context: Context) : StandardGSYVideoPlayer(context) {

    private var playerListener: NvrPlayerListener? = null

    fun setPlayerListener(listener: NvrPlayerListener?) {
        playerListener = listener
    }

    init {
        // Don't release on loss of audio focus, just pause
        isReleaseWhenLossAudio = false
    }

    override fun getLayoutId(): Int = R.layout.layout_player

    override fun setStateAndUi(state: Int) {
        playerListener?.onPlayerStateChanged(state)
        super.setStateAndUi(state)
    }

    override fun setProgressAndTime(
        progress: Long,
        secProgress: Long,
        currentTime: Long,
        totalTime: Long,
        forceChange: Boolean,
    ) {
        playerListener?.onPlayerPositionChanged(currentTime, totalTime)
        super.setProgressAndTime(progress, secProgress, currentTime, totalTime, forceChange)
    }

    // ── disable default GSY touch/UI ──
    override fun touchSurfaceMove(deltaX: Float, deltaY: Float, y: Float) {}
    override fun touchSurfaceUp() {}
    override fun showWifiDialog() {}

    // Expose protected toggle as public (for Compose control overlay)
    public override fun clickStartIcon() {
        super.clickStartIcon()
    }

    fun pause() = onVideoPause()
    fun resume() = onVideoResume()
}
