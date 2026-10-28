package io.github.rafaeldatsch4afk.mibandfaces.testutil

import io.github.rafaeldatsch4afk.mibandfaces.protocol.BleV1Link
import io.github.rafaeldatsch4afk.mibandfaces.protocol.GattIo
import io.github.rafaeldatsch4afk.mibandfaces.protocol.SppV2
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lado GATT de uma Mi Band 8 simulada (protocolo BLE V1, escrito aqui a partir da descrição do
 * Gadgetbridge/miband-hack, separado do código do app). Os comandos vão para a [band], que cuida
 * da autenticação e da cifra.
 */
class FakeBleV1Peer(
    private val band: FakeBand,
    private val maxWriteSize: Int = 64,
    private val withUpload: Boolean = true,
) : GattIo {
    override val description = "BLE V1 simulado"

    private val executor = Executors.newSingleThreadExecutor()
    private var onNotify: ((UUID, ByteArray) -> Unit)? = null

    /** Quantas transferências em partes o celular mandou, por característica. */
    val chunkedFromPhone = CopyOnWriteArrayList<UUID>()

    /** Se > 0, a banda finge que perdeu esta parte (índice a partir de 1) na próxima transferência. */
    @Volatile
    var dropChunkOnce = 0

    /** Quantas vezes a banda pediu partes de novo. */
    @Volatile
    var resendRequests = 0
        private set

    // Recepção (celular → banda), por característica.
    private class Incoming(var parts: Int = 0, var encrypted: Boolean = false, val got: HashMap<Int, ByteArray> = HashMap())

    private val incoming = HashMap<UUID, Incoming>()

    // Envio (banda → celular) pela 0051, um de cada vez.
    private class Out(val encrypted: Boolean, val body: ByteArray)

    private val outQueue = ArrayDeque<Out>()
    private var sending: Out? = null

    init {
        band.attachBleV1(this)
    }

    override suspend fun open(onNotify: (UUID, ByteArray) -> Unit, onClosed: (Throwable?) -> Unit): GattIo.Opened {
        this.onNotify = onNotify
        val chars = if (withUpload) BleV1Link.ALL else BleV1Link.REQUIRED
        return GattIo.Opened(maxWriteSize, chars)
    }

    override suspend fun write(characteristic: UUID, value: ByteArray, withoutResponse: Boolean) {
        check(value.size <= maxWriteSize) { "escrita de ${value.size} bytes passa do MTU" }
        if (characteristic == BleV1Link.UPLOAD) check(withoutResponse) { "o envio de arquivos deveria ser sem resposta" }
        val copy = value.copyOf()
        executor.execute { onWrite(characteristic, copy) }
    }

    override fun close() {
        executor.shutdown()
    }

    fun awaitIdle() {
        val latch = java.util.concurrent.CountDownLatch(1)
        executor.execute { latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
    }

    private fun notify(uuid: UUID, value: ByteArray) {
        onNotify?.invoke(uuid, value)
    }

    private fun u16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun u16At(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun onWrite(uuid: UUID, v: ByteArray) {
        val chunk = u16At(v, 0)
        if (chunk != 0) {
            val inc = incoming.getValue(uuid)
            if (dropChunkOnce == chunk) {
                dropChunkOnce = 0
                return
            }
            inc.got[chunk] = v.copyOfRange(2, v.size)
            if (inc.got.size == inc.parts) {
                notify(uuid, byteArrayOf(0, 0, 1, 0))
                val payload = (1..inc.parts).fold(ByteArray(0)) { acc, i -> acc + inc.got.getValue(i) }
                incoming.remove(uuid)
                deliver(uuid, inc.encrypted, payload, fromChunks = true)
            } else if (chunk == inc.parts) {
                // Última parte chegou mas falta uma no meio: pede de novo, como a banda real.
                val missing = (1..inc.parts).filter { it !in inc.got }
                resendRequests++
                notify(uuid, byteArrayOf(0, 0, 1, 5) + missing.fold(ByteArray(0)) { acc, i -> acc + u16(i) })
            }
            return
        }
        when (v[2].toInt()) {
            0 -> {
                incoming[uuid] = Incoming(parts = u16At(v, 4), encrypted = v[3].toInt() == 1)
                chunkedFromPhone += uuid
                notify(uuid, byteArrayOf(0, 0, 1, 1))
            }
            1 -> when (v[3].toInt()) {
                // Respostas do celular às transferências da banda (pela 0051).
                1 -> sending?.let { out -> sendChunks(out) }
                0 -> {
                    sending = null
                    pump()
                }
            }
            2 -> {
                notify(uuid, byteArrayOf(0, 0, 3, 0))
                val encrypted = v[3].toInt() == 1
                if (encrypted) {
                    // [00 00 02 01 u16 contador | cifrado]
                    deliver(uuid, true, v.copyOfRange(4, v.size), fromChunks = false)
                } else {
                    check(v[3].toInt() == 2) { "byte de cifra inesperado ${v[3]}" }
                    deliver(uuid, false, v.copyOfRange(4, v.size), fromChunks = false)
                }
            }
            3 -> {
                // ACK do celular para um comando único da banda.
                sending = null
                pump()
            }
        }
    }

    private fun deliver(uuid: UUID, encrypted: Boolean, body: ByteArray, fromChunks: Boolean) {
        when (uuid) {
            BleV1Link.CMD_WRITE -> band.receiveV1(SppV2.CHANNEL_PROTOBUF, encrypted, body)
            BleV1Link.UPLOAD -> {
                // Arquivos: contador 0, sem prefixo nas partes; no comando único o contador (0) vai no cabeçalho.
                val ct = if (encrypted && !fromChunks) {
                    check(u16At(body, 0) == 0) { "contador do envio de arquivos deveria ser 0" }
                    body.copyOfRange(2, body.size)
                } else {
                    body
                }
                band.receiveV1(SppV2.CHANNEL_DATA, encrypted, ct)
            }
            else -> error("escrita inesperada em $uuid")
        }
    }

    /** Chamado pela banda (qualquer thread): manda um comando para o celular pela 0051. */
    fun sendToHost(encrypted: Boolean, body: ByteArray) {
        executor.execute {
            outQueue.addLast(Out(encrypted, body))
            pump()
        }
    }

    private fun pump() {
        if (sending != null) return
        val out = outQueue.removeFirstOrNull() ?: return
        sending = out
        if (out.body.size + 6 > maxWriteSize) {
            val parts = (out.body.size + maxWriteSize - 3) / (maxWriteSize - 2)
            notify(BleV1Link.CMD_READ, byteArrayOf(0, 0, 0, if (out.encrypted) 1 else 0) + u16(parts))
        } else {
            notify(BleV1Link.CMD_READ, byteArrayOf(0, 0, 2, if (out.encrypted) 1 else 2) + out.body)
        }
    }

    private fun sendChunks(out: Out) {
        val size = maxWriteSize - 2
        var i = 0
        while (i * size < out.body.size) {
            notify(BleV1Link.CMD_READ, u16(i + 1) + out.body.copyOfRange(i * size, minOf(out.body.size, (i + 1) * size)))
            i++
        }
    }
}
