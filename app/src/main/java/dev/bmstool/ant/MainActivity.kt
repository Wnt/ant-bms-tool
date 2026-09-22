package dev.bmstool.ant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dev.bmstool.ant.ui.connect.ConnectScreen
import dev.bmstool.ant.ui.controls.ControlsScreen
import dev.bmstool.ant.ui.log.LogScreen
import dev.bmstool.ant.ui.settings.SettingsScreen
import dev.bmstool.ant.ui.status.StatusScreen
import dev.bmstool.ant.ui.theme.AntBmsTheme
import kotlinx.coroutines.launch

enum class Tab(val label: String) { STATUS("Status"), SETTINGS("Settings"), CONTROLS("Controls"), LOG("Log"), CONNECT("Connect") }

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // BLE link needs us awake
        setContent { AntBmsTheme { App(vm, ::hasBlePermissions) } }
    }

    private fun hasBlePermissions(): Boolean =
        blePermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}

fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@Composable
fun App(vm: AppViewModel, hasPermissions: () -> Boolean) {
    var granted by remember { mutableStateOf(hasPermissions()) }
    var tab by remember { mutableStateOf(if (vm.lastDeviceAddress == null) Tab.CONNECT else Tab.STATUS) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it.values.all { v -> v }
        if (granted) vm.connectLast()
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(blePermissions()) else vm.connectLast()
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(t.label) },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.STATUS -> Icons.Filled.Info
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                    Tab.CONTROLS -> Icons.Filled.Build
                                    Tab.LOG -> Icons.Filled.List
                                    Tab.CONNECT -> Icons.Filled.Share
                                }, contentDescription = t.label
                            )
                        }
                    )
                }
            }
        }
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            Tab.STATUS -> StatusScreen(vm, m)
            Tab.SETTINGS -> SettingsScreen(vm, m)
            Tab.CONTROLS -> ControlsScreen(vm, m)
            Tab.LOG -> LogScreen(vm, m)
            Tab.CONNECT -> ConnectScreen(vm, m, granted) { launcher.launch(blePermissions()) }
        }
    }
}
