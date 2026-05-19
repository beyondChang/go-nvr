package com.beyond.nvr.ui.recordings

interface NvrPlayerListener {
    fun onPlayerStateChanged(state: Int)
    fun onPlayerPositionChanged(position: Long, total: Long)
}
