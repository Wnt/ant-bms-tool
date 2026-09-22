package dev.bmstool.ant.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import dev.bmstool.ant.protocol.BmsStatus
import dev.bmstool.ant.protocol.Frame
import dev.bmstool.ant.protocol.FrameAssembler
import dev.bmstool.ant.protocol.OldProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The real BLE link to an ANT BMS (module "ANT-BLE20A", service ffe0 / characteristic ffe1).
 *
 * Threading: the GATT callbacks arrive on a binder thread and only ever hand data to a channel;
 * the single RX consumer coroutine owns the [FrameAssembler], a single writer coroutine owns the
 * characteristic writes (one in flight, >= 40 ms apart).
 */
@SuppressLint("MissingPermission")
class BleBmsConnection(private val context: Context) : BmsConnection {

    private companion object {
        const val TAG = "BleBms"
        const val MAX_LOG = 300
        const val SCAN_MS = 15_000L
        const val POLL_MS = 1_000L
        const val WRITE_GAP_MS = 40L
        const val WRITE_ACK_MS = 300L
        const val MAX_ATTEMPTS = 6
        const val STATUS_LOG_MS = 5_000L
        const val DISCOVER_DELAY_MS = 250L
    }

    // ---------------------------------------------------------------- state

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _device = MutableStateFlow<BleDevice?>(null)
    override val device: StateFlow<BleDevice?> = _device.asStateFlow()

    private val _status = MutableStateFlow<BmsStatus?>(null)
    override val status: StateFlow<BmsStatus?> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    override val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    private val _scanResults = MutableStateFlow<List<BleDevice>>(emptyList())
    override val scanResults: StateFlow<List<BleDevice>> = _scanResults.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var characteristic: BluetoothGattCharacteristic? = null
    @Volatile private var target: BleDevice? = null
    @Volatile private var userDisconnect = true
    @Volatile private var attempts = 0
    @Volatile private var lastStatusLogAt = 0L

    private var pollJob: Job? = null
    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null

    // ---------------------------------------------------------------- log

    private val logBuf = ArrayDeque<LogLine>()

    private fun log(kind: Char, text: String) {
        synchronized(logBuf) {
            logBuf.addLast(LogLine(System.currentTimeMillis(), kind, text))
            while (logBuf.size > MAX_LOG) logBuf.removeFirst()
            _log.value = logBuf.toList()
        }
        if (kind == '!') Log.i(TAG, text) else Log.d(TAG, "$kind $text")
    }

    private fun fail(text: String) {
        _lastError.value = text
        log('!', text)
        Log.w(TAG, text)
    }

    // ---------------------------------------------------------------- RX pipeline

    private sealed interface Rx {
        class Data(val bytes: ByteArray) : Rx
        object Reset : Rx
    }

    private val rxChannel = Channel<Rx>(Channel.UNLIMITED)
    private val txChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val assembler = FrameAssembler()

    private class Pending(val header: Int, val reg: Int, val result: CompletableDeferred<Int?>)

    private val pending = AtomicReference<Pending?>(null)
    private val requestMutex = Mutex()
    private val writeAck = AtomicReference<CompletableDeferred<Int>?>(null)
    @Volatile private var lastWriteAt = 0L

    init {
        scope.launch {
            for (msg in rxChannel) {
                try {
                    when (msg) {
                        is Rx.Reset -> assembler.reset()
                        is Rx.Data -> handleFrames(assembler.feed(msg.bytes))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "rx: ${e.message}")
                }
            }
        }
        scope.launch {
            for (bytes in txChannel) {
                try {
                    pump(bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "tx: ${e.message}")
                }
            }
        }
    }

    private fun handleFrames(frames: List<Frame>) {
        for (f in frames) {
            when (f) {
                is Frame.Status -> {
                    _status.value = f.status
                    val now = System.currentTimeMillis()
                    if (now - lastStatusLogAt >= STATUS_LOG_MS) {
                        lastStatusLogAt = now
                        log('<', statusLine(f.status))
                    }
                }
                is Frame.EchoFrame -> {
                    log('<', OldProtocol.toHex(f.raw))
                    val p = pending.get()
                    if (p != null && p.header == f.echo.header && p.reg == f.echo.reg) {
                        pending.compareAndSet(p, null)
                        p.result.complete(f.echo.value)
                    }
                }
            }
        }
    }

    private fun statusLine(s: BmsStatus): String = String.format(
        Locale.US, "status %.1fV %.1fA max#%d %d min#%d %d",
        s.packVoltage, s.current, s.maxCellIndex, s.maxCellMv, s.minCellIndex, s.minCellMv
    )

    // ---------------------------------------------------------------- TX queue

    /** One serialized write: keep 40 ms spacing, then wait for the ack (or 300 ms). */
    private suspend fun pump(bytes: ByteArray) {
        val gap = WRITE_GAP_MS - (System.currentTimeMillis() - lastWriteAt)
        if (gap > 0) delay(gap)
        val ack = CompletableDeferred<Int>()
        writeAck.set(ack)
        val sent = doWrite(bytes)
        lastWriteAt = System.currentTimeMillis()
        if (sent) {
            log('>', OldProtocol.toHex(bytes))
            withTimeoutOrNull(WRITE_ACK_MS) { ack.await() }
        }
        writeAck.compareAndSet(ack, null)
    }

    @Suppress("DEPRECATION")
    private fun doWrite(bytes: ByteArray): Boolean {
        val g = gatt
        val ch = characteristic
        if (g == null || ch == null) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                ch.setValue(bytes)
                g.writeCharacteristic(ch)
            }
        } catch (e: SecurityException) {
            fail("write: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            fail("write: ${e.message}")
            false
        }
    }

    private fun enqueue(bytes: ByteArray) {
        txChannel.trySend(bytes)
    }

    private fun drainTx() {
        while (txChannel.tryReceive().isSuccess) { /* drop stale frames */ }
    }

    // ---------------------------------------------------------------- scanning

    private val seen = LinkedHashMap<String, BleDevice>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) addScanResult(r)
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            fail("Scan failed (error $errorCode)")
        }
    }

    private fun addScanResult(result: ScanResult) {
        val dev: BluetoothDevice = result.device ?: return
        val name = try {
            result.scanRecord?.deviceName ?: dev.name
        } catch (e: SecurityException) {
            null
        }
        val address = dev.address ?: return
        synchronized(seen) {
            val old = seen[address]
            seen[address] = BleDevice(address, name ?: old?.name, result.rssi)
            _scanResults.value = seen.values.sortedWith(
                compareByDescending<BleDevice> { it.name?.startsWith("ANT") == true }
                    .thenByDescending { it.rssi }
            )
        }
    }

    override fun startScan() {
        if (_scanning.value) return
        try {
            val a = adapter
            if (a == null) {
                fail("No Bluetooth adapter on this device")
                return
            }
            if (!a.isEnabled) {
                fail("Bluetooth is off — turn it on to scan")
                return
            }
            val scanner = a.bluetoothLeScanner
            if (scanner == null) {
                fail("BLE scanner unavailable (Bluetooth off?)")
                return
            }
            synchronized(seen) {
                seen.clear()
                _scanResults.value = emptyList()
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, scanCallback)
            _scanning.value = true
            log('!', "scan started")
            scanTimeoutJob?.cancel()
            scanTimeoutJob = scope.launch {
                delay(SCAN_MS)
                stopScan()
            }
        } catch (e: SecurityException) {
            fail("startScan: ${e.message} (missing Bluetooth permission?)")
        } catch (e: IllegalStateException) {
            fail("startScan: ${e.message}")
        }
    }

    override fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        if (!_scanning.value) return
        _scanning.value = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            log('!', "scan stopped (${_scanResults.value.size} devices)")
        } catch (e: SecurityException) {
            fail("stopScan: ${e.message}")
        } catch (e: IllegalStateException) {
            fail("stopScan: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- connect

    override fun connect(address: String, name: String?) {
        stopScan()
        userDisconnect = false
        attempts = 0
        target = BleDevice(address, name)
        _device.value = BleDevice(address, name)
        reconnectJob?.cancel()
        reconnectJob = scope.launch { openGatt() }
    }

    @Synchronized
    private fun openGatt() {
        val t = target ?: return
        if (userDisconnect) return
        val a = adapter
        if (a == null) {
            fail("No Bluetooth adapter on this device")
            _state.value = ConnectionState.DISCONNECTED
            return
        }
        try {
            if (!a.isEnabled) {
                fail("Bluetooth is off — turn it on to connect")
                _state.value = ConnectionState.DISCONNECTED
                return
            }
            closeGatt()
            drainTx()
            rxChannel.trySend(Rx.Reset)
            _state.value = ConnectionState.CONNECTING
            log('!', "connecting to ${t.name ?: "?"} ${t.address}")
            val remote = a.getRemoteDevice(t.address)
            val g = remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            gatt = g
            if (g == null) {
                fail("connectGatt returned null")
                scheduleRetry("connectGatt null")
            }
        } catch (e: SecurityException) {
            fail("connect: ${e.message} (missing Bluetooth permission?)")
            _state.value = ConnectionState.DISCONNECTED
        } catch (e: IllegalArgumentException) {
            fail("connect: bad address ${t.address}")
            _state.value = ConnectionState.DISCONNECTED
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            val current = gatt
            if (g != null && current != null && current !== g) return   // stale: previous link
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                log('!', "link up, discovering services")
                scope.launch {
                    delay(DISCOVER_DELAY_MS)
                    val cur = gatt
                    if (cur == null || g == null || cur !== g) return@launch
                    try {
                        if (!cur.discoverServices()) linkLost("discoverServices refused")
                    } catch (e: SecurityException) {
                        fail("discoverServices: ${e.message}")
                        linkLost("discoverServices denied")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val reason = when {
                    status == BluetoothGatt.GATT_SUCCESS -> "link closed"
                    status == 133 -> "gatt status 133 (connection attempt failed)"
                    else -> "gatt status $status"
                }
                linkLost(reason)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            if (g == null || gatt !== g) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed ($status)")
                linkLost("discovery $status")
                return
            }
            val service = try {
                g.getService(UUID.fromString(OldProtocol.SERVICE_UUID))
            } catch (e: SecurityException) {
                null
            }
            if (service == null) {
                fail("Service ffe0 not found on this device")
                linkLost("no service")
                return
            }
            val ch = service.getCharacteristic(UUID.fromString(OldProtocol.CHAR_UUID))
            if (ch == null) {
                fail("Characteristic ffe1 not found")
                linkLost("no characteristic")
                return
            }
            characteristic = ch
            try {
                ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                if (!g.setCharacteristicNotification(ch, true)) {
                    fail("setCharacteristicNotification failed")
                    linkLost("notify off")
                    return
                }
                val cccd = ch.getDescriptor(UUID.fromString(OldProtocol.CCCD_UUID))
                if (cccd == null) {
                    fail("CCCD 2902 not found")
                    linkLost("no cccd")
                    return
                }
                val ok = writeCccd(g, cccd)
                if (!ok) {
                    fail("Writing the CCCD failed")
                    linkLost("cccd write refused")
                }
            } catch (e: SecurityException) {
                fail("enable notifications: ${e.message}")
                linkLost("notify denied")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (gatt !== g) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Enabling notifications failed ($status)")
                linkLost("cccd status $status")
                return
            }
            attempts = 0
            _lastError.value = null
            val t = target
            if (t != null) {
                var name = t.name
                if (name == null) {
                    name = try {
                        g?.device?.name
                    } catch (e: SecurityException) {
                        null
                    }
                }
                _device.value = BleDevice(t.address, name)
            }
            _state.value = ConnectionState.CONNECTED
            log('!', "connected — notifications on, polling every ${POLL_MS} ms")
            startPolling()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt?, c: BluetoothGattCharacteristic?, status: Int) {
            writeAck.getAndSet(null)?.complete(status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            rxChannel.trySend(Rx.Data(value.copyOf()))
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt?, c: BluetoothGattCharacteristic?) {
            @Suppress("DEPRECATION")
            val v = c?.value ?: return
            rxChannel.trySend(Rx.Data(v))
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCccd(g: BluetoothGatt, cccd: BluetoothGattDescriptor): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            g.writeDescriptor(cccd)
        }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && _state.value == ConnectionState.CONNECTED) {
                enqueue(OldProtocol.STATUS_REQUEST)
                delay(POLL_MS)
            }
        }
    }

    /** Unexpected teardown: drop everything and (unless the user asked for it) retry. */
    @Synchronized
    private fun linkLost(reason: String) {
        if (gatt == null) return                      // already torn down: stale callback
        pollJob?.cancel()
        pollJob = null
        closeGatt()
        drainTx()
        rxChannel.trySend(Rx.Reset)
        pending.getAndSet(null)?.result?.complete(null)
        writeAck.getAndSet(null)?.complete(-1)
        log('!', "disconnected: $reason")
        scheduleRetry(reason)
    }

    @Synchronized
    private fun scheduleRetry(reason: String) {
        if (userDisconnect || target == null) {
            _state.value = ConnectionState.DISCONNECTED
            return
        }
        attempts += 1
        if (attempts > MAX_ATTEMPTS) {
            _state.value = ConnectionState.DISCONNECTED
            fail("Gave up after $MAX_ATTEMPTS reconnect attempts ($reason)")
            return
        }
        val wait = 1000L * attempts.coerceAtMost(5)
        _state.value = ConnectionState.CONNECTING
        log('!', "reconnect attempt $attempts of $MAX_ATTEMPTS in $wait ms")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            openGatt()
        }
    }

    private fun closeGatt() {
        val g = gatt
        gatt = null
        characteristic = null
        if (g != null) {
            try {
                g.disconnect()
            } catch (e: SecurityException) {
                Log.w(TAG, "disconnect: ${e.message}")
            }
            try {
                g.close()
            } catch (e: SecurityException) {
                Log.w(TAG, "close: ${e.message}")
            }
        }
    }

    override fun disconnect() {
        userDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        pollJob?.cancel()
        pollJob = null
        closeGatt()
        drainTx()
        pending.getAndSet(null)?.result?.complete(null)
        _state.value = ConnectionState.DISCONNECTED
        log('!', "disconnected by user")
    }

    override fun close() {
        userDisconnect = true
        stopScan()
        reconnectJob?.cancel()
        pollJob?.cancel()
        closeGatt()
        pending.getAndSet(null)?.result?.complete(null)
        _state.value = ConnectionState.DISCONNECTED
        rxChannel.close()
        txChannel.close()
        scope.cancel()
    }

    // ---------------------------------------------------------------- register transactions

    override suspend fun readRegister(reg: Int, timeoutMs: Long): Int? =
        request(OldProtocol.HDR_READ, reg, OldProtocol.readFrame(reg), timeoutMs)

    override suspend fun writeRegister(reg: Int, value: Int, timeoutMs: Long): Int? =
        request(OldProtocol.HDR_WRITE, reg, OldProtocol.writeFrame(reg, value), timeoutMs)

    private suspend fun request(header: Int, reg: Int, frame: ByteArray, timeoutMs: Long): Int? {
        if (_state.value != ConnectionState.CONNECTED) return null
        return requestMutex.withLock {
            val p = Pending(header, reg, CompletableDeferred())
            pending.set(p)
            try {
                enqueue(frame)
                val v = withTimeoutOrNull(timeoutMs) { p.result.await() }
                if (v == null) {
                    val hdr = String.format(Locale.US, "%02x", header)
                    log('!', "no echo for reg $reg (header $hdr)")
                }
                v
            } finally {
                pending.compareAndSet(p, null)
            }
        }
    }
}
