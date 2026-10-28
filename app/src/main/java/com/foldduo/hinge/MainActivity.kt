package com.foldduo.hinge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.foldduo.hinge.overlay.HingeOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var statusText: TextView
    private lateinit var angleText: TextView
    private lateinit var detailText: TextView
    private lateinit var rateText: TextView
    private lateinit var setupPanel: View
    private lateinit var endpointText: TextView
    private lateinit var codeInput: EditText
    private lateinit var manualEndpoint: EditText
    private lateinit var pairButton: Button
    private lateinit var overlayStatus: TextView
    private lateinit var demoButton: Button
    private var previousTimestamp = 0L
    private var updateHz = Float.NaN

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        statusText = findViewById(R.id.statusText)
        angleText = findViewById(R.id.angleText)
        detailText = findViewById(R.id.detailText)
        rateText = findViewById(R.id.rateText)
        setupPanel = findViewById(R.id.setupPanel)
        endpointText = findViewById(R.id.endpointText)
        codeInput = findViewById(R.id.codeInput)
        manualEndpoint = findViewById(R.id.manualEndpoint)
        pairButton = findViewById(R.id.pairButton)
        overlayStatus = findViewById(R.id.overlayStatus)
        demoButton = findViewById(R.id.demoButton)

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        demoButton.setOnClickListener { HingeOverlayService.instance?.playDemo() }
        pairButton.setOnClickListener { pair() }

        scope.launch { AngleRuntime.status.collectLatest(::showStatus) }
        scope.launch { AngleRuntime.diagnostics.collectLatest { value -> runOnUiThread { detailText.text = value } } }
        scope.launch { AngleRuntime.sensorInventory.collectLatest { value -> runOnUiThread { endpointText.text = value } } }
        scope.launch { AngleRuntime.sample.collectLatest { it?.let(::render) } }
        scope.launch { AngleRuntime.pairEndpoint.collectLatest { runOnUiThread(::renderDiscovery) } }
        scope.launch { AngleRuntime.connectEndpoint.collectLatest { runOnUiThread(::renderDiscovery) } }
        requestLocalNetworkPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        val enabled = HingeOverlayService.isEnabled(this)
        overlayStatus.text = if (enabled) "Screen overlay ON" else "Screen overlay OFF"
        demoButton.isEnabled = enabled && HingeOverlayService.instance != null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun pair() {
        val code = codeInput.text.toString().filter(Char::isDigit)
        if (code.length != 6) {
            statusText.text = "Enter the six-digit pairing code"
            Toast.makeText(this, "Enter the six-digit pairing code", Toast.LENGTH_LONG).show()
            return
        }
        val endpointInput = manualEndpoint.text.toString().trim()
        val manualHost: String?
        val manualPort: Int?
        if (endpointInput.isNotEmpty()) {
            val parts = endpointInput.split(":")
            val port = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || parts[0].isBlank() || port == null || port !in 1..65535) {
                statusText.text = "Enter the pairing address as IP:port"
                Toast.makeText(this, "Enter the pairing address as IP:port", Toast.LENGTH_LONG).show()
                return
            }
            manualHost = parts[0]
            manualPort = port
        } else {
            manualHost = null
            manualPort = null
        }
        pairButton.isEnabled = false
        statusText.text = "Pairing requested..."
        scope.launch {
            val result = runCatching { AngleRuntime.pair(code, manualHost, manualPort).getOrThrow() }
            runOnUiThread {
                pairButton.isEnabled = true
                if (result.isSuccess) {
                    statusText.text = "Pairing succeeded. Connecting..."
                    Toast.makeText(this@MainActivity, "Pairing succeeded", Toast.LENGTH_LONG).show()
                    codeInput.text.clear()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(codeInput.windowToken, 0)
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown pairing error"
                    statusText.text = "Pairing failed: $error"
                    Toast.makeText(this@MainActivity, "Pairing failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderDiscovery() {
        val pair = AngleRuntime.pairEndpoint.value
        val connect = AngleRuntime.connectEndpoint.value
        endpointText.text = when {
            pair != null -> "Pairing service discovered on port ${pair.port}"
            connect != null -> "Wireless debugging detected"
            else -> "Waiting for wireless debugging"
        }
    }

    private fun showStatus(value: String) = runOnUiThread {
        statusText.text = value
        setupPanel.visibility = if (AngleRuntime.sample.value != null) View.GONE else View.VISIBLE
    }

    private fun render(sample: AngleSample) = runOnUiThread {
        if (previousTimestamp > 0 && sample.timestampNanos > previousTimestamp) {
            val instant = 1_000_000_000f / (sample.timestampNanos - previousTimestamp)
            updateHz = if (updateHz.isFinite()) updateHz * 0.85f + instant * 0.15f else instant
        }
        previousTimestamp = sample.timestampNanos
        setupPanel.visibility = View.GONE
        angleText.text = String.format(Locale.getDefault(), "%.3f°", sample.angle)
        rateText.text = if (updateHz.isFinite()) String.format(Locale.getDefault(), "%.1f Hz", updateHz) else "— Hz"
    }

    private fun requestLocalNetworkPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 37)
    }
}
