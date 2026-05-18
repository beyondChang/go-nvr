package com.beyond.nvr.ui.util

import androidx.compose.ui.graphics.Color

object StatusUtils {

    data class StatusColors(
        val dot: Color,
        val bg: Color,
        val text: String,
    )

    /** Parse camera status into Chinese label + dot color + background tint. */
    fun parseStatus(status: String?): StatusColors {
        return when {
            status == null -> StatusColors(
                dot = Color.Gray,
                bg = Color.Gray.copy(alpha = 0.2f),
                text = "未知",
            )
            status.contains("recording", ignoreCase = true) || status.contains("connect", ignoreCase = true) || status.contains("online", ignoreCase = true) -> StatusColors(
                dot = Color(0xFF4CAF50),
                bg = Color(0xFF4CAF50).copy(alpha = 0.15f),
                text = "在线",
            )
            status.contains("error", ignoreCase = true) || status.contains("offline", ignoreCase = true) || status.contains("fail", ignoreCase = true) -> StatusColors(
                dot = Color(0xFFE53935),
                bg = Color(0xFFE53935).copy(alpha = 0.15f),
                text = "离线",
            )
            else -> StatusColors(
                dot = Color(0xFFFF9800),
                bg = Color(0xFFFF9800).copy(alpha = 0.15f),
                text = status,
            )
        }
    }

    /** Quick dot color only (used in list items). */
    fun statusDotColor(status: String?): Color = parseStatus(status).dot
}
