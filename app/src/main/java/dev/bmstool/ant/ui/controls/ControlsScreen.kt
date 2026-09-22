package dev.bmstool.ant.ui.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bmstool.ant.AppViewModel
import dev.bmstool.ant.ble.ConnectionState
import dev.bmstool.ant.protocol.BmsStatus
import dev.bmstool.ant.protocol.ControlDef
import dev.bmstool.ant.protocol.Controls
import dev.bmstool.ant.protocol.StatusTables
import dev.bmstool.ant.ui.theme.Amber
import dev.bmstool.ant.ui.theme.Blue
import dev.bmstool.ant.ui.theme.Green
import dev.bmstool.ant.ui.theme.Red
import java.util.Locale

/** Buttons that write control registers (247-255). Everything irreversible asks first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlsScreen(vm: AppViewModel, modifier: Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    val connected = state == ConnectionState.CONNECTED
    val enabled = connected && busy == null

    // The control waiting for its confirmation dialog, if any.
    var confirm by remember { mutableStateOf<ControlDef?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LiveHeader(status)

        if (!connected) {
            Text(
                text = "Not connected — commands are disabled. Connect on the Connect tab.",
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
        }
        val busyText = busy
        if (busyText != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = busyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section(title = "MOSFETs", danger = false) {
            ControlItem(Controls.chargeOn, enabled, ControlStyle.PRIMARY) { vm.control(Controls.chargeOn) }
            ControlItem(Controls.chargeOff, enabled, ControlStyle.TONAL) { vm.control(Controls.chargeOff) }
            ControlItem(Controls.dischargeOn, enabled, ControlStyle.PRIMARY) { vm.control(Controls.dischargeOn) }
            ControlItem(Controls.dischargeOff, enabled, ControlStyle.TONAL) { vm.control(Controls.dischargeOff) }
        }

        Section(title = "Balancing", danger = false) {
            Text(
                text = "This register toggles: every tap flips auto-balance, and the BMS echoes the " +
                    "new state (1 = on, 0 = off) into the snackbar and the log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ControlItem(Controls.autoBalanceToggle, enabled, ControlStyle.PRIMARY) {
                vm.control(Controls.autoBalanceToggle)
            }
        }

        Section(title = "Calibration", danger = false) {
            ControlItem(Controls.zeroCurrent, enabled, ControlStyle.TONAL) { confirm = Controls.zeroCurrent }
        }

        Section(title = "Settings", danger = false) {
            ControlItem(Controls.applySettings, enabled, ControlStyle.PRIMARY) { vm.control(Controls.applySettings) }
        }

        Section(title = "Danger zone", danger = true) {
            ControlItem(Controls.reboot, enabled, ControlStyle.DANGER) { confirm = Controls.reboot }
            ControlItem(Controls.lifepo4Mode, enabled, ControlStyle.DANGER) { confirm = Controls.lifepo4Mode }
            ControlItem(Controls.factoryReset, enabled, ControlStyle.DANGER) { confirm = Controls.factoryReset }
            ControlItem(Controls.shutdown, enabled, ControlStyle.DANGER) { confirm = Controls.shutdown }
        }
    }

    val pending = confirm
    if (pending != null) {
        val danger = pending.dangerous
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(pending.label) },
            text = {
                Column {
                    Text(pending.description)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = if (danger) {
                            "This is written straight to register ${pending.reg} and cannot be undone " +
                                "from here. Continue?"
                        } else {
                            "No current must be flowing through the BMS while the sensor zero is " +
                                "calibrated. Continue?"
                        },
                        color = if (danger) Red else Amber,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        vm.control(pending)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = if (danger) Red else Amber),
                ) { Text(if (danger) "Yes, do it" else "Continue") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LiveHeader(status: BmsStatus?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (status == null) {
                Text(
                    text = "Waiting for status frames…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                StateLine("Charge MOS", status.chargeMosText, mosColor(status.chargeMosState))
                StateLine("Discharge MOS", status.dischargeMosText, mosColor(status.dischargeMosState))
                StateLine("Balancing", status.balanceText, balanceColor(status.balanceState))
                Spacer(Modifier.size(2.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.size(2.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Metric(
                        label = when {
                            status.isCharging -> "Current (charging)"
                            status.isDischarging -> "Current (discharging)"
                            else -> "Current (idle)"
                        },
                        value = String.format(Locale.US, "%.1f A", status.current),
                        color = when {
                            status.isCharging -> Green
                            status.isDischarging -> Blue
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Metric(
                        label = "Cell difference",
                        value = "${status.diffMv} mV",
                        color = diffColor(status.diffMv),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StateLine(label: String, value: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp),
        )
        Text(text = value, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun Section(title: String, danger: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (danger) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, Red),
        ) { SectionBody(title, Red, content) }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            SectionBody(title, MaterialTheme.colorScheme.primary, content)
        }
    }
}

@Composable
private fun SectionBody(title: String, titleColor: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = titleColor)
        content()
    }
}

private enum class ControlStyle { PRIMARY, TONAL, DANGER }

@Composable
private fun ControlItem(
    def: ControlDef,
    enabled: Boolean,
    style: ControlStyle,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (style) {
            ControlStyle.PRIMARY -> Button(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(def.label) }

            ControlStyle.TONAL -> FilledTonalButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(def.label) }

            ControlStyle.DANGER -> OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, if (enabled) Red else Red.copy(alpha = 0.35f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
            ) { Text(def.label) }
        }
        Text(
            text = def.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 3.dp),
        )
    }
}

/** Green while the MOSFET conducts, amber when it is simply off, red when a protection tripped. */
private fun mosColor(state: Int): Color = when {
    StatusTables.mosIsOn(state) -> Green
    state == 0 -> Amber
    else -> Red
}

/** 2 = charge balancing, 4 = auto, 5 = manual are "working"; 3 is the over-temperature stop. */
private fun balanceColor(state: Int): Color = when (state) {
    1, 2, 4, 5 -> Green
    3 -> Red
    else -> Amber
}

private fun diffColor(diffMv: Int): Color = when {
    diffMv < 30 -> Green
    diffMv < 100 -> Amber
    else -> Red
}
