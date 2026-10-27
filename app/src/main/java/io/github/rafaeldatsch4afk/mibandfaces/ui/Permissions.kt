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

/** Notificação (Android 13+): só para mostrar o aviso "conectado". Não é obrigatória. */
private fun notificationPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

@Composable
fun rememberPermissionGate(onDenied: () -> Unit): PermissionGate {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pending
        pending = null
        // Só o Bluetooth é obrigatório; recusar a notificação não impede de conectar.
        if (hasBluetoothPermissions(context)) action?.invoke() else onDenied()
    }
    return remember(launcher) {
        PermissionGate(
            requestFn = { action ->
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val missing = (bluetoothPermissions() + notificationPermissions()).filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED &&
                        // A notificação é pedida uma única vez.
                        (it != Manifest.permission.POST_NOTIFICATIONS || !prefs.getBoolean("asked_notifications", false))
                }
                if (missing.isEmpty()) {
                    action()
                } else {
                    if (Manifest.permission.POST_NOTIFICATIONS in missing) prefs.edit().putBoolean("asked_notifications", true).apply()
                    pending = action
                    launcher.launch(missing.toTypedArray())
                }
            },
            granted = { hasBluetoothPermissions(context) },
        )
    }
}
