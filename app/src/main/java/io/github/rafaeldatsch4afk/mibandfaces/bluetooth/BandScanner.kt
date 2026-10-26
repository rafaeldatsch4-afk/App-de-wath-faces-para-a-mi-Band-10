package io.github.rafaeldatsch4afk.mibandfaces.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Um aparelho Bluetooth encontrado na busca (ou já pareado). */
data class FoundDevice(
    val address: String,
    val name: String?,
    val rssi: Int? = null,
    val bonded: Boolean = false,
    val seenByBle: Boolean = false,
    val seenByClassic: Boolean = false,
) {
    val isMiBand10: Boolean get() = looksLikeMiBand10(name)
}

/**
 * Mesmo padrão do Gadgetbridge (MiBand10Coordinator.getSupportedDeviceName), com uma folga para
 * nomes como "Mi Band 10". A versão Pro tem outra tela e não é o alvo deste app.
 */
fun looksLikeMiBand10(name: String?): Boolean {
    if (name == null) return false
    if (Regex("^Xiaomi Smart Band 10 [0-9A-F]{4}$").matches(name)) return true
    val lower = name.lowercase()
    return "band 10" in lower && "pro" !in lower
}

/**
 * Busca a banda por BLE e por Bluetooth Clássico ao mesmo tempo, e lista também os aparelhos já
 * pareados. A Mi Band 10 anuncia no BLE, mas a conexão preferida (como no Gadgetbridge) é SPP.
 */
@SuppressLint("MissingPermission") // as permissões são verificadas antes de chamar start()
class BandScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val log: PacketLog,
) {
    private val _devices = MutableStateFlow<Map<String, FoundDevice>>(emptyMap())
    val devices: StateFlow<Map<String, FoundDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.device() ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    merge(device, rssi.takeIf { it != Short.MIN_VALUE }?.toInt(), classic = true)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> log.debug("Busca clássica terminou")
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.device() ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    log.info("Pareamento com ${device.address}: ${bondStateName(state)}")
                    merge(device, null)
                }
            }
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            merge(result.device, result.rssi, ble = true, advertisedName = result.scanRecord?.deviceName)
        }

        override fun onScanFailed(errorCode: Int) {
            log.warn("Busca BLE falhou (código $errorCode)")
        }
    }

    fun refreshBonded() {
        val bonded = adapter?.bondedDevices ?: return
        for (device in bonded) merge(device, null)
    }

    fun start() {
        val a = adapter ?: run {
            log.error("Este aparelho não tem Bluetooth")
            return
        }
        if (!a.isEnabled) {
            log.warn("O Bluetooth está desligado")
            return
        }
        registerReceiver()
        refreshBonded()
        _scanning.value = true
        log.info("Buscando a banda (BLE + Clássico)…")
        a.bluetoothLeScanner?.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            bleCallback,
        )
        a.startDiscovery()
    }

    fun stop() {
        if (!_scanning.value) return
        _scanning.value = false
        adapter?.let {
            runCatching { it.bluetoothLeScanner?.stopScan(bleCallback) }
            runCatching { it.cancelDiscovery() }
        }
        log.debug("Busca encerrada")
    }

    /** Pede ao Android para parear (bond). A banda mostra um pedido de confirmação na tela. */
    fun bond(address: String): Boolean {
        val device = adapter?.getRemoteDevice(address) ?: return false
        registerReceiver()
        stop()
        log.info("Pedindo pareamento com $address — confirme na tela da banda")
        return device.createBond()
    }

    fun remoteDevice(address: String): BluetoothDevice? = adapter?.getRemoteDevice(address)

    fun release() {
        stop()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // São broadcasts do sistema: NOT_EXPORTED impede que outro app forje esses eventos.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun merge(
        device: BluetoothDevice,
        rssi: Int?,
        ble: Boolean = false,
        classic: Boolean = false,
        advertisedName: String? = null,
    ) {
        val name = device.name ?: advertisedName
        _devices.update { map ->
            val old = map[device.address]
            val next = FoundDevice(
                address = device.address,
                name = name ?: old?.name,
                rssi = rssi ?: old?.rssi,
                bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                seenByBle = ble || old?.seenByBle == true,
                seenByClassic = classic || old?.seenByClassic == true,
            )
            if (old == null && next.isMiBand10) log.info("Encontrada: ${next.name} (${next.address})")
            map + (device.address to next)
        }
    }

    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun bondStateName(state: Int) = when (state) {
        BluetoothDevice.BOND_BONDED -> "pareado"
        BluetoothDevice.BOND_BONDING -> "pareando…"
        BluetoothDevice.BOND_NONE -> "não pareado"
        else -> "desconhecido ($state)"
    }
}
