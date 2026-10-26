package io.github.rafaeldatsch4afk.mibandfaces.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Permissões necessárias para buscar e conectar à banda, conforme a versão do Android. */
fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        // Até o Android 11, a busca Bluetooth exige localização.
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun hasBluetoothPermissions(context: Context): Boolean =
    bluetoothPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

class PermissionGate(private val requestFn: (() -> Unit) -> Unit, val granted: () -> Boolean) {
    /** Executa [action] se as permissões já existem; senão pede e executa depois de concedidas. */
    fun runWithPermission(action: () -> Unit) = requestFn(action)
}

@Composable
fun rememberPermissionGate(onDenied: () -> Unit): PermissionGate {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val action = pending
        pending = null
        if (result.values.all { it }) action?.invoke() else onDenied()
    }
    return remember(launcher) {
        PermissionGate(
            requestFn = { action ->
                if (hasBluetoothPermissions(context)) {
                    action()
                } else {
                    pending = action
                    launcher.launch(bluetoothPermissions())
                }
            },
            granted = { hasBluetoothPermissions(context) },
        )
    }
}
