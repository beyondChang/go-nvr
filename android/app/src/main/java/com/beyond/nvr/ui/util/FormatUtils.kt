package com.beyond.nvr.ui.util

object FormatUtils {

    fun formatDuration(seconds: Double): String {
        val totalSecs = seconds.toLong()
        val h = totalSecs / 3600
        val m = (totalSecs % 3600) / 60
        val s = totalSecs % 60
        return buildString {
            if (h > 0) append("${h}时 ")
            if (m > 0 || h > 0) append("${m}分 ")
            append("${s}秒")
        }
    }

    fun formatDurationShort(seconds: Double): String {
        val hrs = (seconds / 3600).toInt()
        val mins = ((seconds % 3600) / 60).toInt()
        val secs = (seconds % 60).toInt()
        return if (hrs > 0) "${hrs}h ${mins}m ${secs}s"
        else if (mins > 0) "${mins}m ${secs}s"
        else "${secs}s"
    }

    fun formatFileSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return "%.1f %s".format(size, units[unitIndex])
    }
}
