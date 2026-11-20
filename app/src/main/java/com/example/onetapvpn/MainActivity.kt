package com.example.onetapvpn

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val viewModel: VpnViewModel by viewModels()

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var progressBar: ProgressBar
    private var latestState = VpnUiState()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.connect()
        else viewModel.onVpnPermissionDenied()
    }

    private val profilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val content = StringBuilder()
                        val buffer = CharArray(4096)
                        while (content.length <= WireGuardProfileValidator.MAX_PROFILE_CHARS) {
                            val remaining = WireGuardProfileValidator.MAX_PROFILE_CHARS + 1 - content.length
                            val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                            if (count < 0) break
                            content.append(buffer, 0, count)
                        }
                        require(content.length <= WireGuardProfileValidator.MAX_PROFILE_CHARS) {
                            getString(R.string.profile_too_large)
                        }
                        content.toString()
                    } ?: throw IllegalStateException(getString(R.string.cannot_read_profile))
                }
                viewModel.importProfile(profile)
            } catch (error: Exception) {
                detailText.text = getString(
                    R.string.import_failed,
                    error.localizedMessage ?: getString(R.string.unknown_error)
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 16, 31)
        window.navigationBarColor = Color.rgb(9, 16, 31)
        window.decorView.systemUiVisibility = 0

        setContentView(createContent())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshState()
    }

    private fun createContent(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(9, 16, 31))
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        scroll.addView(content)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_vpn_shield)
            contentDescription = getString(R.string.app_name)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label(getString(R.string.app_name), 22f, Color.WHITE, Typeface.BOLD))
        brand.addView(label(getString(R.string.app_tagline), 13f, Color.rgb(156, 174, 201)))
        header.addView(brand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })
        content.addView(header, matchWrap())

        val hero = card(Color.rgb(18, 31, 53), Color.rgb(42, 61, 89))
        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        heroContent.addView(label(getString(R.string.connection_title), 17f, Color.rgb(199, 215, 238)))
        statusText = label(getString(R.string.status_disconnected), 24f, Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
        }
        heroContent.addView(statusText, matchWrap().apply { topMargin = dp(8) })
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        heroContent.addView(progressBar, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            topMargin = dp(16)
        })

        connectButton = MaterialButton(this).apply {
            text = getString(R.string.connect)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            cornerRadius = dp(100)
            insetTop = dp(4)
            insetBottom = dp(4)
            setOnClickListener { onConnectionButtonPressed() }
        }
        heroContent.addView(connectButton, LinearLayout.LayoutParams(dp(172), dp(172)).apply {
            topMargin = dp(20)
            bottomMargin = dp(18)
        })
        detailText = label(getString(R.string.tap_to_connect), 14f, Color.rgb(164, 184, 213)).apply {
            gravity = Gravity.CENTER
        }
        heroContent.addView(detailText, matchWrap())
        hero.addView(heroContent)
        content.addView(hero, matchWrap().apply { topMargin = dp(28) })

        content.addView(label(getString(R.string.profile_section), 17f, Color.WHITE, Typeface.BOLD), matchWrap().apply {
            topMargin = dp(26)
            bottomMargin = dp(10)
        })
        val importCard = card(Color.rgb(14, 25, 44), Color.rgb(34, 51, 75))
        val importContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        importContent.addView(label(getString(R.string.profile_description), 14f, Color.rgb(174, 192, 218)))
        val chooseButton = MaterialButton(this).apply {
            text = getString(R.string.import_file)
            isAllCaps = false
            setOnClickListener {
                profilePicker.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
            }
        }
        importContent.addView(chooseButton, matchWrap().apply { topMargin = dp(12) })
        val pasteButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.paste_clipboard)
            isAllCaps = false
            setOnClickListener { importFromClipboard() }
        }
        importContent.addView(pasteButton, matchWrap().apply { topMargin = dp(8) })
        importCard.addView(importContent)
        content.addView(importCard, matchWrap())

        content.addView(label(getString(R.string.warp_note), 12f, Color.rgb(133, 153, 182)), matchWrap().apply {
            topMargin = dp(18)
        })
        return scroll
    }

    private fun onConnectionButtonPressed() {
        when (latestState.status) {
            VpnStatus.CONNECTED -> viewModel.disconnect()
            VpnStatus.CONNECTING, VpnStatus.DISCONNECTING -> Unit
            else -> {
                val permissionIntent = VpnService.prepare(this)
                if (permissionIntent == null) viewModel.connect()
                else vpnPermissionLauncher.launch(permissionIntent)
            }
        }
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            detailText.text = getString(R.string.clipboard_empty)
            return
        }
        viewModel.importProfile(text)
    }

    private fun render(state: VpnUiState) {
        latestState = state
        statusText.text = when (state.status) {
            VpnStatus.CONNECTED -> getString(R.string.status_connected)
            VpnStatus.CONNECTING -> getString(R.string.status_connecting)
            VpnStatus.DISCONNECTING -> getString(R.string.status_disconnecting)
            VpnStatus.ERROR -> getString(R.string.status_error)
            VpnStatus.DISCONNECTED -> getString(R.string.status_disconnected)
        }
        detailText.text = state.message
        progressBar.visibility = if (state.isBusy) View.VISIBLE else View.GONE
        connectButton.isEnabled = !state.isBusy
        connectButton.text = when (state.status) {
            VpnStatus.CONNECTED -> getString(R.string.disconnect)
            VpnStatus.CONNECTING -> getString(R.string.connecting_button)
            VpnStatus.DISCONNECTING -> getString(R.string.disconnecting_button)
            else -> getString(R.string.connect)
        }
        connectButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (state.status == VpnStatus.CONNECTED) Color.rgb(190, 62, 70)
            else Color.rgb(31, 144, 109)
        )
        connectButton.alpha = if (state.isBusy) 0.7f else 1f
    }

    private fun card(background: Int, stroke: Int): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(22).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(background)
        strokeWidth = dp(1)
        setStrokeColor(stroke)
    }

    private fun label(
        text: String,
        size: Float,
        color: Int,
        style: Int = Typeface.NORMAL
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(Typeface.DEFAULT, style)
        gravity = Gravity.START
        textDirection = View.TEXT_DIRECTION_LOCALE
        includeFontPadding = true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
}
