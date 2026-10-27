package io.github.rafaeldatsch4afk.mibandfaces.band

import io.github.rafaeldatsch4afk.mibandfaces.band.Commands.isA
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BandTransport
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.proto.Command
import io.github.rafaeldatsch4afk.mibandfaces.protocol.LinkConfig
import io.github.rafaeldatsch4afk.mibandfaces.protocol.LinkException
import io.github.rafaeldatsch4afk.mibandfaces.protocol.SppV2
import io.github.rafaeldatsch4afk.mibandfaces.protocol.SppV2Cipher
import io.github.rafaeldatsch4afk.mibandfaces.protocol.XiaomiCrypto
import io.github.rafaeldatsch4afk.mibandfaces.protocol.XiaomiLink
import io.github.rafaeldatsch4afk.mibandfaces.util.toHexPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList

/** Estado da conexão, mostrado na tela de conexão. */
sealed interface BandState {
    data object Disconnected : BandState
    data class Connecting(val step: String) : BandState

    /** Conectado e autenticado: pronto para comandos. */
    data class Ready(val transport: String) : BandState
    data class Failed(val message: String) : BandState
}

/** Informações da banda (comandos 2/2 e 2/1). */
data class BandInfo(
    val model: String? = null,
    val firmware: String? = null,
    val serial: String? = null,
    val batteryPercent: Int? = null,
)

class BandTimeoutException(message: String) : Exception(message)
class NotConnectedException : Exception("A banda não está conectada")

/**
 * Cliente de alto nível da banda: abre o enlace, autentica e troca comandos protobuf.
 * Os pedidos registram quem espera a resposta antes de enviar, para não perder respostas rápidas.
 */
class BandClient(
    private val log: PacketLog,
    private val companion: CompanionInfo,
    private val linkConfig: LinkConfig = LinkConfig(),
    private val requestTimeoutMs: Long = 10_000,
    /** Se o celular usa formato 24 h (a banda segue a mesma preferência). */
    private val is24HourFormat: () -> Boolean = { true },
    private val clock: () -> Calendar = XiaomiClock::now,
    /**
     * Medido com a Mi Band 10 real: ela fecha a conexão depois de ~60 s sem nenhum tráfego. Se a
     * conexão ficar este tempo parada, o app pede a bateria (comando inofensivo, o mesmo do
     * wakeLink do Gadgetbridge) para mantê-la viva. 0 desliga.
     */
    private val keepAliveIdleMs: Long = 20_000,
    /**
     * Esperas antes de cada tentativa de reconexão automática depois de uma queda. A banda real
     * fechou o RFCOMM de vez em quando (~50–60 s depois de conectar), então o app reconecta
     * sozinho, como o Gadgetbridge. Depois de uma reconexão bem-sucedida a contagem recomeça.
     */
    private val reconnectDelaysMs: List<Long> = listOf(1_000, 3_000, 6_000),
) {
    /** O que é preciso para reconectar sozinho: como criar um transporte novo e a chave. */
    private class Session(val newTransport: () -> BandTransport, val authKey: ByteArray)

    private class Waiter(val match: (Command) -> Boolean, val reply: CompletableDeferred<Command>)

    private val _state = MutableStateFlow<BandState>(BandState.Disconnected)
    val state: StateFlow<BandState> = _state.asStateFlow()

    private val _info = MutableStateFlow(BandInfo())
    val info: StateFlow<BandInfo> = _info.asStateFlow()

    @Volatile
    private var link: XiaomiLink? = null
    private val waiters = CopyOnWriteArrayList<Waiter>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepAliveJob: Job? = null

    @Volatile
    private var session: Session? = null
    private var reconnectJob: Job? = null

    /** Aumenta a cada conexão autenticada; serve para saber se uma reconexão já aconteceu. */
    @Volatile
    var connectionGeneration = 0
        private set

    /** Último pacote enviado ou recebido (para o keep-alive). */
    @Volatile
    private var lastActivityAt = 0L

    /** Chamado para cada comando recebido que nenhum pedido estava esperando. */
    var onUnsolicited: (Command) -> Unit = {}

    val isReady: Boolean get() = _state.value is BandState.Ready

    /** Conecta uma vez (sem reconexão automática), negocia o SPP V2 e autentica com a [authKey]. */
    suspend fun connect(transport: BandTransport, authKey: ByteArray) =
        connect({ transport }, authKey, autoReconnect = false)

    /**
     * Conecta, negocia o SPP V2 e autentica com a [authKey] de 16 bytes. Com [autoReconnect], se a
     * conexão cair sem o usuário pedir, o cliente cria outro transporte com [newTransport] e
     * reconecta sozinho, até [disconnect] ser chamado.
     */
    suspend fun connect(newTransport: () -> BandTransport, authKey: ByteArray, autoReconnect: Boolean) {
        require(authKey.size == 16) { "a auth key deve ter 16 bytes" }
        disconnect()
        session = if (autoReconnect) Session(newTransport, authKey.copyOf()) else null
        try {
            establish(newTransport(), authKey, announceFailure = true)
        } catch (e: Exception) {
            session = null
            throw e
        }
    }

    /**
     * Espera uma conexão autenticada mais nova que [afterGeneration] (isto é, uma reconexão).
     * Retorna false se o cliente desistir (sem reconexão automática, usuário desconectou ou as
     * tentativas acabaram) ou se passar [timeoutMs].
     */
    suspend fun awaitReconnection(afterGeneration: Int, timeoutMs: Long): Boolean {
        val state = withTimeoutOrNull(timeoutMs) {
            _state.first {
                (it is BandState.Ready && connectionGeneration > afterGeneration) ||
                    it is BandState.Failed || it is BandState.Disconnected
            }
        }
        return state is BandState.Ready
    }

    private suspend fun establish(transport: BandTransport, authKey: ByteArray, announceFailure: Boolean) {
        val newLink = XiaomiLink(transport, log, linkConfig)
        link = newLink
        try {
            _state.value = BandState.Connecting("Abrindo ${transport.description}…")
            newLink.open(LinkListener(newLink))
            _state.value = BandState.Connecting("Autenticando…")
            authenticate(newLink, authKey)
            // Como o Gadgetbridge (XiaomiSupport.onAuthSuccess): acerta a hora logo depois de autenticar.
            // Antes de anunciar Ready, para que nenhum outro comando passe na frente.
            syncTime()
            if (link !== newLink || !newLink.isOpen) throw NotConnectedException()
            connectionGeneration++
            _state.value = BandState.Ready(transport.description)
            log.info("Pronto: autenticado por ${transport.description}")
            startKeepAlive(newLink)
        } catch (e: Exception) {
            keepAliveJob?.cancel()
            if (link === newLink) link = null
            newLink.close()
            failAllWaiters(e)
            if (announceFailure) _state.value = BandState.Failed(e.message ?: e.javaClass.simpleName)
            throw e
        }
        refreshInfo()
    }

    /** Desconecta a pedido do usuário (ou do app) e desliga a reconexão automática. */
    fun disconnect() {
        session = null
        reconnectJob?.cancel()
        reconnectJob = null
        keepAliveJob?.cancel()
        if (_state.value is BandState.Connecting && link == null) _state.value = BandState.Disconnected
        val old = link ?: return
        link = null
        old.close()
        failAllWaiters(NotConnectedException())
        _state.value = BandState.Disconnected
    }

    /** Mantém a conexão viva enquanto ela for a atual: pede a bateria se ficar parada. */
    private fun startKeepAlive(owner: XiaomiLink) {
        keepAliveJob?.cancel()
        if (keepAliveIdleMs <= 0) return
        keepAliveJob = scope.launch {
            val checkEvery = (keepAliveIdleMs / 4).coerceAtLeast(50)
            while (link === owner && owner.isOpen) {
                delay(checkEvery)
                val idle = System.currentTimeMillis() - lastActivityAt
                if (idle < keepAliveIdleMs || link !== owner) continue
                log.debug("Conexão parada há ${idle / 1000} s: pedindo a bateria para mantê-la viva")
                try {
                    sendOn(owner, Commands.simple(Commands.SYSTEM, Commands.SYSTEM_BATTERY))
                } catch (e: Exception) {
                    if (!owner.isOpen) break
                    log.warn("Keep-alive falhou: ${e.message}")
                }
            }
        }
    }

    /** Reconecta depois de uma queda, com algumas tentativas; desiste com o estado Failed. */
    private fun startReconnect(s: Session, cause: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var lastError = cause
            for ((index, wait) in reconnectDelaysMs.withIndex()) {
                val attempt = index + 1
                _state.value = BandState.Connecting("A conexão caiu. Reconectando (tentativa $attempt de ${reconnectDelaysMs.size})…")
                delay(wait)
                if (session !== s) return@launch
                log.info("Reconexão automática: tentativa $attempt de ${reconnectDelaysMs.size}")
                try {
                    establish(s.newTransport(), s.authKey, announceFailure = false)
                    log.info("Reconectado")
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    log.warn("Reconexão $attempt falhou: $lastError")
                    if (e is AuthException) break
                }
            }
            if (session === s) {
                session = null
                _state.value = BandState.Failed("A conexão caiu e não consegui reconectar: $lastError")
            }
        }
    }

    private suspend fun authenticate(l: XiaomiLink, authKey: ByteArray) {
        val phoneNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val step1 = request(XiaomiAuth.buildNonceCommand(phoneNonce), "autenticação (passo 1)", l) {
            it.isA(XiaomiAuth.COMMAND_TYPE, XiaomiAuth.CMD_NONCE)
        }
        val account = step1.auth
        if (account.hasErrorCode() && account.errorCode != 0) {
            throw AuthException("A banda recusou a autenticação (código ${account.errorCode}): ${XiaomiAuth.describeAccountError(account.errorCode)}")
        }
        if (!account.hasDeviceVerify()) throw AuthException("Resposta inesperada da banda no passo 1 da autenticação")

        val bandNonce = account.deviceVerify.nonce.toByteArray()
        val keys = XiaomiCrypto.deriveSessionKeys(authKey, phoneNonce, bandNonce)
        if (!XiaomiAuth.bandHmacMatches(keys, phoneNonce, account.deviceVerify)) {
            throw AuthException("Chave de autenticação incorreta (o HMAC da banda não confere). Confira a auth key.")
        }
        log.info("HMAC da banda confere: a auth key está certa")

        val step2 = request(XiaomiAuth.buildConfirmCommand(keys, phoneNonce, bandNonce, companion), "autenticação (passo 2)", l) {
            it.isA(XiaomiAuth.COMMAND_TYPE, XiaomiAuth.CMD_AUTH)
        }
        if (step2.auth.hasErrorCode() && step2.auth.errorCode != 0) {
            throw AuthException("A banda recusou a confirmação (código ${step2.auth.errorCode}): ${XiaomiAuth.describeAccountError(step2.auth.errorCode)}")
        }
        l.cipher = SppV2Cipher(keys)
    }

    /**
     * Envia a hora, a data, o fuso e o formato 12/24 h do celular (comando 2/3). O Gadgetbridge não
     * espera resposta, só o ACK de transporte. Uma falha aqui é registrada, mas não derruba a conexão.
     */
    suspend fun syncTime() {
        try {
            val now = clock()
            send(XiaomiClock.buildSetTimeCommand(now, is24HourFormat()))
            log.info(
                "Hora acertada: %tF %<tT (%s, %s)".format(now, now.timeZone.id, if (is24HourFormat()) "24 h" else "12 h"),
            )
        } catch (e: NotConnectedException) {
            throw e
        } catch (e: LinkException) {
            // O enlace caiu ou parou de receber ACKs: isso sim é fatal para a conexão.
            throw e
        } catch (e: Exception) {
            log.warn("Não consegui acertar a hora da banda: ${e.message}")
        }
    }

    /** Pede modelo, firmware e bateria. Falhas aqui não derrubam a conexão. */
    suspend fun refreshInfo() {
        try {
            val info = request(Commands.simple(Commands.SYSTEM, Commands.SYSTEM_DEVICE_INFO), "informações da banda") {
                it.isA(Commands.SYSTEM, Commands.SYSTEM_DEVICE_INFO)
            }.system.deviceInfo
            _info.update { it.copy(model = info.model, firmware = info.firmware, serial = info.serialNumber) }
        } catch (e: Exception) {
            log.warn("Não consegui ler as informações da banda: ${e.message}")
        }
        try {
            val battery = request(Commands.simple(Commands.SYSTEM, Commands.SYSTEM_BATTERY), "bateria") {
                it.isA(Commands.SYSTEM, Commands.SYSTEM_BATTERY)
            }.system.power.battery
            _info.update { it.copy(batteryPercent = battery.level) }
        } catch (e: Exception) {
            log.warn("Não consegui ler a bateria: ${e.message}")
        }
    }

    /** Envia um comando sem esperar resposta (só o ACK de transporte). */
    suspend fun send(command: Command) {
        sendOn(currentLink(), command)
    }

    /** Envia um comando e espera a resposta que satisfaz [match]. */
    suspend fun request(
        command: Command,
        what: String,
        timeoutMs: Long = requestTimeoutMs,
        match: (Command) -> Boolean,
    ): Command = request(command, what, currentLink(), timeoutMs, match)

    private suspend fun request(
        command: Command,
        what: String,
        l: XiaomiLink,
        timeoutMs: Long = requestTimeoutMs,
        match: (Command) -> Boolean,
    ): Command {
        val waiter = Waiter(match, CompletableDeferred())
        waiters += waiter
        try {
            sendOn(l, command)
            return withTimeout(timeoutMs) { waiter.reply.await() }
        } catch (e: TimeoutCancellationException) {
            throw BandTimeoutException("A banda não respondeu: $what")
        } finally {
            waiters -= waiter
        }
    }

    /** Envia bytes crus no canal de dados (partes do upload). Retorna quando a banda der ACK. */
    internal suspend fun enqueueData(body: ByteArray): Deferred<Unit> {
        lastActivityAt = System.currentTimeMillis()
        return currentLink().enqueue(SppV2.CHANNEL_DATA, body, encrypt = false)
    }

    internal val linkWindow: Int get() = link?.effectiveWindow ?: 1

    private fun currentLink(): XiaomiLink = link ?: throw NotConnectedException()

    private suspend fun sendOn(l: XiaomiLink, command: Command) {
        // Autenticação (tipo 1) vai em texto puro; o resto vai cifrado depois da autenticação.
        val encrypt = command.type != XiaomiAuth.COMMAND_TYPE
        val bytes = command.toByteArray()
        log.info("→ comando ${command.type}/${command.subtype}${if (encrypt) " (cifrado)" else ""}: ${bytes.toHexPreview()}")
        lastActivityAt = System.currentTimeMillis()
        l.send(SppV2.CHANNEL_PROTOBUF, bytes, encrypt)
    }

    private fun onPacket(channel: Int, body: ByteArray) {
        lastActivityAt = System.currentTimeMillis()
        if (channel != SppV2.CHANNEL_PROTOBUF) {
            log.debug("Pacote ignorado no canal $channel (${body.size} bytes)")
            return
        }
        val command = try {
            Command.parseFrom(body)
        } catch (e: Exception) {
            log.error("Não consegui decodificar o protobuf: ${body.toHexPreview()}", e)
            return
        }
        log.info("← comando ${command.type}/${command.subtype}: ${body.toHexPreview()}")
        handlePassive(command)
        val waiter = waiters.firstOrNull { it.match(command) }
        if (waiter != null) {
            waiters -= waiter
            waiter.reply.complete(command)
        } else {
            onUnsolicited(command)
        }
    }

    /** Atualizações que a banda pode mandar a qualquer momento. */
    private fun handlePassive(command: Command) {
        if (command.isA(Commands.SYSTEM, Commands.SYSTEM_BATTERY) && command.system.power.hasBattery()) {
            _info.update { it.copy(batteryPercent = command.system.power.battery.level) }
        }
    }

    private fun failAllWaiters(error: Exception) {
        for (w in waiters) w.reply.completeExceptionally(error)
        waiters.clear()
    }

    /** Um listener por enlace, para que o fechamento de um enlace antigo não afete o atual. */
    private inner class LinkListener(private val owner: XiaomiLink) : XiaomiLink.Listener {
        override fun onPacket(channel: Int, body: ByteArray) {
            if (link === owner) this@BandClient.onPacket(channel, body)
        }

        override fun onClosed(cause: Throwable?) {
            if (link !== owner) return
            // Caiu no meio da conexão/autenticação: quem trata é o establish(), que vai falhar.
            val wasReady = _state.value is BandState.Ready
            link = null
            keepAliveJob?.cancel()
            val error = cause as? Exception ?: NotConnectedException()
            failAllWaiters(error)
            val s = session
            when {
                !wasReady -> Unit
                cause == null -> _state.value = BandState.Disconnected
                s != null -> startReconnect(s, cause.message ?: "Conexão perdida")
                else -> _state.value = BandState.Failed(cause.message ?: "Conexão perdida")
            }
        }
    }
}
