package io.github.rafaeldatsch4afk.mibandfaces.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.protocol.BleV1Link
import io.github.rafaeldatsch4afk.mibandfaces.protocol.GattIo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * GATT da Mi Band 8 para o [BleV1Link]: conecta por BLE, pede MTU 512 (como o Gadgetbridge),
 * encontra o serviço FE95 e liga as notificações das características 0051–0055. O Android só
 * aceita uma operação GATT por vez, então as escritas esperam a confirmação da anterior.
 */
@SuppressLint("MissingPermission") // as permissões são pedidas na tela de conexão
class AndroidGattIo(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: PacketLog,
) : GattIo {

    override val description = "Bluetooth LE V1 (GATT FE95)"

    private var gatt: BluetoothGatt? = null
    private val chars = HashMap<UUID, BluetoothGattCharacteristic>()
    private var onNotify: ((UUID, ByteArray) -> Unit)? = null
    private var onClosed: ((Throwable?) -> Unit)? = null

    @Volatile
    private var closedByUs = false

    @Volatile
    private var maxWriteSize = 20

    private val opLock = Mutex()
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var pendingDescriptor: CompletableDeferred<Boolean>? = null
    private var discovered: CompletableDeferred<Unit>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log.debug("BLE V1: estado da conexão = $newState (status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.requestMtu(512)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val error = TransportException("BLE desconectado (status $status)")
                discovered?.completeExceptionally(error)
                pendingWrite?.complete(false)
                pendingDescriptor?.complete(false)
                g.close()
                if (gatt === g) gatt = null
                onClosed?.invoke(if (closedByUs) null else error)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) maxWriteSize = mtu - 3
            log.info("BLE V1: MTU = $mtu (escritas de até $maxWriteSize bytes)")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(BleV1Link.SERVICE)
            if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                discovered?.completeExceptionally(TransportException("Serviço FE95 não encontrado nesta banda"))
                return
            }
            for (uuid in BleV1Link.ALL) service.getCharacteristic(uuid)?.let { chars[uuid] = it }
            val missing = BleV1Link.REQUIRED - chars.keys
            if (missing.isNotEmpty()) {
                discovered?.completeExceptionally(
                    TransportException("A banda não tem as características do BLE V1 (${missing.joinToString { it.toString().substring(4, 8) }}): não é uma Mi Band 8?"),
                )
                return
            }
            discovered?.complete(Unit)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pendingDescriptor?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify?.invoke(c.uuid, value.copyOf())
        }

        // Android 12 e anteriores
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = c.value ?: return
            onNotify?.invoke(c.uuid, value.copyOf())
        }
    }

    override suspend fun open(onNotify: (UUID, ByteArray) -> Unit, onClosed: (Throwable?) -> Unit): GattIo.Opened {
        this.onNotify = onNotify
        this.onClosed = onClosed
        closedByUs = false
        chars.clear()
        val ready = CompletableDeferred<Unit>()
        discovered = ready
        log.info("BLE V1: conectando via GATT")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw TransportException("connectGatt retornou null")
        try {
            withTimeout(OPEN_TIMEOUT_MS) { ready.await() }
            // Mesma ordem do Gadgetbridge: 0052, 0051, 0053, 0055.
            for (uuid in listOf(BleV1Link.CMD_WRITE, BleV1Link.CMD_READ, BleV1Link.ACTIVITY, BleV1Link.UPLOAD)) {
                chars[uuid]?.let { enableNotifications(it) }
            }
        } catch (e: Exception) {
            close()
            throw if (e is TransportException) e else TransportException("Tempo esgotado ao conectar por BLE", e)
        } finally {
            discovered = null
        }
        log.info("BLE V1: notificações ativas em ${chars.keys.joinToString { it.toString().substring(4, 8) }}")
        return GattIo.Opened(maxWriteSize, chars.keys.toSet())
    }

    private suspend fun enableNotifications(c: BluetoothGattCharacteristic) = opLock.withLock {
        val g = gatt ?: throw TransportException("BLE não está conectado")
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD_UUID) ?: return@withLock
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val done = CompletableDeferred<Boolean>()
        pendingDescriptor = done
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        if (!ok || !withTimeout(WRITE_TIMEOUT_MS) { done.await() }) {
            throw TransportException("Não foi possível ativar as notificações da ${c.uuid.toString().substring(4, 8)}")
        }
    }

    override suspend fun write(characteristic: UUID, value: ByteArray, withoutResponse: Boolean) = opLock.withLock {
        val g = gatt ?: throw TransportException("BLE não está conectado")
        val c = chars[characteristic] ?: throw TransportException("Característica ${characteristic.toString().substring(4, 8)} indisponível")
        val type = if (withoutResponse && c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val done = CompletableDeferred<Boolean>()
        pendingWrite = done
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!started) throw TransportException("A pilha BLE recusou a escrita")
        if (!withTimeout(WRITE_TIMEOUT_MS) { done.await() }) throw TransportException("Falha na escrita BLE")
    }

    override fun close() {
        closedByUs = true
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val OPEN_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
    }
}
