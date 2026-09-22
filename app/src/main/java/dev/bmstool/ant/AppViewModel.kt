package dev.bmstool.ant

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bmstool.ant.ble.BleBmsConnection
import dev.bmstool.ant.ble.BmsConnection
import dev.bmstool.ant.protocol.ControlDef
import dev.bmstool.ant.protocol.CurrentHistory
import dev.bmstool.ant.protocol.OldProtocol
import dev.bmstool.ant.protocol.ParamDef
import dev.bmstool.ant.protocol.Params
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by every screen. Owns the BLE connection and all parameter/control transactions. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val bms: BmsConnection = BleBmsConnection(app)
    private val prefs = app.getSharedPreferences("bms", Context.MODE_PRIVATE)

    val state get() = bms.state
    val status get() = bms.status
    val device get() = bms.device
    val log get() = bms.log
    val lastError get() = bms.lastError
    val scanResults get() = bms.scanResults
    val scanning get() = bms.scanning

    /** reg -> raw value (for wide params the combined 32-bit value, keyed by the low register). */
    private val _params = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val params: StateFlow<Map<Int, Long>> = _params.asStateFlow()

    /** Description of the transaction in progress, or null when idle. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /** One-shot user messages (snackbar). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages

    private val txLock = Mutex()

    /** Measured-current history (10-min average + session Ah) for the charge-time estimates. */
    private val history = CurrentHistory()
    private val _stats = MutableStateFlow(history.snapshot())
    val stats: StateFlow<CurrentHistory.Stats> = _stats.asStateFlow()

    init {
        viewModelScope.launch {
            bms.status.filterNotNull().collect { s ->
                history.add(s.current, s.receivedAt)
                _stats.value = history.snapshot()
            }
        }
    }

    val lastDeviceAddress: String? get() = prefs.getString("addr", null)
    val lastDeviceName: String? get() = prefs.getString("name", null)

    fun connect(address: String, name: String?) {
        prefs.edit().putString("addr", address).putString("name", name).apply()
        bms.connect(address, name)
    }

    /** Reconnect to the remembered device; false if none is remembered. */
    fun connectLast(): Boolean {
        val addr = lastDeviceAddress ?: return false
        bms.connect(addr, lastDeviceName)
        return true
    }

    fun disconnect() = bms.disconnect()
    fun startScan() = bms.startScan()
    fun stopScan() = bms.stopScan()

    fun say(text: String) { _messages.tryEmit(text) }

    /** Read every parameter (or the given subset) from the BMS into [params]. */
    fun refreshParams(defs: List<ParamDef> = Params.all) {
        viewModelScope.launch {
            txLock.withLock {
                _busy.value = "Reading parameters…"
                var missing = 0
                for (def in defs) {
                    val v = readParam(def)
                    if (v == null) missing++ else _params.value = _params.value + (def.reg to v)
                }
                _busy.value = null
                say(if (missing == 0) "Parameters read" else "Parameters read, $missing without reply")
            }
        }
    }

    private suspend fun readParam(def: ParamDef): Long? {
        val words = ArrayList<Int>()
        for (reg in def.registers) {
            val w = bms.readRegister(reg) ?: return null
            words.add(w)
        }
        return if (def.wide) (words[1].toLong() shl 16) or words[0].toLong() else words[0].toLong()
    }

    /**
     * Write a parameter in engineering units: write the word(s), read back, optionally apply (255).
     * Reports the outcome through [messages]; refreshes [params] for that register.
     */
    fun setParam(def: ParamDef, value: Double, apply: Boolean = true) {
        viewModelScope.launch {
            txLock.withLock {
                _busy.value = "Writing ${def.label}…"
                val raws = def.toRaw(value)
                var ok = true
                for ((i, reg) in def.registers.withIndex()) {
                    val echo = bms.writeRegister(reg, raws[i])
                    if (echo == null) { ok = false; say("No reply writing reg $reg"); break }
                }
                if (ok) {
                    val back = readParam(def)
                    if (back == null) { ok = false; say("Write sent but read-back failed for ${def.label}") }
                    else {
                        _params.value = _params.value + (def.reg to back)
                        val expected = if (def.wide) (raws[1].toLong() shl 16) or raws[0].toLong() else raws[0].toLong()
                        if (back != expected) { ok = false; say("BMS kept ${def.toDisplay(back)} ${def.unit} for ${def.label}") }
                    }
                }
                if (ok && apply) {
                    val a = bms.writeRegister(OldProtocol.REG_APPLY, 0)
                    say(if (a != null) "${def.label} = ${formatValue(def, value)} written and applied" else "Written, but apply (255) got no reply")
                } else if (ok) say("${def.label} = ${formatValue(def, value)} written (not applied yet)")
                _busy.value = null
            }
        }
    }

    fun applySettings() = control(dev.bmstool.ant.protocol.Controls.applySettings)

    /** Send a control command; the echo value is reported (for toggles it is the new state). */
    fun control(def: ControlDef) {
        viewModelScope.launch {
            txLock.withLock {
                _busy.value = def.label
                val echo = bms.writeRegister(def.reg, def.value)
                _busy.value = null
                say(
                    when {
                        echo == null -> "${def.label}: no reply"
                        def.reg == 252 -> "Auto-balance is now ${if (echo == 1) "ON" else "OFF"}"
                        else -> "${def.label}: acknowledged (${echo})"
                    }
                )
            }
        }
    }

    fun formatValue(def: ParamDef, value: Double): String =
        if (def.decimals == 0) "${Math.round(value)} ${def.unit}".trim()
        else String.format("%.${def.decimals}f %s", value, def.unit).trim()

    fun displayValue(def: ParamDef): Double? = _params.value[def.reg]?.let { raw ->
        if (!def.wide && raw == 0xFFFFL) null else def.toDisplay(raw)
    }

    override fun onCleared() {
        bms.close()
    }
}
