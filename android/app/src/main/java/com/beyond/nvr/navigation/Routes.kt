package com.beyond.nvr.navigation

sealed class Routes(val route: String) {
    data object Login : Routes("login")
    data object Dashboard : Routes("dashboard")
    data object Cameras : Routes("cameras")
    data object LiveView : Routes("live_view/{cameraId}") {
        fun createRoute(cameraId: String) = "live_view/$cameraId"
    }
    data object Recordings : Routes("recordings")
    data object RecordingDetail : Routes("recording/{recordingId}") {
        fun createRoute(recordingId: String) = "recording/$recordingId"
    }
    data object Settings : Routes("settings")
    data object Stats : Routes("stats")
}
