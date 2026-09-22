package dev.bmstool.ant.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bmstool.ant.AppViewModel
import dev.bmstool.ant.ble.ConnectionState
import dev.bmstool.ant.protocol.ParamDef
import dev.bmstool.ant.protocol.Params
import dev.bmstool.ant.ui.theme.Amber
import java.util.Locale

/**
 * Parameter registers: read them all, edit the editable ones, apply (255).
 *
 * Nothing is written without an explicit tap: a row opens a dialog, the dialog writes.
 * Registers that re-interpret the pack or the calibration ask a second time ([CONFIRM_KEYS]).
 */
@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val params by vm.params.collectAsStateWithLifecycle()

    val connected = state == ConnectionState.CONNECTED
    val enabled = connected && busy == null

    var editing by remember { mutableStateOf<ParamDef?>(null) }
    val grouped = remember {
        Params.groups
            .map { group -> group to Params.all.filter { it.group == group } }
            .filter { it.second.isNotEmpty() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (!connected) {
                Text(
                    text = "Not connected — open the Connect tab first. Reading and writing registers " +
                        "needs a live link to the BMS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { vm.refreshParams() },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("Read all from BMS") }
                OutlinedButton(
                    onClick = { vm.applySettings() },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("Apply (255)") }
            }
            val busyText = busy
            if (busyText != null) {
                Spacer(Modifier.height(10.dp))
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
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            grouped.forEach { (group, defs) ->
                item(key = "group-$group") { SectionHeader(group) }
                items(defs, key = { it.key }) { def ->
                    ParamRow(
                        vm = vm,
                        def = def,
                        raw = params[def.reg],
                        editable = def.editable && enabled,
                        onClick = { editing = def },
                    )
                }
            }
        }
    }

    val edit = editing
    if (edit != null) {
        EditParamDialog(
            vm = vm,
            def = edit,
            current = vm.displayValue(edit),
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(Locale.US),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun ParamRow(
    vm: AppViewModel,
    def: ParamDef,
    raw: Long?,
    editable: Boolean,
    onClick: () -> Unit,
) {
    // `raw` is passed in so the row recomposes when the register changes; the value itself
    // comes from the view model (it knows about 0xFFFF = "not available").
    val value = if (raw == null) null else vm.displayValue(def)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = editable, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = def.label, style = MaterialTheme.typography.bodyLarge)
                if (def.help.isNotEmpty()) {
                    Text(
                        text = def.help,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (def.editable) "reg ${def.reg}" else "reg ${def.reg} · read-only",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (value == null) "—" else vm.formatValue(def, value),
                style = MaterialTheme.typography.titleSmall,
                color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditParamDialog(
    vm: AppViewModel,
    def: ParamDef,
    current: Double?,
    onDismiss: () -> Unit,
) {
    var text by remember(def.key) {
        mutableStateOf(if (current == null) "" else plainNumber(current, def.decimals))
    }
    // Non-null while the "are you sure" step is up; it carries the apply flag of the pending write.
    var pendingApply by remember(def.key) { mutableStateOf<Boolean?>(null) }

    val parsed = parseNumber(text)
    val min = def.min
    val max = def.max
    val tooLow = parsed != null && min != null && parsed < min
    val tooHigh = parsed != null && max != null && parsed > max
    val error = when {
        text.isBlank() -> "Enter a value"
        parsed == null -> "Not a number"
        tooLow || tooHigh -> "Out of range — allowed: ${rangeText(def)}"
        else -> null
    }
    val needsConfirm = def.key in CONFIRM_KEYS

    val write: (Boolean) -> Unit = { apply ->
        val v = parseNumber(text)
        if (v != null && error == null) {
            if (needsConfirm) pendingApply = apply
            else {
                vm.setParam(def, v, apply)
                onDismiss()
            }
        }
    }

    val confirming = pendingApply
    if (confirming != null) {
        AlertDialog(
            onDismissRequest = { pendingApply = null },
            title = { Text("Confirm ${def.label}") },
            text = {
                Column {
                    Text(
                        text = "New value: " +
                            (parsed?.let { vm.formatValue(def, it) } ?: text) +
                            "  (register ${def.reg})"
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("This changes how the BMS interprets the pack — continue?")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val v = parseNumber(text)
                        pendingApply = null
                        if (v != null) vm.setParam(def, v, confirming)
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Amber),
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { pendingApply = null }) { Text("Back") }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(def.label) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (def.help.isNotEmpty()) {
                        Text(
                            text = def.help,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            isError = error != null,
                            label = { Text("Value") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            supportingText = {
                                Text(
                                    text = error ?: "Allowed: ${rangeText(def)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                        )
                        if (def.signed) {
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = {
                                val t = text.trim()
                                text = when {
                                    t.isEmpty() -> "-"
                                    t.startsWith("-") -> t.removePrefix("-")
                                    else -> "-$t"
                                }
                            }) { Text("+/−") }
                        }
                        if (def.unit.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(text = def.unit, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Register ${def.reg}" + if (def.wide) " + ${def.reg + 1} (32-bit)" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Decimal separator: . or ,",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { write(false) }, enabled = error == null) { Text("Write only") }
                    TextButton(onClick = { write(true) }, enabled = error == null) { Text("Write & apply") }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )
    }
}

/** Writing one of these re-interprets the pack or the calibration: ask a second time. */
private val CONFIRM_KEYS: Set<String> = setOf(
    "cell_count",
    "phys_capacity",
    "sys_ref_v",
    "total_v_adc_zero",
    "current_sensor_range",
    "start_current",
    "reg43",
    "reg47",
)

/** '.' and ',' both work as the decimal separator. */
private fun parseNumber(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

private fun plainNumber(v: Double, decimals: Int): String =
    if (decimals <= 0) Math.round(v).toString() else String.format(Locale.US, "%.${decimals}f", v)

private fun rangeText(def: ParamDef): String {
    val min = def.min
    val max = def.max
    val unit = if (def.unit.isEmpty()) "" else " ${def.unit}"
    return when {
        min != null && max != null ->
            "${plainNumber(min, def.decimals)} … ${plainNumber(max, def.decimals)}$unit"
        min != null -> "at least ${plainNumber(min, def.decimals)}$unit"
        max != null -> "at most ${plainNumber(max, def.decimals)}$unit"
        else -> "no limit known — the BMS may clamp the value"
    }
}
