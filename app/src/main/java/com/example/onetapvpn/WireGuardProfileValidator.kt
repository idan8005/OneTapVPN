package com.example.onetapvpn

import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

/** Performs local validation before a profile is encrypted or passed to the VPN backend. */
internal object WireGuardProfileValidator {
    const val MAX_PROFILE_CHARS = 64 * 1024

    fun parse(profileText: String): Config {
        require(profileText.isNotBlank()) { "The WireGuard profile is empty." }
        require(profileText.length <= MAX_PROFILE_CHARS) { "The WireGuard profile is too large." }

        val config = Config.parse(BufferedReader(StringReader(profileText)))
        require(config.getPeers().isNotEmpty()) { "The WireGuard profile must contain at least one peer." }
        require(config.getInterface().getAddresses().isNotEmpty()) {
            "The WireGuard interface must contain at least one IP address."
        }
        return config
    }
}
