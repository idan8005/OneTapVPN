package com.example.onetapvpn

internal object IranVpnContract {
    const val ACTION_STATUS = "com.example.onetapvpn.iran.STATUS"
    const val EXTRA_STATE = "state"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_ACTIVE_TAG = "active_outbound_tag"
    const val EXTRA_SERVER_TAG = "server_tag"
    const val EXTRA_PING_MS = "ping_ms"
    const val EXTRA_SPEED_MBPS = "speed_mbps"
    const val EXTRA_HEALTH_STATUS = "health_status"
    const val STATE_CONNECTED = "connected"
    const val STATE_DISCONNECTED = "disconnected"
    const val STATE_ERROR = "error"
    const val STATE_SERVER_HEALTH = "server_health"
}
