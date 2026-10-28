package io.github.rafaeldatsch4afk.mibandfaces.protocol

import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.util.toHexPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Uma característica do protocolo BLE "V1" da Mi Band 8 (XiaomiCharacteristicV1 no Gadgetbridge,
 * conferido com o ble-v1-link.ts do miband-hack, testado numa Mi Band 8 real).
 *
 * Pacotes de controle começam com `00 00` e o byte 2 é o tipo:
 * - `0` início de transferência em partes `[00 00 00 cifrado u16 n]` → quem recebe responde `00 00 01 01`
 * - `1` resposta de transferência em partes, byte 3: 0 terminou, 1 pode mandar as partes,
 *   2 recusou (NACK), 5 reenvie as partes `[u16…]`
 * - `2` comando único `[00 00 02 cifra(1 = cifrado, 2 = puro) (u16 contador) payload]` → ACK `00 00 03 00`
 * - `3` ACK de um comando único; byte 3 = 0 é OK
 *
 * Partes: `u16 índice (a partir de 1) | dados`, com até (tamanho da escrita − 2) bytes de dados.
 *
 * Cifra (depois da autenticação): AES-CCM; o nonce leva um contador que começa em 1 e vai no
 * cabeçalho do comando único ou na frente do payload em partes. Na característica de arquivos
 * (0055) o contador é sempre 0 e não vai no pacote ([incrementNonce] = false).
 *
 * Os bytes crus vão para [rawWrite], que só enfileira (quem escreve no GATT é o enlace, um por vez).
 */
internal class CharacteristicV1(
    val name: String,
    private val log: PacketLog,
    private val scope: CoroutineScope,
    private val rawWrite: (ByteArray) -> Unit,
    private val incrementNonce: Boolean = true,
    private val ackTimeoutMs: Long = 10_000,
    /** A banda não confirmou a tempo: o enlace deve ser derrubado. */
    private val onAckTimeout: (String) -> Unit = {},
) {
    /** Tamanho máximo de cada escrita (MTU − 3). */
    @Volatile
    var maxWriteSize = 244

    /** Cifra da sessão; null antes da autenticação (tudo vai em texto puro). */
    @Volatile
    var cipher: V1Cipher? = null

    /** Payload completo recebido da banda, já decifrado. */
    @Volatile
    var handler: ((ByteArray) -> Unit)? = null

    private class Payload(val bytes: ByteArray, val encrypt: Boolean, val done: CompletableDeferred<Unit>)

    private val lock = Any()
    private val queue = ArrayDeque<Payload>()
    private var current: Payload? = null
    private var currentBytes = ByteArray(0)
    private var currentChunkData = 0
    private var waitingAck = false
    private var sendingChunked = false
    private var encryptedIndex = 1
    private var ackJob: Job? = null

    private var numChunks = 0
    private var incomingEncrypted = false
    private val received = HashMap<Int, ByteArray>()
    private var missingJob: Job? = null
    private var closed = false

    /** Enfileira [bytes]. O Deferred completa com o ACK da banda, ou falha (NACK, queda). */
    fun write(bytes: ByteArray, encrypt: Boolean): CompletableDeferred<Unit> {
        val payload = Payload(bytes, encrypt, CompletableDeferred())
        synchronized(lock) {
            if (closed) {
                payload.done.completeExceptionally(LinkClosedException("Conexão com a banda encerrada"))
                return payload.done
            }
            queue.addLast(payload)
            sendNextLocked()
        }
        return payload.done
    }

    /** Depois da autenticação: o contador volta a 1 e o que estava sendo recebido é descartado. */
    fun reset() {
        synchronized(lock) {
            encryptedIndex = 1
            numChunks = 0
            received.clear()
            missingJob?.cancel()
        }
    }

    /** O enlace caiu: falha tudo o que estava pendente. */
    fun failAll(error: Exception) {
        val pending = synchronized(lock) {
            closed = true
            ackJob?.cancel()
            missingJob?.cancel()
            val all = listOfNotNull(current) + queue
            current = null
            queue.clear()
            all
        }
        for (p in pending) p.done.completeExceptionally(error)
    }

    private fun sendNextLocked() {
        if (waitingAck || sendingChunked || closed) return
        val next = queue.removeFirstOrNull() ?: return
        current = next
        val c = if (next.encrypt) cipher else null
        if (next.encrypt && c == null) {
            current = null
            next.done.completeExceptionally(LinkException("Sessão ainda não autenticada"))
            sendNextLocked()
            return
        }
        var bytes = next.bytes
        if (c != null) bytes = c.encrypt(bytes, if (incrementNonce) encryptedIndex else 0)
        val size = maxWriteSize
        if (bytes.size + 6 > size) {
            if (c != null && incrementNonce) bytes = u16(encryptedIndex++) + bytes
            currentBytes = bytes
            currentChunkData = size - 2
            sendingChunked = true
            val parts = (bytes.size + currentChunkData - 1) / currentChunkData
            log.tx("BLE V1 $name: início em $parts partes${if (c != null) " (cifrado)" else ""}", bytes)
            rawWrite(byteArrayOf(0, 0, 0, if (c != null) 1 else 0) + u16(parts))
        } else {
            val header = if (c != null) {
                byteArrayOf(0, 0, 2, 1) + u16(if (incrementNonce) encryptedIndex++ else 0)
            } else {
                byteArrayOf(0, 0, 2, 2)
            }
            waitingAck = true
            val packet = header + bytes
            log.tx("BLE V1 $name: comando único${if (c != null) " (cifrado)" else ""}", packet)
            rawWrite(packet)
        }
        armAckTimeoutLocked()
    }

    private fun armAckTimeoutLocked() {
        ackJob?.cancel()
        val waitingFor = current ?: return
        ackJob = scope.launch {
            delay(ackTimeoutMs)
            val expired = synchronized(lock) { current === waitingFor && !closed }
            if (expired) onAckTimeout("A banda não confirmou o pacote em $name a tempo")
        }
    }

    private fun finishCurrentLocked(error: Exception?): Payload? {
        ackJob?.cancel()
        val done = current
        current = null
        waitingAck = false
        sendingChunked = false
        return done?.also { if (error == null) it.done.complete(Unit) else it.done.completeExceptionally(error) }
    }

    private fun sendChunkLocked(index: Int) {
        val start = index * currentChunkData
        if (start < 0 || start >= currentBytes.size) return
        val end = minOf(currentBytes.size, start + currentChunkData)
        rawWrite(u16(index + 1) + currentBytes.copyOfRange(start, end))
    }

    /** Notificação recebida nesta característica. */
    fun onNotify(value: ByteArray) {
        if (value.size < 2) return
        var delivery: ByteArray? = null
        synchronized(lock) {
            val chunk = u16At(value, 0)
            if (chunk != 0) {
                delivery = onChunkLocked(chunk, value)
            } else if (value.size >= 3) {
                delivery = onControlLocked(value)
            }
        }
        delivery?.let { body ->
            try {
                handler?.invoke(body)
            } catch (e: Exception) {
                log.error("Erro ao tratar pacote de $name", e)
            }
        }
    }

    private fun onChunkLocked(chunk: Int, value: ByteArray): ByteArray? {
        if (chunk > numChunks) {
            log.warn("BLE V1 $name: parte $chunk além de $numChunks, ignorada")
            return null
        }
        received[chunk] = value.copyOfRange(2, value.size)
        armMissingTimerLocked()
        if (received.size < numChunks) return null
        missingJob?.cancel()
        rawWrite(byteArrayOf(0, 0, 1, 0))
        val out = java.io.ByteArrayOutputStream()
        for (i in 1..numChunks) out.write(received.getValue(i))
        numChunks = 0
        received.clear()
        val payload = out.toByteArray()
        log.rx("BLE V1 $name: recebido em partes", payload)
        return if (incomingEncrypted) decryptLocked(payload) else payload
    }

    private fun onControlLocked(value: ByteArray): ByteArray? {
        when (value[2].toInt()) {
            0 -> {
                // A banda vai mandar algo em partes.
                if (value.size < 6) return null
                incomingEncrypted = value[3].toInt() == 1
                numChunks = u16At(value, 4)
                received.clear()
                rawWrite(byteArrayOf(0, 0, 1, 1))
            }
            1 -> when (if (value.size > 3) value[3].toInt() else -1) {
                0 -> {
                    finishCurrentLocked(null)
                    sendNextLocked()
                }
                1 -> {
                    if (!sendingChunked) {
                        log.warn("BLE V1 $name: pedido de partes sem nada sendo enviado")
                        return null
                    }
                    val parts = (currentBytes.size + currentChunkData - 1) / currentChunkData
                    for (i in 0 until parts) sendChunkLocked(i)
                }
                2 -> {
                    log.warn("BLE V1 $name: a banda recusou a transferência em partes (NACK)")
                    finishCurrentLocked(NackException("A banda recusou o pacote em $name (NACK)"))
                    sendNextLocked()
                }
                5 -> {
                    var o = 4
                    val missing = mutableListOf<Int>()
                    while (o + 1 < value.size) {
                        missing += u16At(value, o)
                        o += 2
                    }
                    log.warn("BLE V1 $name: a banda pediu de novo as partes $missing")
                    for (index in missing) sendChunkLocked(index - 1)
                }
                else -> log.warn("BLE V1 $name: resposta de partes desconhecida ${value.toHexPreview()}")
            }
            2 -> {
                // Comando único da banda.
                rawWrite(PAYLOAD_ACK)
                if (value.size < 4) return null
                val body = value.copyOfRange(4, value.size)
                log.rx("BLE V1 $name: comando único", body)
                return if (value[3].toInt() == 1) decryptLocked(body) else body
            }
            3 -> {
                val ok = value.size > 3 && value[3].toInt() == 0
                finishCurrentLocked(if (ok) null else NackException("A banda recusou o comando em $name"))
                sendNextLocked()
            }
            else -> log.warn("BLE V1 $name: pacote desconhecido ${value.toHexPreview()}")
        }
        return null
    }

    private fun decryptLocked(body: ByteArray): ByteArray? {
        val c = cipher
        if (c == null) {
            log.warn("BLE V1 $name: pacote cifrado antes da autenticação, descartado")
            return null
        }
        return try {
            c.decrypt(body)
        } catch (e: CcmTagException) {
            log.warn("BLE V1 $name: pacote descartado, ${e.message}")
            null
        }
    }

    /** Se faltar alguma parte por 5 s, pede de novo (XiaomiCharacteristicV1.rescheduleTimeoutTask). */
    private fun armMissingTimerLocked() {
        missingJob?.cancel()
        missingJob = scope.launch {
            delay(MISSING_CHUNK_TIMEOUT_MS)
            synchronized(lock) {
                if (numChunks == 0 || closed) return@launch
                val missing = (1..numChunks).filter { it !in received }
                val n = minOf(missing.size, (maxWriteSize - 4) / 2)
                if (n == 0) return@launch
                log.warn("BLE V1 $name: faltam as partes $missing; pedindo de novo")
                rawWrite(byteArrayOf(0, 0, 1, 5) + missing.take(n).fold(ByteArray(0)) { acc, i -> acc + u16(i) })
            }
        }
    }

    companion object {
        private val PAYLOAD_ACK = byteArrayOf(0, 0, 3, 0)
        private const val MISSING_CHUNK_TIMEOUT_MS = 5_000L

        private fun u16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
        private fun u16At(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    }
}
