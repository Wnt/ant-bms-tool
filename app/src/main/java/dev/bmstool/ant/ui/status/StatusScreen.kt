package dev.bmstool.ant.ui.status

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bmstool.ant.AppViewModel
import dev.bmstool.ant.ble.BleDevice
import dev.bmstool.ant.ble.ConnectionState
import dev.bmstool.ant.protocol.BmsStatus
import dev.bmstool.ant.protocol.ChargeTimeEstimate
import dev.bmstool.ant.protocol.ChargeTimeEstimator
import dev.bmstool.ant.protocol.CurrentHistory
import dev.bmstool.ant.protocol.SocEstimate
import dev.bmstool.ant.protocol.SocEstimator
import dev.bmstool.ant.protocol.StatusTables
import dev.bmstool.ant.ui.theme.Amber
import dev.bmstool.ant.ui.theme.Blue
import dev.bmstool.ant.ui.theme.Green
import dev.bmstool.ant.ui.theme.Red
import dev.bmstool.ant.ui.theme.SurfaceHigh
import kotlinx.coroutines.delay
import java.util.Locale

private val Track = Color(0xFF232B34)
private val AmberBg = Color(0xFF2A2318)
private val GreenBg = Color(0xFF17291B)
private val RedBg = Color(0xFF2E1A1A)

/** Design "A · Glance": ring + pills + cell strip; everything explanatory folded into Details. */
@Composable
fun StatusScreen(vm: AppViewModel, modifier: Modifier) {
    val status by vm.status.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    var details by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(state, device, status, now)
        val s = status
        if (s == null) {
            Placeholder(state)
        } else {
            // The BMS samples current and cell voltages at different instants, so right after a charge
            // start/stop the IR-compensated estimate jumps for a frame or two; a 5-frame median hides that.
            val raw = SocEstimator.estimate(s)
            val history = remember { ArrayList<SocEstimate>() }
            val soc = remember(s.receivedAt) {
                if (raw != null) { history.add(raw); while (history.size > 5) history.removeAt(0) }
                if (history.isEmpty()) null else SocEstimate(
                    packPct = history.map { it.packPct }.sorted()[history.size / 2],
                    minCellPct = history.map { it.minCellPct }.sorted()[history.size / 2],
                    maxCellPct = history.map { it.maxCellPct }.sorted()[history.size / 2],
                    usableAh = history.map { it.usableAh }.sorted()[history.size / 2],
                )
            }
            val est = soc?.let { ChargeTimeEstimator.estimate(s, it, stats) }
            Hero(s, soc, est)
            if (soc != null) RangeBand(soc)
            StatusPills(s)
            CellStrip(s)
            TempsLine(s)
            DetailsSection(s, soc, est, stats, details) { details = !details }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ------------------------------------------------------------------ header / placeholder

@Composable
private fun Header(state: ConnectionState, device: BleDevice?, s: BmsStatus?, now: Long) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(8.dp).background(stateColor(state), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            device?.name ?: device?.address ?: stateText(state),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = stateColor(state),
        )
        if (state != ConnectionState.CONNECTED) {
            Spacer(Modifier.width(8.dp))
            Text(stateText(state), fontSize = 12.sp, color = dim())
        }
        Spacer(Modifier.weight(1f))
        if (s != null) {
            val age = ((now - s.receivedAt) / 1000L).coerceAtLeast(0L)
            Text("${age} s ago", fontSize = 12.sp, color = if (age > 5) Amber else dim())
        }
    }
}

@Composable
private fun Placeholder(state: ConnectionState) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("No status yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                when (state) {
                    ConnectionState.CONNECTED -> "Connected — waiting for the first status frame…"
                    ConnectionState.CONNECTING -> "Connecting to the BMS…"
                    ConnectionState.DISCONNECTED -> "Not connected. Open the Connect tab to scan and connect."
                },
                style = MaterialTheme.typography.bodyMedium, color = dim(),
            )
        }
    }
}

// ------------------------------------------------------------------ hero: ring + V/A + time

@Composable
private fun Hero(s: BmsStatus, soc: SocEstimate?, est: ChargeTimeEstimate?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SocRing(soc?.packPct ?: s.soc, soc?.let { "est. · ≈ ${fmt(it.usableAh, 1)} Ah" } ?: "BMS counter")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigMono(fmt(s.packVoltage, 1), "V")
            Column {
                BigMono(fmt(s.current, 1), "A", if (s.isCharging) Green else if (s.isDischarging) Blue else MaterialTheme.colorScheme.onSurface)
                val (note, color) = currentNote(s)
                Text(note, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(top = 4.dp))
            }
            TimeLine(est)
        }
    }
}

@Composable
private fun SocRing(pct: Int, subtitle: String) {
    val color = socColor(pct)
    Box(Modifier.size(156.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 12.dp.toPx()
            val arcSize = Size(size.width - sw, size.height - sw)
            val topLeft = Offset(sw / 2, sw / 2)
            drawArc(Track, 0f, 360f, false, topLeft = topLeft, size = arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * pct.coerceIn(0, 100) / 100f, false, topLeft = topLeft, size = arcSize,
                style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(pct.toString(), fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, lineHeight = 40.sp)
                Text("%", fontSize = 18.sp, color = dim(), modifier = Modifier.padding(bottom = 5.dp))
            }
            Text(subtitle, fontSize = 11.sp, color = dim())
        }
    }
}

@Composable
private fun BigMono(value: String, unit: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color, lineHeight = 30.sp)
        Spacer(Modifier.width(4.dp))
        Text(unit, fontSize = 14.sp, color = dim(), modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun currentNote(s: BmsStatus): Pair<String, Color> = when {
    // MOS state first: after a trip the current takes a second to decay, and "CHARGING" would lag
    s.chargeMosState == 2 -> "Charge paused · #${s.maxCellIndex} full" to Amber
    s.isCharging -> "CHARGING" to Green
    s.isDischarging -> "DISCHARGING" to Blue
    s.chargeMosState == 4 -> "Battery full" to Green
    s.chargeMosState == 1 && s.dischargeMosState == 1 -> "idle" to dim()
    s.chargeMosState != 1 -> "Charge: ${s.chargeMosText}" to Amber
    else -> "Discharge: ${s.dischargeMosText}" to Amber
}

@Composable
private fun TimeLine(est: ChargeTimeEstimate?) {
    if (est == null) return
    when (est.mode) {
        ChargeTimeEstimate.Mode.CHARGING, ChargeTimeEstimate.Mode.BALANCING -> Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Full in ", fontSize = 13.sp)
                Text(est.secToFull?.let { ChargeTimeEstimator.duration(it) } ?: "—", fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            }
            if (est.secTo80 != null && est.ahTo80 > 0.01) {
                Text("80 % in ${ChargeTimeEstimator.duration(est.secTo80)}", fontSize = 11.sp, color = dim())
            }
        }
        ChargeTimeEstimate.Mode.DISCHARGING -> Row(verticalAlignment = Alignment.Bottom) {
            Text("Empty in ", fontSize = 13.sp)
            Text(est.secToEmpty?.let { ChargeTimeEstimator.duration(it) } ?: "—", fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        }
        ChargeTimeEstimate.Mode.IDLE -> {}
    }
}

// ------------------------------------------------------------------ lowest–highest band

@Composable
private fun RangeBand(soc: SocEstimate) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("lowest cell ${soc.minCellPct} %", fontSize = 11.sp, color = dim())
            Text("highest ${soc.maxCellPct} %", fontSize = 11.sp, color = dim())
        }
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            val w = maxWidth
            Box(Modifier.fillMaxWidth().height(10.dp).offset(y = 3.dp).background(Track, RoundedCornerShape(5.dp)))
            Box(
                Modifier
                    .offset(x = w * soc.minCellPct / 100f, y = 3.dp)
                    .width(w * (soc.maxCellPct - soc.minCellPct).coerceAtLeast(1) / 100f)
                    .height(10.dp)
                    .background(Color(0xFF2E5A34), RoundedCornerShape(5.dp))
            )
            Box(
                Modifier
                    .offset(x = w * soc.packPct / 100f - 2.dp)
                    .width(4.dp).height(16.dp)
                    .background(Green, RoundedCornerShape(2.dp))
            )
        }
    }
}

// ------------------------------------------------------------------ status pills

@Composable
private fun StatusPills(s: BmsStatus) {
    val (chgText, chgColor) = when (s.chargeMosState) {
        1 -> "On" to Green
        2 -> "Paused" to Amber
        4 -> "Full" to Green
        0, 14, 15 -> (if (s.chargeMosState == 0) "Off" else if (s.chargeMosState == 14) "Waiting" else "Manual off") to Amber
        else -> s.chargeMosText to Red
    }
    val (disText, disColor) = when (s.dischargeMosState) {
        1 -> "On" to Green
        0 -> "Off" to Amber
        11 -> "Charger" to Amber
        15 -> "Manual off" to Amber
        else -> s.dischargeMosText to Red
    }
    val balColor = when {
        StatusTables.balanceActive(s.balanceState) -> Green
        s.balanceState == 3 -> Red
        else -> Amber
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("CHARGE", chgText, chgColor, Modifier.weight(1f))
        Pill("DISCHARGE", disText, disColor, Modifier.weight(1f))
        Pill("BALANCING", s.balanceText, balColor, Modifier.weight(1f))
    }
}

@Composable
private fun Pill(label: String, value: String, color: Color, modifier: Modifier) {
    val bg = when (color) { Green -> GreenBg; Amber -> AmberBg; else -> RedBg }
    Column(
        modifier
            .background(bg, RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, fontSize = 10.sp, letterSpacing = 0.6.sp, color = dim())
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 2)
    }
}

// ------------------------------------------------------------------ cell strip (bleeding cells pulse amber)

@Composable
private fun CellStrip(s: BmsStatus) {
    val pulse by rememberInfiniteTransition(label = "bleed").animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse",
    )
    val lo = s.minCellMv - 100
    val hi = s.maxCellMv + 100
    val bleeding = s.bleedingCells
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                s.cells.forEachIndexed { i, mv ->
                    val frac = ((mv - lo).toFloat() / (hi - lo).coerceAtLeast(1)).coerceIn(0.06f, 1f)
                    val isMax = i + 1 == s.maxCellIndex
                    val isMin = i + 1 == s.minCellIndex
                    val bleed = s.isBleeding(i)
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (bleed) {
                            Box(Modifier.size(5.dp).alpha(pulse).background(Amber, CircleShape))
                            Spacer(Modifier.height(3.dp))
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(64.dp * frac)
                                .alpha(if (bleed) 0.45f + 0.55f * pulse else 1f)
                                .background(
                                    if (bleed) Amber else if (isMax) Green else if (isMin) Red else Blue,
                                    RoundedCornerShape(2.dp),
                                )
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("min #${s.minCellIndex} ${fmt(s.minCellMv / 1000.0, 3)}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Red)
                Text("Δ ${s.diffMv} mV", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text("max #${s.maxCellIndex} ${fmt(s.maxCellMv / 1000.0, 3)}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Green)
            }
            if (bleeding.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).alpha(pulse).background(Amber, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("bleeding: " + bleeding.joinToString(", ") { "#$it" }, fontSize = 11.sp, color = Amber)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ temps line

@Composable
private fun TempsLine(s: BmsStatus) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val cells = s.fittedTemps.joinToString(" · ") { it.toString() }
        Small("Cells", "$cells °C", tempColor(s.fittedTemps.maxOrNull() ?: 0))
        Small("MOS", "${s.mosTemp} °C", tempColor(s.mosTemp))
        Small("Balancer", "${s.balanceTemp} °C", tempColor(s.balanceTemp))
        Small("Cycle", "${fmt(s.cycleCapacityAh, 1)} Ah", MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Small(label: String, value: String, color: Color) {
    Row {
        Text("$label ", fontSize = 12.sp, color = dim())
        Text(value, fontSize = 12.sp, color = color)
    }
}

// ------------------------------------------------------------------ details (collapsed by default)

@Composable
private fun DetailsSection(
    s: BmsStatus, soc: SocEstimate?, est: ChargeTimeEstimate?, stats: CurrentHistory.Stats,
    expanded: Boolean, onToggle: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (expanded) SurfaceHigh else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (expanded) "Details" else "Details — counters, session, why paused", fontSize = 13.sp, color = dim())
            Text(if (expanded) "▴" else "▾", fontSize = 13.sp, color = dim())
        }
        if (!expanded) return@Column
        Spacer(Modifier.height(10.dp))
        val lines = ArrayList<Pair<String, Color>>()
        val dimC = dim()
        if (est != null && (est.mode == ChargeTimeEstimate.Mode.CHARGING || est.mode == ChargeTimeEstimate.Mode.BALANCING)) {
            val chg = est.chargeCurrent?.let { fmt(it, 1) + " A" + (if (est.assumedCharger) " assumed" else "") } ?: "?"
            lines += if (est.bleedAhToFull >= 0.05)
                ("Charger ($chg) can add ${fmt(est.directAh, 1)} Ah before #${s.maxCellIndex} is full; the remaining " +
                    "${fmt(est.bleedAhToFull, 1)} Ah must first be bled from it by the balancer (0.2–0.1 A → time range)" +
                    (if (!est.balancerActive) " — BALANCER IS OFF, charging will stall" else "")) to Amber
            else "Charger $chg, pack balanced — plain charge at that current (+15 % taper)" to dimC
        }
        lines += ("Power ${s.power} W · runtime ${runtime(s.runtimeSeconds)}") to dimC
        if (soc != null) lines += ("Usable ≈ ${fmt(soc.usableAh, 1)} / ${fmt(s.physicalCapacityAh, 1)} Ah (lowest cell ${soc.minCellPct} %)") to dimC
        lines += ("BMS counter: ${s.soc} % · ${fmt(s.remainingCapacityAh, 2)} Ah — resets to 100 % on every cell-OV trip, unreliable on an unbalanced pack") to dimC
        val rate = stats.avgCurrent?.let { "${fmt(it, 2)} A net average over the last 10 min" } ?: "net average needs 1 min of data"
        lines += ("$rate · session +${fmt(stats.ahIn, 2)} Ah in, −${fmt(stats.ahOut, 2)} Ah out") to dimC
        lines += ("Charge MOS: ${s.chargeMosText} · Discharge MOS: ${s.dischargeMosText} · Balancing: ${s.balanceText}" +
            (if (s.balanceBits != 0L) " · bits 0x${java.lang.Long.toHexString(s.balanceBits)}" else "")) to dimC
        for ((text, color) in lines) {
            Text(text, fontSize = 11.sp, color = color, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text("CELLS (${s.cellCount}S)", fontSize = 10.sp, letterSpacing = 0.6.sp, color = dimC)
        Spacer(Modifier.height(6.dp))
        val rows = s.cells.chunked(4)
        rows.forEachIndexed { r, chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                chunk.forEachIndexed { c, mv ->
                    val idx = r * 4 + c + 1
                    val color = when {
                        s.isBleeding(idx - 1) -> Amber
                        idx == s.maxCellIndex -> Green
                        idx == s.minCellIndex -> Red
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("$idx", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = dimC)
                        Text(fmt(mv / 1000.0, 3), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = color)
                    }
                }
                repeat(4 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ------------------------------------------------------------------ helpers

@Composable
private fun dim(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

private fun stateColor(state: ConnectionState): Color = when (state) {
    ConnectionState.CONNECTED -> Green
    ConnectionState.CONNECTING -> Amber
    ConnectionState.DISCONNECTED -> Red
}

private fun stateText(state: ConnectionState): String = when (state) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.CONNECTING -> "Connecting…"
    ConnectionState.DISCONNECTED -> "Not connected"
}

private fun socColor(soc: Int): Color = when {
    soc >= 50 -> Green
    soc >= 20 -> Amber
    else -> Red
}

private fun tempColor(t: Int): Color = when {
    t >= 55 -> Red
    t >= 45 -> Amber
    else -> Green
}

private fun fmt(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)

private fun runtime(sec: Long): String {
    val d = sec / 86400
    val h = sec % 86400 / 3600
    val m = sec % 3600 / 60
    return if (d > 0) "${d}d ${h}h ${m}m" else "${h}h ${m}m"
}
