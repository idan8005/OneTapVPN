package com.example.onetapvpn

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import java.io.StringReader

internal class VpnManager(private val context: Context) {
    fun start(server: VpnServer, routing: RoutingSettings): Boolean = try {
        if (routing.mode == RoutingMode.SELECTED_APPS && routing.selectedPackages.isEmpty()) {
            throw IllegalArgumentException("Choose at least one app before connecting.")
        }

        val configBytes = Base64.decode(server.configBase64, Base64.DEFAULT)
        val configText = configBytes.toString(Charsets.UTF_8)
        val parser = ConfigParser()
        parser.parseConfig(StringReader(configText))
        val profile = parser.convertProfile()

        profile.mName = "Raahat VPN - ${server.country} - ${server.ip}"
        profile.mUsername = VPN_GATE_USERNAME
        profile.mPassword = VPN_GATE_PASSWORD
        profile.mProfileCreator = context.packageName

        // Always route the default IPv4 and IPv6 networks through the VPN.
        // Per-app selection is then applied by OpenVPN's Android VpnService.Builder.
        profile.mUseDefaultRoute = true
        profile.mUseDefaultRoutev6 = true
        profile.mAllowAppVpnBypass = false
        profile.mAllowedAppsVpn.clear()

        when (routing.mode) {
            RoutingMode.FULL_TUNNEL -> {
                // Empty deny-list means every app uses the VPN.
                profile.mAllowedAppsVpnAreDisallowed = true
            }
            RoutingMode.SELECTED_APPS -> {
                // Android's allow-list makes only the selected packages use the VPN.
                profile.mAllowedAppsVpnAreDisallowed = false
                profile.mAllowedAppsVpn.addAll(routing.selectedPackages)
            }
        }

        ProfileManager.setTemporaryProfile(context, profile)
        VPNLaunchHelper.startOpenVpn(profile, context, "Raahat VPN VPN Gate", true)
        true
    } catch (error: Exception) {
        Log.e(TAG, "Could not start VPN Gate server ${server.ip}", error)
        false
    }

    fun stop(): Boolean = try {
        ProfileManager.setConntectedVpnProfileDisconnected(context)
        val disconnect = Intent(context, OpenVPNService::class.java).apply {
            action = OpenVPNService.DISCONNECT_VPN
        }
        context.startService(disconnect)
        true
    } catch (error: Exception) {
        Log.e(TAG, "Could not stop OpenVPN service", error)
        false
    }

    private companion object {
        const val TAG = "RaahatVpnManager"
        const val VPN_GATE_USERNAME = "vpn"
        const val VPN_GATE_PASSWORD = "vpn"
    }
}
