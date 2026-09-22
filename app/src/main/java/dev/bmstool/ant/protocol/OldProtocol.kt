package dev.bmstool.ant.protocol

/**
 * ANT BMS "old" protocol — the one spoken by this pack's ANT-BLE20A (verified on the real BMS):
 *
 *   status poll     DB DB 00 00 00 00      -> 140-byte frame  AA 55 AA FF … (big-endian),
 *                                              checksum = sum(bytes[4..137]) & 0xFFFF at [138..139]
 *   read register   5A 5A reg 00 00 sum    -> echo 5A 5A reg hi lo sum   (value in hi/lo; FFFF = n/a)
 *   write/control   A5 A5 reg hi lo sum    -> echo A5 A5 reg hi lo sum   (toggles echo the NEW state)
 *   sum = (reg + hi + lo) & 0xFF.  Control 255 = "apply settings": protections are re-evaluated at once.
 *
 * BLE: service 0000ffe0-…, characteristic 0000ffe1-… (write-without-response + notify, 20-byte chunks).
 * The module drops the link ~9 s after the last write: poll status every second (doubles as keepalive).
 */
object OldProtocol {
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    const val HDR_READ = 0x5A
    const val HDR_WRITE = 0xA5
    const val STATUS_LEN = 140
    const val REG_APPLY = 255

    val STATUS_REQUEST: ByteArray = byteArrayOf(0xDB.toByte(), 0xDB.toByte(), 0, 0, 0, 0)

    fun readFrame(reg: Int): ByteArray = frame(HDR_READ, reg, 0)
    fun writeFrame(reg: Int, value: Int): ByteArray = frame(HDR_WRITE, reg, value)

    fun frame(header: Int, reg: Int, value: Int): ByteArray {
        val hi = (value shr 8) and 0xFF
        val lo = value and 0xFF
        return byteArrayOf(
            header.toByte(), header.toByte(), (reg and 0xFF).toByte(), hi.toByte(), lo.toByte(),
            ((reg + hi + lo) and 0xFF).toByte()
        )
    }

    fun toHex(b: ByteArray, off: Int = 0, len: Int = b.size - off): String {
        val sb = StringBuilder(len * 2)
        for (i in off until off + len) sb.append(String.format("%02x", b[i].toInt() and 0xFF))
        return sb.toString()
    }
}

/** A 6-byte reply to a read (header 0x5A) or write/control (header 0xA5). */
data class Echo(val header: Int, val reg: Int, val value: Int) {
    val isWrite: Boolean get() = header == OldProtocol.HDR_WRITE
    val isRead: Boolean get() = header == OldProtocol.HDR_READ
}

data class BmsStatus(
    val packVoltage: Double,          // V
    val current: Double,              // A, NEGATIVE while charging
    val power: Int,                   // W, same sign convention
    val soc: Int,                     // %
    val cells: List<Int>,             // mV, size == cellCount
    val cellCount: Int,
    val physicalCapacityAh: Double,
    val remainingCapacityAh: Double,
    val cycleCapacityAh: Double,
    val runtimeSeconds: Long,
    val mosTemp: Int,                 // degC
    val balanceTemp: Int,             // degC
    val temps: List<Int>,             // 4 sensors, -40 == not fitted
    val chargeMosState: Int,
    val dischargeMosState: Int,
    val balanceState: Int,
    val maxCellIndex: Int,            // 1-based
    val maxCellMv: Int,
    val minCellIndex: Int,            // 1-based
    val minCellMv: Int,
    val avgCellMv: Int,
    val balanceBits: Long = 0,        // bit n-1 set = cell n is being bled (bytes 132..135, big-endian)
    val receivedAt: Long = System.currentTimeMillis(),
) {
    fun isBleeding(cellIndex0: Int): Boolean = (balanceBits shr cellIndex0) and 1L == 1L
    val bleedingCells: List<Int> get() = (0 until cellCount).filter { isBleeding(it) }.map { it + 1 }
    val diffMv: Int get() = maxCellMv - minCellMv
    val isCharging: Boolean get() = current < -0.05
    val isDischarging: Boolean get() = current > 0.05
    val chargeMosText: String get() = StatusTables.chargeMos(chargeMosState)
    val dischargeMosText: String get() = StatusTables.dischargeMos(dischargeMosState)
    val balanceText: String get() = StatusTables.balance(balanceState)
    val fittedTemps: List<Int> get() = temps.filter { it > -40 }
}

object StatusTables {
    private val CHARGE_MOS = listOf(
        "Off", "On", "Cell over-voltage", "Over-current", "Battery full", "Pack over-voltage",
        "Battery over-temp", "MOS over-temp", "Current abnormal", "Balance wire fault", "Board over-temp",
        "?11", "Open failed", "Discharge MOS fault", "Waiting", "Manual off", "Lv2 over-voltage",
        "Low-temp protect", "Cell diff too high", "?19", "Self-check error",
    )
    private val DISCHARGE_MOS = listOf(
        "Off", "On", "Cell under-voltage", "Over-current", "Lv2 over-current", "Pack under-voltage",
        "Battery over-temp", "MOS over-temp", "Current abnormal", "Balance wire fault", "Board over-temp",
        "Charger connected", "Short circuit", "Discharge MOS fault", "Open failed", "Manual off",
        "Lv2 under-voltage", "Low-temp protect", "Cell diff too high", "Self-check error",
    )
    // 1 = cells above the balance-limit voltage (reg 13) are being bled; 2 = bleeding during charge
    // above the charge-balance start voltage (reg 14); 4 = spread-based auto mode. All three = active.
    private val BALANCE = listOf(
        "Off", "Active", "Active (charging)", "Paused (over-temp)", "Active (auto)", "Active (manual)",
    )

    fun chargeMos(i: Int) = CHARGE_MOS.getOrNull(i) ?: "state $i"
    fun dischargeMos(i: Int) = DISCHARGE_MOS.getOrNull(i) ?: "state $i"
    fun balance(i: Int) = BALANCE.getOrNull(i) ?: "state $i"
    fun mosIsOn(i: Int) = i == 1
    fun balanceActive(i: Int) = i == 1 || i == 2 || i == 4 || i == 5
}

object StatusParser {
    private fun u8(f: ByteArray, i: Int) = f[i].toInt() and 0xFF
    private fun u16(f: ByteArray, i: Int) = (u8(f, i) shl 8) or u8(f, i + 1)
    private fun i16(f: ByteArray, i: Int) = u16(f, i).let { if (it > 32767) it - 65536 else it }
    private fun u32(f: ByteArray, i: Int): Long =
        (u8(f, i).toLong() shl 24) or (u8(f, i + 1).toLong() shl 16) or (u8(f, i + 2).toLong() shl 8) or u8(f, i + 3).toLong()
    private fun i32(f: ByteArray, i: Int): Int = u32(f, i).toInt()

    fun checksumOk(f: ByteArray): Boolean {
        if (f.size < OldProtocol.STATUS_LEN) return false
        var sum = 0
        for (i in 4 until 138) sum += u8(f, i)
        return (sum and 0xFFFF) == u16(f, 138)
    }

    /** Layout of the 140-byte AA55AAFF frame (from the ANT app's old-protocol parser). */
    fun parse(f: ByteArray): BmsStatus? {
        if (f.size < OldProtocol.STATUS_LEN || u8(f, 0) != 0xAA || u8(f, 1) != 0x55 || u8(f, 2) != 0xAA || u8(f, 3) != 0xFF) return null
        if (!checksumOk(f)) return null
        val cellCount = u8(f, 123).coerceIn(0, 32)
        val cells = (0 until cellCount).map { u16(f, 6 + 2 * it) }
        return BmsStatus(
            packVoltage = u16(f, 4) / 10.0,
            current = i32(f, 70) / 10.0,
            power = i32(f, 111),
            soc = u8(f, 74),
            cells = cells,
            cellCount = cellCount,
            physicalCapacityAh = u32(f, 75) / 1e6,
            remainingCapacityAh = u32(f, 79) / 1e6,
            cycleCapacityAh = u32(f, 83) / 1e3,
            runtimeSeconds = u32(f, 87),
            mosTemp = i16(f, 91),
            balanceTemp = i16(f, 93),
            temps = (0 until 4).map { i16(f, 95 + 2 * it) },
            chargeMosState = u8(f, 103),
            dischargeMosState = u8(f, 104),
            balanceState = u8(f, 105),
            maxCellIndex = u8(f, 115),
            maxCellMv = u16(f, 116),
            minCellIndex = u8(f, 118),
            minCellMv = u16(f, 119),
            avgCellMv = u16(f, 121),
            balanceBits = u32(f, 132),
        )
    }
}

sealed interface Frame {
    data class Status(val status: BmsStatus, val raw: ByteArray) : Frame
    data class EchoFrame(val echo: Echo, val raw: ByteArray) : Frame
}

/**
 * Reassembles frames from the BLE notification byte stream. Status frames (140 B, delivered as
 * 7 × 20-byte notifications) and 6-byte echoes interleave freely; junk is skipped byte by byte.
 * Not thread-safe: feed it from one thread (the GATT callback thread or a single coroutine).
 */
class FrameAssembler {
    private var buf = ByteArray(0)

    fun reset() { buf = ByteArray(0) }

    fun feed(bytes: ByteArray): List<Frame> {
        buf += bytes
        val out = ArrayList<Frame>()
        var i = 0
        loop@ while (buf.size - i >= 4) {
            val b0 = buf[i].toInt() and 0xFF
            val b1 = buf[i + 1].toInt() and 0xFF
            if (b0 == 0xAA && b1 == 0x55 && (buf[i + 2].toInt() and 0xFF) == 0xAA && (buf[i + 3].toInt() and 0xFF) == 0xFF) {
                if (buf.size - i < OldProtocol.STATUS_LEN) break@loop          // wait for the rest
                val raw = buf.copyOfRange(i, i + OldProtocol.STATUS_LEN)
                val st = StatusParser.parse(raw)
                if (st != null) { out.add(Frame.Status(st, raw)); i += OldProtocol.STATUS_LEN; continue@loop }
                i += 1; continue@loop                                             // bad checksum: resync
            }
            if (b0 == b1 && (b0 == OldProtocol.HDR_READ || b0 == OldProtocol.HDR_WRITE)) {
                if (buf.size - i < 6) break@loop
                val reg = buf[i + 2].toInt() and 0xFF
                val hi = buf[i + 3].toInt() and 0xFF
                val lo = buf[i + 4].toInt() and 0xFF
                val sum = buf[i + 5].toInt() and 0xFF
                if ((reg + hi + lo) and 0xFF == sum) {
                    out.add(Frame.EchoFrame(Echo(b0, reg, (hi shl 8) or lo), buf.copyOfRange(i, i + 6)))
                    i += 6; continue@loop
                }
            }
            i += 1
        }
        buf = if (i > 0) buf.copyOfRange(i, buf.size) else buf
        if (buf.size > 4096) buf = buf.copyOfRange(buf.size - 512, buf.size)   // runaway junk guard
        return out
    }
}

/**
 * A settable register. Display value = raw / scale (signed: raw > 32767 -> raw - 65536).
 * wide == true: 32-bit value spread over reg (low word) and reg+1 (high word), e.g. capacity in µAh.
 */
data class ParamDef(
    val reg: Int,
    val key: String,
    val label: String,
    val unit: String,
    val scale: Double,
    val group: String,
    val signed: Boolean = false,
    val wide: Boolean = false,
    val editable: Boolean = true,
    val min: Double? = null,
    val max: Double? = null,
    val help: String = "",
) {
    /** raw -> engineering units (for wide params pass the combined 32-bit value). */
    fun toDisplay(raw: Long): Double {
        val v = if (signed && !wide && raw > 32767) raw - 65536 else raw
        return v / scale
    }
    /** engineering units -> raw register value(s): [low word, high word] for wide, else [word]. */
    fun toRaw(value: Double): List<Int> {
        val r = Math.round(value * scale)
        if (wide) return listOf((r and 0xFFFF).toInt(), ((r shr 16) and 0xFFFF).toInt())
        val w = if (r < 0) (r + 65536).toInt() else r.toInt()
        return listOf(w and 0xFFFF)
    }
    val registers: List<Int> get() = if (wide) listOf(reg, reg + 1) else listOf(reg)
    val decimals: Int get() = when (scale) { 1000.0 -> 3; 100.0 -> 2; 10.0 -> 1; 1e6 -> 3; else -> 0 }
}

object Params {
    const val G_VOLT = "Voltage"
    const val G_CURR = "Current"
    const val G_BAL = "Balance"
    const val G_TEMP = "Temperature"
    const val G_BATT = "Battery"
    const val G_SYS = "System"
    const val G_OTHER = "Other"

    val all: List<ParamDef> = listOf(
        ParamDef(3, "cell_ov_protect", "Cell over-voltage protect", "V", 1000.0, G_VOLT, min = 3.0, max = 4.5),
        ParamDef(5, "cell_ov_recover", "Cell over-voltage recover", "V", 1000.0, G_VOLT, min = 3.0, max = 4.5),
        ParamDef(1, "cell_ov_alarm", "Cell over-voltage alarm", "V", 1000.0, G_VOLT, min = 3.0, max = 4.5),
        ParamDef(4, "cell_uv_protect", "Cell under-voltage protect", "V", 1000.0, G_VOLT, min = 1.5, max = 3.5),
        ParamDef(6, "cell_uv_recover", "Cell under-voltage recover", "V", 1000.0, G_VOLT, min = 1.5, max = 3.8),
        ParamDef(2, "cell_uv_warning", "Cell under-voltage warning", "V", 1000.0, G_VOLT, min = 1.5, max = 3.8),
        ParamDef(7, "pack_ov_protect", "Pack over-voltage protect", "V", 10.0, G_VOLT, min = 0.0, max = 200.0),
        ParamDef(8, "pack_uv_protect", "Pack under-voltage protect (0 = off)", "V", 10.0, G_VOLT, min = 0.0, max = 200.0),
        ParamDef(44, "cell_diff_protect", "Cell voltage difference protect", "V", 1000.0, G_VOLT, min = 0.02, max = 3.0,
            help = "Both MOSFETs are cut when max-min cell voltage exceeds this (VDiffHigh)."),
        ParamDef(43, "reg43", "Register 43 (unknown)", "", 1.0, G_OTHER),

        ParamDef(9, "chg_oc_protect", "Charge over-current protect", "A", 10.0, G_CURR, min = 0.1, max = 500.0),
        ParamDef(10, "chg_oc_delay", "Charge over-current delay", "s", 1.0, G_CURR, min = 0.0, max = 60.0),
        ParamDef(11, "dis_oc_protect", "Discharge over-current protect", "A", 10.0, G_CURR, min = 0.1, max = 500.0),
        ParamDef(12, "dis_oc_delay", "Discharge over-current delay", "s", 1.0, G_CURR, min = 0.0, max = 60.0),
        ParamDef(20, "short_circuit_a", "Short-circuit current", "A", 1.0, G_CURR, min = 1.0, max = 2000.0),
        ParamDef(21, "short_circuit_delay", "Short-circuit delay", "µs", 1.0, G_CURR, min = 0.0, max = 65535.0),
        ParamDef(19, "start_current", "Start current", "A", 10.0, G_CURR),
        ParamDef(18, "current_sensor_range", "Current sensor range", "", 1.0, G_CURR),

        ParamDef(13, "balance_limit_v", "Balance limit voltage", "V", 1000.0, G_BAL, min = 2.0, max = 4.5),
        ParamDef(14, "balance_start_v_charge", "Charge-balance start voltage", "V", 1000.0, G_BAL, min = 2.0, max = 4.5),
        ParamDef(15, "balance_start_diff", "Balance start difference", "V", 1000.0, G_BAL, min = 0.001, max = 1.0),
        ParamDef(16, "balance_current", "Balance current level (1-20)", "", 1.0, G_BAL, min = 1.0, max = 20.0),

        ParamDef(25, "chg_hi_temp_protect", "Charge high-temp protect", "°C", 1.0, G_TEMP, signed = true),
        ParamDef(26, "chg_hi_temp_recover", "Charge high-temp recover", "°C", 1.0, G_TEMP, signed = true),
        ParamDef(27, "dis_hi_temp_protect", "Discharge high-temp protect", "°C", 1.0, G_TEMP, signed = true),
        ParamDef(28, "dis_hi_temp_recover", "Discharge high-temp recover", "°C", 1.0, G_TEMP, signed = true),
        ParamDef(29, "mos_hi_temp_protect", "MOS high-temp protect", "°C", 1.0, G_TEMP, signed = true),
        ParamDef(30, "mos_hi_temp_recover", "MOS high-temp recover", "°C", 1.0, G_TEMP, signed = true),
        ParamDef(37, "chg_lo_temp_protect", "Charge low-temp protect (unverified)", "°C", 1.0, G_TEMP, signed = true),
        ParamDef(38, "chg_lo_temp_recover", "Charge low-temp recover (unverified)", "°C", 1.0, G_TEMP, signed = true),
        ParamDef(39, "dis_lo_temp_protect", "Discharge low-temp protect (unverified)", "°C", 1.0, G_TEMP, signed = true),
        ParamDef(40, "dis_lo_temp_recover", "Discharge low-temp recover (unverified)", "°C", 1.0, G_TEMP, signed = true),

        ParamDef(31, "phys_capacity", "Physical capacity", "Ah", 1e6, G_BATT, wide = true, min = 0.1, max = 4000.0),
        ParamDef(24, "cell_count", "Cell count (series)", "S", 1.0, G_BATT, min = 1.0, max = 32.0,
            help = "Changing this re-maps the cell inputs — only if the pack really changed."),
        ParamDef(17, "sys_ref_v", "System reference voltage", "V", 1000.0, G_SYS),
        ParamDef(23, "total_v_adc_zero", "Total-voltage ADC zero", "", 1.0, G_SYS),
        ParamDef(22, "auto_standby_s", "No-current auto-standby", "s", 1.0, G_SYS, min = 0.0, max = 65535.0),
        ParamDef(41, "tire_length", "Tire length", "mm", 1.0, G_OTHER),
        ParamDef(42, "pulses_per_week", "Pulses per week (speed)", "", 1.0, G_OTHER),
        ParamDef(47, "reg47", "Register 47 (unknown)", "", 1.0, G_OTHER),
        ParamDef(90, "fw_id", "Firmware / protocol id", "", 1.0, G_OTHER, editable = false),
        ParamDef(100, "runtime", "Runtime", "s", 1.0, G_OTHER, editable = false),
    )
    val groups: List<String> = listOf(G_VOLT, G_CURR, G_BAL, G_TEMP, G_BATT, G_SYS, G_OTHER)
    val byReg: Map<Int, ParamDef> = all.associateBy { it.reg }
    val byKey: Map<String, ParamDef> = all.associateBy { it.key }
}

data class ControlDef(
    val reg: Int,
    val value: Int,
    val label: String,
    val description: String,
    val dangerous: Boolean = false,
)

object Controls {
    val chargeOn = ControlDef(250, 1, "Charge MOSFET ON", "Allow charging (protections still apply)")
    val chargeOff = ControlDef(250, 0, "Charge MOSFET OFF", "Manually block charging")
    val dischargeOn = ControlDef(249, 1, "Discharge MOSFET ON", "Allow discharging (protections still apply)")
    val dischargeOff = ControlDef(249, 0, "Discharge MOSFET OFF", "Manually block discharging")
    val autoBalanceToggle = ControlDef(252, 0, "Auto-balance ON/OFF", "Toggles auto-balance mode; the reply shows the new state (1 = on)")
    val zeroCurrent = ControlDef(248, 0, "Zero current", "Calibrate current sensor zero (no current must flow)")
    val applySettings = ControlDef(255, 0, "Apply settings", "Re-evaluate protections with the current parameters")
    val reboot = ControlDef(254, 0, "Reboot BMS", "Restarts the BMS; the link drops", dangerous = true)
    val lifepo4Mode = ControlDef(251, 0, "Switch to LiFePO4 preset", "Loads LiFePO4 voltage thresholds", dangerous = true)
    val factoryReset = ControlDef(253, 0, "Factory reset", "All parameters back to defaults", dangerous = true)
    val shutdown = ControlDef(247, 0, "Shut down BMS", "Powers the BMS off", dangerous = true)

    val all = listOf(chargeOn, chargeOff, dischargeOn, dischargeOff, autoBalanceToggle, zeroCurrent,
        applySettings, reboot, lifepo4Mode, factoryReset, shutdown)
}
