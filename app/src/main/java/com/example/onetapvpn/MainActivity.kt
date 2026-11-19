package com.example.onetapvpn

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader
import kotlin.concurrent.thread

/**
 * One-tap WireGuard client.
 *
 * Flow:
 *  1. Paste your WireGuard config once, or let the app request a WARP profile.
 *  2. Tap the main button to connect or disconnect.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var backend: Backend
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var importButton: Button

    private var connected = false

    private val tunnel = object : Tunnel {
        override fun getName() = "onetap"
        override fun onStateChange(newState: Tunnel.State) {
            runOnUiThread { render(newState == Tunnel.State.UP) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(applicationContext)
        buildUi()
        render(false)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        statusText = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
        }
        toggleButton = Button(this).apply {
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setOnClickListener { onToggle() }
        }
        importButton = Button(this).apply {
            text = "Advanced: use my own WireGuard config (clipboard)"
            setOnClickListener { importFromClipboard() }
        }
        val size = (resources.displayMetrics.density * 180).toInt()
        root.addView(statusText)
        root.addView(toggleButton, LinearLayout.LayoutParams(size, size).apply {
            topMargin = 64
            bottomMargin = 64
        })
        root.addView(importButton)
        setContentView(root)
    }

    private fun render(isUp: Boolean) {
        connected = isUp
        toggleButton.text = if (isUp) "OFF" else "ON"
        toggleButton.setBackgroundColor(
            if (isUp) Color.parseColor("#C62828") else Color.parseColor("#2E7D32")
        )
        statusText.text = if (isUp) "Connected" else "Tap to connect"
    }

    private fun onToggle() {
        if (connected) {
            setTunnelState(Tunnel.State.DOWN)
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VPN)
        } else {
            setTunnelState(Tunnel.State.UP)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == Activity.RESULT_OK) {
            setTunnelState(Tunnel.State.UP)
        }
    }

    private fun setTunnelState(state: Tunnel.State) {
        thread {
            try {
                var text = loadConfig()
                if (text == null) {
                    runOnUiThread { statusText.text = "Setting up..." }
                    text = WarpProvisioner.provision()
                    getSharedPreferences("vpn", MODE_PRIVATE).edit().putString("config", text).apply()
                }
                val config = Config.parse(BufferedReader(StringReader(text)))
                backend.setState(tunnel, state, config)
            } catch (e: Exception) {
                runOnUiThread {
                    render(false)
                    toast("Error: ${e.message}")
                }
            }
        }
    }

    private fun importFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            toast("Clipboard is empty")
            return
        }
        try {
            Config.parse(BufferedReader(StringReader(text)))
            getSharedPreferences("vpn", MODE_PRIVATE).edit().putString("config", text).apply()
            toast("Config saved")
            render(connected)
        } catch (e: Exception) {
            toast("Invalid config: ${e.message}")
        }
    }

    private fun loadConfig(): String? =
        getSharedPreferences("vpn", MODE_PRIVATE).getString("config", null)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_VPN = 1001
    }
}
