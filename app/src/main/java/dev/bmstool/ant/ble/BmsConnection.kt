package dev.bmstool.ant.ble

import dev.bmstool.ant.protocol.BmsStatus
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/** kind: '>' = bytes sent, '<' = bytes received / decoded, '!' = event or error */
data class LogLine(val time: Long, val kind: Char, val text: String)

data class BleDevice(val address: String, val name: String?, val rssi: Int = 0)

/**
 * The BLE link to the BMS. One implementation: [BleBmsConnection] (constructor `(context: Context)`).
 *
 * Contract:
 *  - While CONNECTED the implementation polls [dev.bmstool.ant.protocol.OldProtocol.STATUS_REQUEST]
 *    every ~1000 ms (keepalive) and publishes every decoded frame to [status].
 *  - All writes go through one serialized queue with >= 40 ms spacing.
 *  - [readRegister] / [writeRegister] send one 6-byte frame and suspend until the matching echo
 *    (same header + reg) arrives, or return null on timeout / when not connected.
 *  - On an unexpected disconnect the implementation reconnects by itself (a few attempts).
 */
interface BmsConnection {
    val state: StateFlow<ConnectionState>
    val device: StateFlow<BleDevice?>
    val status: StateFlow<BmsStatus?>
    val lastError: StateFlow<String?>
    val log: StateFlow<List<LogLine>>            // newest last, bounded (~300 lines)
    val scanResults: StateFlow<List<BleDevice>>  // sorted by RSSI desc, de-duplicated by address
    val scanning: StateFlow<Boolean>

    fun startScan()
    fun stopScan()
    fun connect(address: String, name: String?)
    fun disconnect()
    suspend fun readRegister(reg: Int, timeoutMs: Long = 1500): Int?
    suspend fun writeRegister(reg: Int, value: Int, timeoutMs: Long = 2000): Int?
    fun close()
}
