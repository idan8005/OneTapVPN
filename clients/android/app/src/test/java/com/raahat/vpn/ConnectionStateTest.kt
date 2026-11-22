package com.raahat.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStateTest {
    @Test
    fun initialStateIsDisconnected() {
        assertEquals(ConnectionState.DISCONNECTED, ConnectionState.initial)
    }
}
