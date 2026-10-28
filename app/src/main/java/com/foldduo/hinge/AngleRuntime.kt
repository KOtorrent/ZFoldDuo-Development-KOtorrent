package com.foldduo.hinge

import android.content.Context
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsConfig
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.util.concurrent.atomic.AtomicBoolean

data class AngleSample(
    val angle: Float,
    val timestampNanos: Long,
)

/** One process-wide ADB connection shared by the activity and accessibility overlay. */
object AngleRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connecting = AtomicBoolean(false)
    private val pairing = AtomicBoolean(false)
    private val displayCommand = Mutex()
    private val _status = MutableStateFlow("Waiting for wireless debugging")
    private val _sample = MutableStateFlow<AngleSample?>(null)
    private val _pairEndpoint = MutableStateFlow<MdnsEndpoint?>(null)
    private val _connectEndpoint = MutableStateFlow<MdnsEndpoint?>(null)

    val status = _status.asStateFlow()
    val sample = _sample.asStateFlow()
    val pairEndpoint = _pairEndpoint.asStateFlow()
    val connectEndpoint = _connectEndpoint.asStateFlow()

    private var initialized = false
    private lateinit var mdns: KadbMdnsAndroid
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var client: EmbeddedAdbAngleClient
    private var fallbackPairHost: String? = null
    private var fallbackPairPort: Int? = null
    private var fallbackConnectHost: String? = null
    private var fallbackConnectPort: Int? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        // Some Wi-Fi implementations filter mDNS multicast packets without a lock.
        runCatching {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("ZFoldDuo-mDNS").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure {
            _status.value = "mDNS multicast lock unavailable: ${it.message}"
        }
        KadbCert.configure(OkioFilePrivateKeyStore(app.filesDir.resolve("adb_private_key.pem").path.toPath()))
        client = EmbeddedAdbAngleClient(
            onStatus = {
                _status.value = it
                if (it.contains("disconnected", ignoreCase = true) || it.contains("stopped", ignoreCase = true)) _sample.value = null
            },
            onAngle = { angle, timestamp ->
                _sample.value = AngleSample(angle, timestamp)
            },
        )
        mdns = KadbMdnsAndroid(
            app,
            MdnsConfig(setOf(MdnsServiceType.TLS_CONNECT, MdnsServiceType.TLS_PAIRING)),
        )
        scope.launch {
            mdns.state.collect { state ->
                _pairEndpoint.value = state.pairDevices.firstOrNull()
                _connectEndpoint.value = state.connectDevices.firstOrNull()
            }
        }
        mdns.start()
        // Platform NSD fallback: Android 17 may not return pairing endpoints
        // through Kadb's multicast discovery even when Wireless debugging is on.
        val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        fun discover(serviceType: String, onResolved: (String, Int) -> Unit) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    _status.value = "NSD discovery failed ($type): $code"
                }
                override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
                override fun onServiceLost(service: NsdServiceInfo) = Unit
                override fun onServiceFound(service: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            if (info.port in 1..65535) onResolved(host, info.port)
                        }
                    })
                }
            }
            runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
        }
        discover("_adb-tls-pairing._tcp.") { host, port ->
            fallbackPairHost = host
            fallbackPairPort = port
            if (_pairEndpoint.value == null) _status.value = "Pairing service discovered via Android NSD"
        }
        discover("_adb-tls-connect._tcp.") { host, port ->
            fallbackConnectHost = host
            fallbackConnectPort = port
        }
        scope.launch {
            while (isActive) {
                val endpoint = _connectEndpoint.value
                val host = endpoint?.host ?: fallbackConnectHost
                val port = endpoint?.port ?: fallbackConnectPort
                if (host != null && port != null && !client.isConnected && connecting.compareAndSet(false, true)) {
                    try {
                        _sample.value = null
                        client.connect(host, port)
                    } catch (_: Throwable) {
                        _status.value = "ADB pairing required"
                    } finally {
                        connecting.set(false)
                    }
                }
                delay(if (host == null) 500L else 2_500L)
            }
        }
    }

    suspend fun pair(code: String, manualHost: String? = null, manualPort: Int? = null): Result<Unit> {
        val discovered = _pairEndpoint.value
        val host = manualHost?.trim()?.takeIf { it.isNotEmpty() } ?: discovered?.host ?: fallbackPairHost
        val port = manualPort ?: discovered?.port ?: fallbackPairPort
        if (host == null || port == null || port !in 1..65535) {
            val error = IllegalStateException("Pairing service not discovered. Enter the IP and pairing port from Android's pairing popup.")
            _status.value = error.message.orEmpty()
            return Result.failure(error)
        }
        if (code.length != 6 || !code.all(Char::isDigit)) {
            val error = IllegalArgumentException("Enter the six-digit pairing code")
            _status.value = error.message.orEmpty()
            return Result.failure(error)
        }
        if (!pairing.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("Pairing already in progress"))
        }
        return try {
            _status.value = "Pairing"
            Kadb.pair(host, port, code, "ZFoldDuo")
            _status.value = "Paired; connecting"
            Result.success(Unit)
        } catch (error: Throwable) {
            _status.value = "Pairing failed: ${safeMessage(error)}"
            Result.failure(error)
        } finally {
            pairing.set(false)
        }
    }

    fun prepareCoverDisplay() {
        scope.launch {
            displayCommand.withLock {
                // Keep the inner panel as logical display 0 and expose the
                // cover as display 1. State 5 does the reverse and visibly
                // blanks/remaps the inner panel during a close.
                if (client.shell("cmd device_state state 4").isSuccess) {
                    client.shell("cmd display enable-display 1")
                    client.shell("cmd display power-reset 1")
                }
            }
        }
    }

    fun releasePreparedCoverDisplay() {
        scope.launch {
            displayCommand.withLock {
                client.shell("cmd device_state state reset")
            }
        }
    }

    fun setDisplayWakeLock(displayId: Int, held: Boolean) {
        if (displayId < 0) return
        scope.launch {
            displayCommand.withLock {
                val operation = if (held) "acquire" else "release"
                if (held) {
                    val status = client.shell("cmd power set-wakelock list").getOrNull().orEmpty()
                    if (!displayWakeLockHeld(status, displayId)) {
                        client.shell("cmd power set-wakelock $operation -d $displayId FULL_WAKE_LOCK")
                    }
                } else {
                    // Older builds acquired the same shell lock repeatedly.
                    // Drain its reference count completely so it cannot leak
                    // across several open/close cycles.
                    repeat(MAX_WAKE_LOCK_RELEASES) {
                        val status = client.shell("cmd power set-wakelock list").getOrNull().orEmpty()
                        if (!displayWakeLockHeld(status, displayId)) return@withLock
                        client.shell("cmd power set-wakelock $operation -d $displayId FULL_WAKE_LOCK")
                    }
                }
            }
        }
    }

    fun isPreparedCoverDisplayPhysicallyClosed(onResult: (Boolean) -> Unit) {
        scope.launch {
            val closed = displayCommand.withLock {
                val state = client.shell("dumpsys device_state").getOrNull().orEmpty()
                state.contains(
                    "mBaseState=Optional[DeviceState{identifier=0, name='CLOSED'",
                )
            }
            withContext(Dispatchers.Main.immediate) { onResult(closed) }
        }
    }

    /**
     * State 4 keeps the inner panel as display 0 and the cover as display 1.
     * Resetting it directly at CLOSED makes Samsung power-cycle the cover.
     * Promote the already-lit cover to display 0 through state 5 first; the
     * final reset then only removes the hidden inner display.
     */
    fun finishClosedDisplayHandoff(onComplete: () -> Unit) {
        scope.launch {
            displayCommand.withLock {
                client.shell("cmd device_state state 5")
                for (attempt in 0 until COVER_PROMOTION_POLL_COUNT) {
                    val state = client.shell("dumpsys device_state").getOrNull().orEmpty()
                    if (state.contains(
                            "mCommittedState=Optional[DeviceState{identifier=5, " +
                                "name='CONCURRENT_OUTER_DEFAULT'",
                        )
                    ) break
                    delay(COVER_PROMOTION_POLL_MS)
                }
                client.shell("cmd device_state state reset")
            }
            withContext(Dispatchers.Main.immediate) { onComplete() }
        }
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private fun displayWakeLockHeld(status: String, displayId: Int): Boolean {
        val lock = "Display $displayId, wakelock type: FULL_WAKE_LOCK:"
        return status.lineSequence().any { it.contains(lock) && it.contains("held=true") }
    }

    private const val MAX_WAKE_LOCK_RELEASES = 32
    private const val COVER_PROMOTION_POLL_COUNT = 20
    private const val COVER_PROMOTION_POLL_MS = 16L
}
