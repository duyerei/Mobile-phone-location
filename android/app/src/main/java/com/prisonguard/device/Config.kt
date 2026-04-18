package com.prisonguard.device

object Config {
    // ⚠️ 修改为实际服务器地址，调试时填局域网 IP，如 http://192.168.1.100:8000
    const val SERVER_URL = "http://YOUR_SERVER_IP:8000"

    // 定位上报间隔（毫秒），默认5分钟，可由服务端动态下发修改
    const val LOCATION_INTERVAL_MS_DEFAULT = 5 * 60 * 1000L  // 5分钟
    const val LOCATION_INTERVAL_MS_MIN     = 30 * 1000L       // 最短30秒

    // 设备 ID（首次启动自动生成并持久化）
    const val PREF_DEVICE_ID      = "device_id"
    const val PREF_NAME           = "prefs"
    const val PREF_LOCATION_INTERVAL = "location_interval"
}
