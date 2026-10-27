package io.github.rafaeldatsch4afk.mibandfaces.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog

/**
 * Só diagnóstico: registra no log quando o Android conecta ou desconecta o enlace de rádio (ACL)
 * com a banda escolhida, e com qual transporte (BR/EDR = Bluetooth Clássico, LE = BLE). Serve para
 * distinguir "o rádio caiu" de "a banda ou o app fechou o canal serial" e para ver se outro app
 * (como o Mi Fitness) conectou à banda ao mesmo tempo.
 */
class AclLogger(
    private val context: Context,
    private val log: PacketLog,
    private val selectedAddress: () -> String?,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            if (device.address != selectedAddress()) return
            val what = when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> "conectado"
                BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED -> "desconexão pedida"
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> "desconectado"
                else -> return
            }
            val transport = when (intent.getIntExtra("android.bluetooth.device.extra.TRANSPORT", -1)) {
                1 -> "BR/EDR (Clássico)"
                2 -> "LE (BLE)"
                else -> "transporte desconhecido"
            }
            // Mostra os demais extras (alguns Androids trazem o motivo da desconexão).
            val extras = intent.extras?.keySet().orEmpty()
                .filter { it != BluetoothDevice.EXTRA_DEVICE && !it.endsWith("TRANSPORT") }
                .joinToString { key -> "${key.substringAfterLast('.')}=${intent.extras?.get(key)}" }
            log.info("Bluetooth (ACL): banda $what, $transport${if (extras.isNotEmpty()) ", $extras" else ""}")
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // São broadcasts do sistema: NOT_EXPORTED impede que outro app forje esses eventos.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }
}
