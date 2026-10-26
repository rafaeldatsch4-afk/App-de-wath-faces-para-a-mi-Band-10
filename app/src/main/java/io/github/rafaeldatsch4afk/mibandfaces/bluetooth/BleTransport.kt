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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * BLE "V2": o mesmo enquadramento SPP V2, mas sobre GATT. Serviço FE95, notificações na
 * característica 005E (banda → celular) e escrita na 005F (celular → banda).
 * Fonte: XiaomiUuids / XiaomiBleProtocolV2 no Gadgetbridge. O miband-hack observou essas
 * características numa Mi Band 10 real (docs/captures/mi-band-10-native-macos.md).
 *
 * O Gadgetbridge usa o Bluetooth Clássico para a Mi Band 10; este transporte fica como
 * alternativa, ainda não validada com a banda real.
 */
@SuppressLint("MissingPermission") // as permissões são pedidas na tela de conexão
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: PacketLog,
) : BandTransport {

    override val description = "Bluetooth Low Energy (GATT FE95)"
    override val needsVersionProbe = false

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var listener: BandTransport.Listener? = null

    @Volatile
    private var closedByUs = false

    /** Tamanho máximo de cada escrita: MTU - 3 bytes do cabeçalho ATT. */
    @Volatile
    private var maxWriteSize = 20

    private val writeLock = Mutex()
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var setup: CompletableDeferred<Unit>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log.debug("BLE: estado da conexão = $newState (status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // Pede o maior MTU possível, como o Gadgetbridge (a banda responde com o que suporta).
                if (!g.requestMtu(512)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val error = TransportException("BLE desconectado (status $status)")
                setup?.completeExceptionally(error)
                pendingWrite?.complete(false)
                g.close()
                if (gatt === g) gatt = null
                listener?.onClosed(if (closedByUs) null else error)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) maxWriteSize = mtu - 3
            log.info("BLE: MTU = $mtu (escritas de até $maxWriteSize bytes)")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            val rxChar = service?.getCharacteristic(RX_UUID)
            val txChar = service?.getCharacteristic(TX_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || rxChar == null || txChar == null) {
                setup?.completeExceptionally(TransportException("Serviço FE95 (005E/005F) não encontrado nesta banda"))
                return
            }
            rx = rxChar
            tx = txChar
            g.setCharacteristicNotification(rxChar, true)
            val cccd = rxChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                setup?.complete(Unit)
                return
            }
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!ok) setup?.completeExceptionally(TransportException("Não foi possível ativar as notificações da 005E"))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) setup?.complete(Unit)
            else setup?.completeExceptionally(TransportException("Falha ao ativar notificações (status $status)"))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == RX_UUID) listener?.onData(value.copyOf())
        }

        // Android 12 e anteriores
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = c.value ?: return
            if (c.uuid == RX_UUID) listener?.onData(value.copyOf())
        }
    }

    override suspend fun open(listener: BandTransport.Listener) {
        this.listener = listener
        closedByUs = false
        val ready = CompletableDeferred<Unit>()
        setup = ready
        log.info("BLE: conectando via GATT")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw TransportException("connectGatt retornou null")
        try {
            withTimeout(OPEN_TIMEOUT_MS) { ready.await() }
        } catch (e: Exception) {
            close()
            throw if (e is TransportException) e else TransportException("Tempo esgotado ao conectar por BLE", e)
        } finally {
            setup = null
        }
        log.info("BLE: pronto (notificações ativas em 005E)")
    }

    override suspend fun write(bytes: ByteArray) {
        writeLock.withLock {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + maxWriteSize)
                writeChunk(bytes.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    private suspend fun writeChunk(chunk: ByteArray) {
        val g = gatt ?: throw TransportException("BLE não está conectado")
        val c = tx ?: throw TransportException("Característica 005F indisponível")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val done = CompletableDeferred<Boolean>()
        pendingWrite = done
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, chunk, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = chunk
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!started) throw TransportException("A pilha BLE recusou a escrita")
        val ok = withTimeout(WRITE_TIMEOUT_MS) { done.await() }
        if (!ok) throw TransportException("Falha na escrita BLE")
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
        val SERVICE_UUID: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
        val RX_UUID: UUID = UUID.fromString("0000005e-0000-1000-8000-00805f9b34fb")
        val TX_UUID: UUID = UUID.fromString("0000005f-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val OPEN_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
    }
}
