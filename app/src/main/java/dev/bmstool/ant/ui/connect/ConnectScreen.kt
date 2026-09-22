package dev.bmstool.ant.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bmstool.ant.AppViewModel
import dev.bmstool.ant.ble.BleDevice
import dev.bmstool.ant.ble.ConnectionState
import dev.bmstool.ant.ui.theme.Amber
import dev.bmstool.ant.ui.theme.Green
import dev.bmstool.ant.ui.theme.Red
import dev.bmstool.ant.ui.theme.SurfaceHigh

@Composable
fun ConnectScreen(
    vm: AppViewModel,
    modifier: Modifier,
    permissionsGranted: Boolean,
    requestPermissions: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val error by vm.lastError.collectAsStateWithLifecycle()
    val results by vm.scanResults.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()

    val lastAddress = remember(device, state) { vm.lastDeviceAddress }
    val lastName = remember(device, state) { vm.lastDeviceName }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StateDot(stateColor(state))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stateText(state),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = stateColor(state),
                        )
                    }
                    val d = device
                    if (d != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(d.name ?: "(no name)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            d.address,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    val e = error
                    if (e != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = Amber, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(e, style = MaterialTheme.typography.bodySmall, color = Amber)
                        }
                    }
                }
            }
        }

        if (!permissionsGranted) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceHigh),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Bluetooth permissions are required to scan for and connect to the BMS.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = requestPermissions) {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Grant permissions")
                        }
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { if (scanning) vm.stopScan() else vm.startScan() },
                    enabled = permissionsGranted,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (scanning) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (scanning) "Stop scan" else "Scan")
                }
                if (state != ConnectionState.DISCONNECTED) {
                    OutlinedButton(onClick = { vm.disconnect() }, modifier = Modifier.weight(1f)) {
                        Text("Disconnect")
                    }
                }
            }
        }

        if (lastAddress != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceHigh),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = permissionsGranted) { vm.connectLast() },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Reconnect last device", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                (lastName ?: "(no name)") + "  " + lastAddress,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Devices (${results.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.width(10.dp))
                if (scanning) {
                    Text(
                        "scanning…",
                        style = MaterialTheme.typography.labelLarge,
                        color = Amber,
                    )
                }
            }
        }

        items(results, key = { it.address }) { d ->
            DeviceRow(d) { vm.connect(d.address, d.name) }
        }

        if (results.isEmpty()) {
            item {
                Text(
                    if (scanning) "Scanning… the module advertises as \"ANT-BLE20A\"."
                    else "No devices yet — tap Scan (stops automatically after 15 s).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DeviceRow(d: BleDevice, onClick: () -> Unit) {
    val isAnt = d.name?.startsWith("ANT") == true
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isAnt) Green.copy(alpha = 0.16f) else SurfaceHigh),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    d.name ?: "(no name)",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isAnt) FontWeight.Bold else FontWeight.Normal,
                    color = if (isAnt) Green else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    d.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Text(
                "${d.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = rssiColor(d.rssi),
            )
        }
    }
}

@Composable
private fun StateDot(color: Color) {
    Spacer(
        Modifier
            .size(10.dp)
            .background(color, CircleShape)
    )
}

private fun stateText(s: ConnectionState): String = when (s) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.CONNECTING -> "Connecting…"
    ConnectionState.DISCONNECTED -> "Disconnected"
}

private fun stateColor(s: ConnectionState): Color = when (s) {
    ConnectionState.CONNECTED -> Green
    ConnectionState.CONNECTING -> Amber
    ConnectionState.DISCONNECTED -> Red
}

private fun rssiColor(rssi: Int): Color = when {
    rssi >= -65 -> Green
    rssi >= -85 -> Amber
    else -> Red
}
