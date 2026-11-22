package com.raahat.vpn

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED;

    companion object {
        val initial = DISCONNECTED
    }
}
