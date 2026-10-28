package io.github.rafaeldatsch4afk.mibandfaces.protocol

import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BandTransport
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.util.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout

/** Cifra do payload depois da autenticação (AES-CTR no SPP V2). */
interface PayloadCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(cipher: ByteArray): ByteArray
}

/** Erros da camada de enlace. A mensagem já vem pronta para mostrar ao usuário. */
open class LinkException(message: String, cause: Throwable? = null) : Exception(message, cause)
class LinkClosedException(message: String, cause: Throwable? = null) : LinkException(message, cause)
class AckTimeoutException(message: String) : LinkException(message)
class NackException(message: String) : LinkException(message)

data class LinkConfig(
    /**
     * Quantos quadros DATA podem ficar sem ACK ao mesmo tempo. 1 = para-e-espera: cada pedaço só
     * sai depois da confirmação (ACK) do anterior. A banda também anuncia um limite próprio
     * (TX window); o menor dos dois vale.
     */
    val maxWindow: Int = 1,
    /** Mesmo valor que anunciamos na configuração de sessão (10000 ms) e que o Gadgetbridge usa. */
    val resendTimeoutMs: Long = 10_000,
    /** Timeouts seguidos sem nenhum ACK antes de considerar a conexão morta (Gadgetbridge: 3). */
    val maxTimeoutsWithoutAck: Int = 3,
    /** Quantas vezes aceitamos um NACK para o mesmo quadro antes de desistir. */
    val maxNacksPerFrame: Int = 5,
    val versionProbeTimeoutMs: Long = 5_000,
    val sessionTimeoutMs: Long = 5_000,
)

/**
 * Enlace com a banda: sondagem de versão (SPP V1), sessão SPP V2, números de sequência, ACK/NACK,
 * reenvio por timeout e janela de envio. Baseado em XiaomiSppSupport, XiaomiSppProtocolV2 e
 * XiaomiSppV2SendWindow do Gadgetbridge.
 *
 * Os ACKs são cumulativos e a banda aceita quadros estritamente em ordem (go-back-N): um ACK(n)
 * confirma todos os quadros até n; um NACK(n) diz que a banda espera o quadro n.
 *
 * Se a banda responder à sondagem com versão < 2, ou não responder em 5 s, a sessão segue em
 * SPP V1 (XiaomiSppProtocolV1): sem ACK, com AES-CCM por pacote. Firmwares antigos da Mi Band 9
 * podem estar nesse caso; a Mi Band 10 real fala V2.
 */
class XiaomiLink(
    private val transport: BandTransport,
    private val log: PacketLog,
    private val config: LinkConfig = LinkConfig(),
) : BandTransport.Listener, BandLink {

    override val description: String get() = transport.description + if (mode == Mode.V1) ", SPP V1" else ""

    private enum class Mode { PROBING, V1, V2, CLOSED }

    /** Bytes a escrever e, no V1 (sem ACK), quem espera a escrita terminar. */
    private class Outgoing(val bytes: ByteArray, val written: CompletableDeferred<Unit>? = null)

    private class Outstanding(val seq: Int, val frame: ByteArray, val acked: CompletableDeferred<Unit>) {
        var nacks = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdAt = System.currentTimeMillis()

    @Volatile
    private var lastRxAt = 0L

    @Volatile
    private var lastTxAt = 0L
    private val lock = Any()
    private val writeQueue = Channel<Outgoing>(Channel.UNLIMITED)

    private var listener: BandLink.Listener? = null
    private var mode = Mode.PROBING
    private var v1Buffer = ByteArray(0)
    private val parser = SppV2.Parser { log.warn("Quadro descartado: $it") }
    private var versionReply: CompletableDeferred<ByteArray>? = null
    private var sessionReply: CompletableDeferred<SppV2.SessionConfig>? = null

    private var nextSeq = 0
    private val outstanding = ArrayDeque<Outstanding>()
    private var window = Semaphore(1)
    private var timeoutJob: Job? = null
    private var timeoutsWithoutAck = 0
    private var lastRxSeq: Int? = null

    /**
     * Quadro já reenviado por causa de um NACK. A banda manda um NACK para cada quadro fora de
     * ordem que recebe; sem isto, uma rajada de NACKs iguais viraria uma rajada de reenvios.
     */
    private var nackResentFor: Int? = null

    /** Cifra usada nos pacotes com opcode 2 (V2). Definida pela autenticação. */
    @Volatile
    var cipher: PayloadCipher? = null

    /** Cifra do V1 (AES-CCM). Definida pela autenticação. */
    @Volatile
    private var v1Cipher: V1Cipher? = null

    /** V1: serial do próximo pacote e contador do nonce CCM (o primeiro pacote cifrado usa 1). */
    private var v1Serial = 0
    private var v1Counter = 0

    /** Se a sessão está em SPP V1. */
    val isV1: Boolean get() = synchronized(lock) { mode == Mode.V1 }

    /** Configuração de sessão recebida da banda (para a tela de status e logs). */
    @Volatile
    var bandSession: SppV2.SessionConfig? = null
        private set

    /** Janela efetiva de envio (mínimo entre a nossa e a da banda). */
    @Volatile
    override var effectiveWindow: Int = 1
        private set

    override val isOpen: Boolean get() = synchronized(lock) { mode != Mode.CLOSED }

    override suspend fun open(listener: BandLink.Listener) {
        this.listener = listener
        transport.open(this)
        scope.launch {
            for (item in writeQueue) {
                try {
                    transport.write(item.bytes)
                    lastTxAt = System.currentTimeMillis()
                    item.written?.complete(Unit)
                } catch (e: Exception) {
                    item.written?.completeExceptionally(LinkClosedException("Falha ao escrever na banda: ${e.message}", e))
                    fail(LinkClosedException("Falha ao escrever na banda: ${e.message}", e))
                    break
                }
            }
        }
        try {
            if (transport.needsVersionProbe) probeVersion() else synchronized(lock) { mode = Mode.V2 }
            if (isV1) {
                effectiveWindow = 1
                log.info("Sessão SPP V1: sem ACK nem janela; cada pacote vai uma vez, como no Gadgetbridge")
            } else {
                startSession()
            }
        } catch (e: Exception) {
            fail(e)
            throw e
        }
    }

    private suspend fun probeVersion() {
        val reply = CompletableDeferred<ByteArray>()
        synchronized(lock) { versionReply = reply }
        val request = SppV1.versionRequest()
        log.tx("SPP V1 pedido de versão", request)
        writeQueue.trySend(Outgoing(request))
        val version = try {
            withTimeout(config.versionProbeTimeoutMs) { reply.await() }
        } catch (e: TimeoutCancellationException) {
            // XiaomiSppSupport: sem resposta à sondagem, a banda fala V1.
            synchronized(lock) { if (mode == Mode.PROBING) mode = Mode.V1 }
            log.warn("A banda não respondeu ao pedido de versão: seguindo em SPP V1, como o Gadgetbridge")
            return
        }
        log.info("Versão do protocolo SPP da banda: ${version.toHex()}" + if (isV1) " → SPP V1" else " → SPP V2")
    }

    private suspend fun startSession() {
        val reply = CompletableDeferred<SppV2.SessionConfig>()
        synchronized(lock) { sessionReply = reply }
        val request = SppV2.sessionStartRequest()
        log.tx("V2 SESSION_CONFIG (início)", request)
        writeQueue.trySend(Outgoing(request))
        val session = try {
            withTimeout(config.sessionTimeoutMs) { reply.await() }
        } catch (e: TimeoutCancellationException) {
            throw LinkException("A banda não respondeu à configuração de sessão SPP V2")
        }
        bandSession = session
        val bandWindow = session.txWindow?.takeIf { it > 0 } ?: 1
        effectiveWindow = minOf(config.maxWindow, bandWindow).coerceAtLeast(1)
        window = Semaphore(effectiveWindow)
        log.info(
            "Sessão V2: versão=${session.version?.toHex()} quadroMáx=${session.maxPacketSize} " +
                "janelaBanda=${session.txWindow} timeout=${session.sendTimeoutMs}ms → janela usada=$effectiveWindow",
        )
    }

    override fun onAuthenticated(keys: SessionKeys) {
        synchronized(lock) {
            if (mode == Mode.V1) v1Cipher = V1Cipher(keys) else cipher = SppV2Cipher(keys)
        }
    }

    /**
     * Enfileira um pacote DATA. Suspende enquanto a janela de envio estiver cheia. O Deferred
     * retornado completa quando a banda confirmar (ACK) o quadro, ou falha se o enlace cair.
     * No V1, que não tem ACK, completa quando o pacote for escrito no socket.
     */
    override suspend fun enqueue(channel: Int, body: ByteArray, encrypt: Boolean): Deferred<Unit> {
        if (!isOpen) throw LinkClosedException("Conexão com a banda encerrada")
        if (isV1) return enqueueV1(channel, body, encrypt)
        val payloadBody = if (encrypt) {
            (cipher ?: throw LinkException("Sessão ainda não autenticada")).encrypt(body)
        } else {
            body
        }
        val opcode = if (encrypt) SppV2.OPCODE_ENCRYPTED else SppV2.OPCODE_PLAIN
        val payload = SppV2.dataPayload(channel, opcode, payloadBody)
        val permits = window
        permits.acquire()
        val item: Outstanding
        synchronized(lock) {
            if (mode == Mode.CLOSED) {
                permits.release()
                throw LinkClosedException("Conexão com a banda encerrada")
            }
            val seq = nextSeq
            nextSeq = (nextSeq + 1) and 0xff
            item = Outstanding(seq, SppV2.encode(SppV2.TYPE_DATA, seq, payload), CompletableDeferred())
            outstanding.addLast(item)
            writeQueue.trySend(Outgoing(item.frame))
            armTimeoutLocked()
        }
        log.tx("V2 DATA seq=${item.seq} canal=$channel op=$opcode", item.frame)
        return item.acked
    }

    /**
     * SPP V1 (XiaomiSppPacketV1.encode): comandos cifrados levam o contador do nonce (u16) na
     * frente; o canal de arquivos é sempre cifrado com o contador 0; os comandos de autenticação vão
     * em texto puro com dataType 2.
     */
    private fun enqueueV1(channel: Int, body: ByteArray, encrypt: Boolean): Deferred<Unit> {
        val written = CompletableDeferred<Unit>()
        val frame: ByteArray
        val what: String
        synchronized(lock) {
            if (mode == Mode.CLOSED) throw LinkClosedException("Conexão com a banda encerrada")
            val serial = v1Serial
            v1Serial = (v1Serial + 1) and 0xff
            when (channel) {
                SppV2.CHANNEL_PROTOBUF -> if (encrypt) {
                    val c = v1Cipher ?: throw LinkException("Sessão ainda não autenticada")
                    v1Counter++
                    val counter = v1Counter
                    val payload = byteArrayOf(counter.toByte(), (counter ushr 8).toByte()) + c.encrypt(body, counter)
                    frame = SppV1.send(SppV1.CHANNEL_PROTO_TX, serial, SppV1.DATA_TYPE_ENCRYPTED, payload)
                    what = "V1 comando cifrado serial=$serial contador=$counter"
                } else {
                    frame = SppV1.send(SppV1.CHANNEL_PROTO_TX, serial, SppV1.DATA_TYPE_AUTH, body)
                    what = "V1 comando (autenticação) serial=$serial"
                }
                SppV2.CHANNEL_DATA -> {
                    val c = v1Cipher ?: throw LinkException("Sessão ainda não autenticada")
                    frame = SppV1.send(SppV1.CHANNEL_MASS, serial, SppV1.DATA_TYPE_ENCRYPTED, c.encrypt(body, 0))
                    what = "V1 dados serial=$serial"
                }
                else -> throw LinkUnsupportedException("Canal $channel não existe no SPP V1")
            }
            writeQueue.trySend(Outgoing(frame, written))
        }
        log.tx(what, frame)
        return written
    }

    override fun close() {
        fail(null)
    }

    // ------------------------------------------------------------------ recepção ---

    override fun onData(bytes: ByteArray) {
        lastRxAt = System.currentTimeMillis()
        log.rx("bytes", bytes)
        val deliveries = mutableListOf<Pair<Int, ByteArray>>()
        synchronized(lock) {
            when (mode) {
                Mode.CLOSED -> return
                Mode.PROBING, Mode.V1 -> handleV1BytesLocked(bytes, deliveries)
                Mode.V2 -> for (frame in parser.feed(bytes)) handleFrameLocked(frame, deliveries)
            }
        }
        // Entrega fora do lock, na ordem de chegada.
        for ((channel, body) in deliveries) {
            try {
                listener?.onPacket(channel, body)
            } catch (e: Exception) {
                log.error("Erro ao tratar pacote do canal $channel", e)
            }
        }
    }

    override fun onClosed(cause: Throwable?) {
        fail(cause?.let { LinkClosedException("A conexão com a banda caiu: ${it.message}", it) })
    }

    /** Sondagem (resposta de versão em V1) e, se a banda ficar no V1, a sessão inteira. */
    private fun handleV1BytesLocked(bytes: ByteArray, deliveries: MutableList<Pair<Int, ByteArray>>) {
        v1Buffer += bytes
        while (v1Buffer.isNotEmpty()) {
            when (val result = SppV1.parse(v1Buffer)) {
                SppV1.ParseResult.Incomplete -> return
                is SppV1.ParseResult.Invalid -> v1Buffer = v1Buffer.copyOfRange(minOf(result.skip, v1Buffer.size), v1Buffer.size)
                is SppV1.ParseResult.Complete -> {
                    val rest = v1Buffer.copyOfRange(result.size, v1Buffer.size)
                    v1Buffer = rest
                    val packet = result.packet
                    if (mode == Mode.PROBING && packet.channel == SppV1.CHANNEL_VERSION) {
                        val version = packet.payload
                        val v2 = version.isNotEmpty() && (version[0].toInt() and 0xff) >= 2
                        mode = if (v2) Mode.V2 else Mode.V1
                        versionReply?.complete(version)
                        if (v2) {
                            // A partir daqui tudo chega em SPP V2.
                            v1Buffer = ByteArray(0)
                            if (rest.isNotEmpty()) for (frame in parser.feed(rest)) handleFrameLocked(frame, deliveries)
                            return
                        }
                        continue
                    }
                    if (mode == Mode.V1) handleV1PacketLocked(packet, deliveries)
                    else log.warn("Pacote SPP V1 inesperado no canal ${packet.channel} durante a sondagem")
                }
            }
        }
    }

    private fun handleV1PacketLocked(packet: SppV1.Packet, deliveries: MutableList<Pair<Int, ByteArray>>) {
        val channel = when (packet.channel) {
            SppV1.CHANNEL_PROTO_RX, SppV1.CHANNEL_PROTO_TX -> SppV2.CHANNEL_PROTOBUF
            SppV1.CHANNEL_MASS -> SppV2.CHANNEL_DATA
            else -> {
                log.debug("V1: pacote ignorado no canal ${packet.channel} (${packet.payload.size} bytes)")
                return
            }
        }
        var body = packet.payload
        if (packet.dataType == SppV1.DATA_TYPE_ENCRYPTED) {
            val c = v1Cipher
            if (c == null) {
                log.warn("V1: pacote cifrado antes da autenticação (serial ${packet.serial})")
                return
            }
            body = try {
                c.decrypt(body)
            } catch (e: CcmTagException) {
                log.warn("V1: pacote descartado, ${e.message} (serial ${packet.serial})")
                return
            }
        }
        log.debug("RX V1 canal=${packet.channel} serial=${packet.serial} dataType=${packet.dataType}")
        deliveries += channel to body
    }

    private fun handleFrameLocked(frame: SppV2.Frame, deliveries: MutableList<Pair<Int, ByteArray>>) {
        when (frame.type) {
            SppV2.TYPE_SESSION_CONFIG -> {
                val session = SppV2.parseSessionConfig(frame.payload)
                log.debug("RX SESSION_CONFIG opcode=${session?.opcode}")
                if (session != null) sessionReply?.complete(session)
            }
            SppV2.TYPE_ACK -> {
                log.debug("RX ACK seq=${frame.seq}")
                onAckLocked(frame.seq)
            }
            SppV2.TYPE_NACK -> {
                log.warn("RX NACK: a banda espera o quadro ${frame.seq}")
                onNackLocked(frame.seq)
            }
            SppV2.TYPE_DATA -> {
                // Todo quadro DATA da banda recebe ACK, como no Gadgetbridge.
                writeQueue.trySend(Outgoing(SppV2.ack(frame.seq)))
                if (frame.payload.size < 2) {
                    log.warn("Quadro DATA curto demais (seq=${frame.seq})")
                    return
                }
                if (lastRxSeq == frame.seq) {
                    log.warn("Quadro DATA repetido (seq=${frame.seq}), ignorado")
                    return
                }
                lastRxSeq = frame.seq
                val channel = frame.payload[0].toInt() and 0x0f
                val opcode = frame.payload[1].toInt() and 0xff
                var body = frame.payload.copyOfRange(2, frame.payload.size)
                if (opcode == SppV2.OPCODE_ENCRYPTED) {
                    val c = cipher
                    if (c == null) log.warn("Pacote cifrado antes da autenticação (seq=${frame.seq})")
                    else body = c.decrypt(body)
                }
                log.debug("RX DATA seq=${frame.seq} canal=$channel op=$opcode")
                deliveries += channel to body
            }
            else -> log.warn("Tipo de quadro desconhecido ${frame.type}")
        }
    }

    private fun onAckLocked(seq: Int) {
        if (outstanding.none { it.seq == seq }) return
        while (true) {
            val item = outstanding.removeFirst()
            item.acked.complete(Unit)
            window.release()
            if (item.seq == seq) break
        }
        timeoutsWithoutAck = 0
        nackResentFor = null
        timeoutJob?.cancel()
        timeoutJob = null
        if (outstanding.isNotEmpty()) armTimeoutLocked()
    }

    private fun onNackLocked(expected: Int) {
        onAckLocked((expected - 1) and 0xff)
        val first = outstanding.firstOrNull() ?: return
        if (first.seq != expected) {
            log.warn("NACK para o quadro $expected, que não está pendente")
            return
        }
        if (nackResentFor == expected) {
            log.debug("NACK repetido para $expected: reenvio já feito, aguardando")
            return
        }
        nackResentFor = expected
        first.nacks++
        if (first.nacks > config.maxNacksPerFrame) {
            failLocked(NackException("A banda recusou o quadro $expected ${first.nacks} vezes (NACK)"))
            return
        }
        resendLocked("NACK")
    }

    private fun resendLocked(reason: String) {
        log.warn("Reenviando ${outstanding.size} quadro(s) a partir do ${outstanding.firstOrNull()?.seq} ($reason)")
        for (item in outstanding) writeQueue.trySend(Outgoing(item.frame))
        armTimeoutLocked(force = true)
    }

    private fun armTimeoutLocked(force: Boolean = false) {
        if (timeoutJob?.isActive == true && !force) return
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(config.resendTimeoutMs)
            synchronized(lock) { onTimeoutLocked() }
        }
    }

    private fun onTimeoutLocked() {
        timeoutJob = null
        if (outstanding.isEmpty() || mode == Mode.CLOSED) return
        timeoutsWithoutAck++
        if (timeoutsWithoutAck >= config.maxTimeoutsWithoutAck) {
            failLocked(AckTimeoutException("Sem confirmação (ACK) da banda após $timeoutsWithoutAck tentativas"))
            return
        }
        nackResentFor = null
        resendLocked("timeout de ${config.resendTimeoutMs} ms")
    }

    // ------------------------------------------------------------------ encerramento ---

    private fun fail(cause: Throwable?) {
        synchronized(lock) { failLocked(cause) }
    }

    private fun failLocked(cause: Throwable?) {
        if (mode == Mode.CLOSED) return
        mode = Mode.CLOSED
        val error = cause as? Exception ?: LinkClosedException(cause?.message ?: "Conexão encerrada", cause)
        // Tempos para diagnóstico: há quanto tempo a conexão vivia e quando foi o último pacote.
        val now = System.currentTimeMillis()
        fun ago(t: Long) = if (t == 0L) "nunca" else "há ${(now - t) / 1000.0} s".replace('.', ',')
        val timing = "conexão durou ${(now - createdAt) / 1000} s; último RX ${ago(lastRxAt)}, último TX ${ago(lastTxAt)}"
        if (cause != null) log.error("Enlace encerrado ($timing)", cause) else log.info("Enlace encerrado ($timing)")
        versionReply?.completeExceptionally(error)
        sessionReply?.completeExceptionally(error)
        for (item in outstanding) {
            item.acked.completeExceptionally(error)
            // Devolve a vaga da janela: quem estiver esperando acorda, vê CLOSED e desiste.
            window.release()
        }
        outstanding.clear()
        timeoutJob?.cancel()
        // V1: quem esperava a escrita de um pacote ainda na fila também precisa saber da queda.
        while (true) {
            val item = writeQueue.tryReceive().getOrNull() ?: break
            item.written?.completeExceptionally(error)
        }
        writeQueue.close()
        val l = listener
        // Fecha o transporte e avisa fora da thread atual (que pode ser a do próprio transporte).
        scope.launch {
            transport.close()
            l?.onClosed(cause)
            scope.cancel()
        }
    }
}
