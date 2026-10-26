package io.github.rafaeldatsch4afk.mibandfaces.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Clássico, perfil de porta serial (SPP) sobre RFCOMM. É o transporte que o
 * Gadgetbridge usa para a Mi Band 10 (MiBand10Coordinator.getConnectionType() = BT_CLASSIC).
 * A banda precisa estar pareada (bond) com o celular no Android.
 */
@SuppressLint("MissingPermission") // as permissões são pedidas na tela de conexão
class SppTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val log: PacketLog,
) : BandTransport {

    override val description = "Bluetooth Clássico (SPP/RFCOMM)"
    override val needsVersionProbe = true

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var closedByUs = false
    private val writeLock = Mutex()

    override suspend fun open(listener: BandTransport.Listener) = withContext(Dispatchers.IO) {
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            throw TransportException("A banda não está pareada com o Android. Toque em \"Parear\" primeiro.")
        }
        // A busca de dispositivos atrapalha (e muito) a abertura do RFCOMM.
        adapter.cancelDiscovery()
        closedByUs = false
        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: IOException) {
            throw TransportException("Não foi possível criar o socket RFCOMM", e)
        }
        log.info("SPP: conectando ao RFCOMM ($SPP_UUID)")
        try {
            s.connect()
        } catch (e: IOException) {
            runCatching { s.close() }
            throw TransportException("Falha ao abrir o RFCOMM. A banda está perto, ligada e com o Mi Fitness fechado?", e)
        }
        socket = s
        log.info("SPP: RFCOMM conectado")
        startReader(s, listener)
    }

    private fun startReader(s: BluetoothSocket, listener: BandTransport.Listener) {
        Thread({
            val buffer = ByteArray(4096)
            var cause: Throwable? = null
            try {
                val input = s.inputStream
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) listener.onData(buffer.copyOf(n))
                }
            } catch (e: IOException) {
                cause = e
            }
            listener.onClosed(if (closedByUs) null else cause ?: IOException("conexão encerrada pela banda"))
        }, "spp-reader").apply { isDaemon = true }.start()
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket ?: throw TransportException("SPP não está conectado")
        writeLock.withLock {
            try {
                s.outputStream.write(bytes)
                s.outputStream.flush()
            } catch (e: IOException) {
                throw TransportException("Falha ao escrever no RFCOMM", e)
            }
        }
    }

    override fun close() {
        closedByUs = true
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        /** UUID padrão do Serial Port Profile (XiaomiUuids.UUID_SERVICE_SERIAL_PORT_PROFILE). */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
