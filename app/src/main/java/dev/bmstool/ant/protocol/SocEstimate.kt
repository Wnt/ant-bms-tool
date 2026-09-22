package dev.bmstool.ant.protocol

/**
 * Voltage-based state-of-charge estimate. The BMS's own coulomb counter is unreliable here: it resets
 * to 100 % on every cell-over-voltage trip, which on an unbalanced pack happens all the time.
 *
 * Method: terminal voltage -> open-circuit voltage (IR-compensated with a rough per-group resistance)
 * -> SOC through a generic LCO/NMC 18650 OCV curve. Good to maybe ±10 %; it is an estimate.
 */
data class SocEstimate(
    val packPct: Int,        // from the average cell (what the pack "looks like")
    val minCellPct: Int,     // lowest cell — what is usable on discharge
    val maxCellPct: Int,     // highest cell — what limits charging
    val usableAh: Double,    // minCellPct × physical capacity
)

object SocEstimator {
    /** Internal resistance of one parallel group (3P of aged 18650s ≈ 3 × 75 mΩ in parallel). */
    const val GROUP_RESISTANCE_OHM = 0.025

    // mV -> %, resting OCV of a typical 4.2 V LCO/NMC 18650 at room temperature
    private val OCV = listOf(
        3000 to 0.0, 3300 to 1.0, 3400 to 3.0, 3450 to 5.0, 3500 to 8.0, 3550 to 12.0, 3600 to 17.0,
        3650 to 24.0, 3700 to 32.0, 3750 to 41.0, 3800 to 49.0, 3850 to 57.0, 3900 to 65.0, 3950 to 72.0,
        4000 to 79.0, 4050 to 85.0, 4100 to 91.0, 4150 to 96.0, 4200 to 100.0,
    )

    fun socFromOcv(mv: Double): Double {
        if (mv <= OCV.first().first) return 0.0
        if (mv >= OCV.last().first) return 100.0
        for (i in 1 until OCV.size) {
            val (v1, s1) = OCV[i - 1]
            val (v2, s2) = OCV[i]
            if (mv <= v2) return s1 + (s2 - s1) * (mv - v1) / (v2 - v1)
        }
        return 100.0
    }

    /** Terminal voltage -> OCV. Pack current is positive when discharging, negative when charging. */
    fun ocvMv(terminalMv: Int, currentA: Double): Double = terminalMv + currentA * GROUP_RESISTANCE_OHM * 1000.0

    fun estimate(s: BmsStatus): SocEstimate? {
        if (s.cells.isEmpty()) return null
        val pct = { mv: Int -> socFromOcv(ocvMv(mv, s.current)) }
        val avg = pct(s.cells.average().toInt())
        val minPct = pct(s.cells.min())
        val maxPct = pct(s.cells.max())
        return SocEstimate(
            packPct = avg.toInt(),
            minCellPct = minPct.toInt(),
            maxCellPct = maxPct.toInt(),
            usableAh = s.physicalCapacityAh * minPct / 100.0,
        )
    }
}
