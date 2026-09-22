package dev.bmstool.ant.protocol

import java.util.ArrayDeque

/**
 * Rolling record of the measured pack current: time-weighted average over a window, the charger's
 * "on" current (most negative current seen), and the Ah moved since the app started.
 */
class CurrentHistory(private val windowMs: Long = 10 * 60_000L) {
    private data class Sample(val t: Long, val a: Double)

    data class Stats(
        val avgCurrent: Double?,      // time-weighted average over the window (null until 60 s of data)
        val chargeCurrent: Double?,   // charger current while it is on (positive A), last seen this session
        val dischargeCurrent: Double?,// average of the discharging samples in the window (positive A)
        val ahIn: Double,
        val ahOut: Double,
        val startedAt: Long,
        val samples: Int,
    )

    private val samples = ArrayDeque<Sample>()
    private var last: Sample? = null
    private var ahIn = 0.0        // charged into the pack
    private var ahOut = 0.0       // drawn from the pack
    private var lastChargeCurrent: Double? = null
    private val startedAt = System.currentTimeMillis()

    @Synchronized
    fun add(current: Double, t: Long = System.currentTimeMillis()) {
        last?.let { p ->
            val h = (t - p.t) / 3_600_000.0
            if (h > 0.0 && h < 0.01) {          // ignore gaps > 36 s (link drop)
                val ah = p.a * h
                if (ah < 0) ahIn -= ah else ahOut += ah
            }
        }
        if (current < -0.2) lastChargeCurrent = -current
        val s = Sample(t, current)
        last = s
        samples.addLast(s)
        while (samples.isNotEmpty() && t - samples.first.t > windowMs) samples.removeFirst()
    }

    /** Time-weighted average current over the window; null until 60 s of data exist. */
    @Synchronized
    fun average(): Double? {
        if (samples.size < 2) return null
        val span = samples.last.t - samples.first.t
        if (span < 60_000) return null
        var acc = 0.0
        val it = samples.iterator()
        var prev = it.next()
        while (it.hasNext()) {
            val n = it.next()
            acc += prev.a * (n.t - prev.t)
            prev = n
        }
        return acc / span
    }

    @Synchronized
    fun snapshot(): Stats {
        val chargePeak = samples.filter { it.a < -0.2 }.minOfOrNull { it.a }?.let { -it }
        val dis = samples.filter { it.a > 0.2 }.map { it.a }
        return Stats(
            avgCurrent = average(),
            chargeCurrent = chargePeak ?: lastChargeCurrent,
            dischargeCurrent = if (dis.isEmpty()) null else dis.average(),
            ahIn = ahIn, ahOut = ahOut, startedAt = startedAt, samples = samples.size,
        )
    }
}

/**
 * Charge-time model for a possibly unbalanced pack:
 *   the charger fills the pack directly only until the HIGHEST cell is full ([directAh]);
 *   whatever the LOWEST cell still needs beyond that must first be bled from the high cell(s)
 *   by the balancer, so that part runs at the balancer current (0.1–0.2 A → a time range).
 */
data class ChargeTimeEstimate(
    val mode: Mode,
    val chargeCurrent: Double?,   // A the charger delivers while on (null = never seen one)
    val directAh: Double,         // Ah the charger can still add before the highest cell is full
    val ahTo80: Double,           // lowest cell to 80 %
    val ahToFull: Double,         // lowest cell to 100 %
    val bleedAhTo80: Double,      // part of ahTo80 that is balancer-limited
    val bleedAhToFull: Double,
    val secTo80: LongRange?,      // fast..slow (equal ends when not balancer-limited)
    val secToFull: LongRange?,
    val secToEmpty: Long?,        // when discharging, lowest cell to 0 %
    val balancerActive: Boolean,
    val assumedCharger: Boolean,  // true when no charger current has been observed yet (3 A assumed)
) {
    /** BALANCING = charger paused by the high cell while the balancer bleeds it (recovery in progress). */
    enum class Mode { CHARGING, BALANCING, DISCHARGING, IDLE }
}

object ChargeTimeEstimator {
    const val BAL_A_LOW = 0.1     // typical passive balancer current range (A)
    const val BAL_A_HIGH = 0.2
    private const val TAPER = 1.15 // CV taper: the last part of a full charge takes ~15 % longer

    fun estimate(s: BmsStatus, soc: SocEstimate, stats: CurrentHistory.Stats): ChargeTimeEstimate {
        val cap = s.physicalCapacityAh
        val ahToFull = (100 - soc.minCellPct).coerceAtLeast(0) / 100.0 * cap
        val ahTo80 = (80 - soc.minCellPct).coerceAtLeast(0) / 100.0 * cap
        val directAh = (100 - soc.maxCellPct).coerceAtLeast(0) / 100.0 * cap
        val bleedFull = (ahToFull - directAh).coerceAtLeast(0.0)
        val bleed80 = (ahTo80 - directAh).coerceAtLeast(0.0)
        val ref = stats.avgCurrent ?: s.current
        val balancerActive = StatusTables.balanceActive(s.balanceState)
        val mode = when {
            s.isCharging || (ref < -0.05) -> ChargeTimeEstimate.Mode.CHARGING
            ref > 0.05 -> ChargeTimeEstimate.Mode.DISCHARGING
            balancerActive && bleedFull >= 0.05 -> ChargeTimeEstimate.Mode.BALANCING
            else -> ChargeTimeEstimate.Mode.IDLE
        }
        val seen = stats.chargeCurrent ?: (if (s.isCharging) -s.current else null)
        val assumed = seen == null && mode == ChargeTimeEstimate.Mode.BALANCING
        val chg = seen ?: (if (assumed) 3.0 else null)

        fun range(total: Double, bleed: Double, taper: Double): LongRange? {
            if (chg == null || chg < 0.05) return null
            val direct = (total - bleed).coerceAtLeast(0.0) / chg * 3600 * taper
            val fast = direct + bleed / BAL_A_HIGH * 3600
            val slow = direct + bleed / BAL_A_LOW * 3600
            return fast.toLong()..slow.toLong()
        }
        val charging = mode == ChargeTimeEstimate.Mode.CHARGING || mode == ChargeTimeEstimate.Mode.BALANCING
        val disA = stats.dischargeCurrent ?: (if (s.isDischarging) s.current else null)
        return ChargeTimeEstimate(
            mode = mode,
            chargeCurrent = chg,
            directAh = directAh,
            ahTo80 = ahTo80,
            ahToFull = ahToFull,
            bleedAhTo80 = bleed80,
            bleedAhToFull = bleedFull,
            secTo80 = if (charging) range(ahTo80, bleed80, 1.0) else null,
            secToFull = if (charging) range(ahToFull, bleedFull, TAPER) else null,
            secToEmpty = if (mode == ChargeTimeEstimate.Mode.DISCHARGING && disA != null && disA > 0.05)
                (soc.minCellPct / 100.0 * cap / disA * 3600).toLong() else null,
            balancerActive = balancerActive,
            assumedCharger = assumed,
        )
    }

    fun duration(sec: Long): String {
        if (sec < 60) return "<1 m"
        val d = sec / 86400
        val h = sec % 86400 / 3600
        val m = sec % 3600 / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    /** "13h 12m – 1d 2h", or a single value when the range is tight. */
    fun duration(r: LongRange): String =
        if (r.last - r.first < 600) duration(r.first) else "${duration(r.first)} – ${duration(r.last)}"
}
