package io.github.rafaeldatsch4afk.mibandfaces.band

import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BandTransport
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.protocol.LinkConfig
import io.github.rafaeldatsch4afk.mibandfaces.protocol.XiaomiLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado da conexão, mostrado na tela de conexão. */
sealed interface BandState {
    data object Disconnected : BandState
    data class Connecting(val step: String) : BandState
    data class Connected(val transport: String) : BandState
    data class Failed(val message: String) : BandState
}

/**
 * Cliente de alto nível da banda. Nesta etapa: abre o transporte, negocia o SPP V2 e mantém o
 * estado da conexão.
 */
class BandClient(
    private val log: PacketLog,
    private val linkConfig: LinkConfig = LinkConfig(),
) {

    private val _state = MutableStateFlow<BandState>(BandState.Disconnected)
    val state: StateFlow<BandState> = _state.asStateFlow()

    @Volatile
    private var link: XiaomiLink? = null

    suspend fun connect(transport: BandTransport) {
        disconnect()
        val newLink = XiaomiLink(transport, log, linkConfig)
        link = newLink
        _state.value = BandState.Connecting("Abrindo ${transport.description}…")
        try {
            newLink.open(LinkListener(newLink))
            _state.value = BandState.Connected(transport.description)
            log.info("Conectado (${transport.description}), sessão SPP V2 aberta")
        } catch (e: Exception) {
            link = null
            newLink.close()
            _state.value = BandState.Failed(e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    fun disconnect() {
        val old = link ?: return
        link = null
        old.close()
        _state.value = BandState.Disconnected
    }

    private fun onPacket(channel: Int, body: ByteArray) {
        log.debug("Pacote no canal $channel (${body.size} bytes)")
    }

    /** Um listener por enlace, para que o fechamento de um enlace antigo não afete o atual. */
    private inner class LinkListener(private val owner: XiaomiLink) : XiaomiLink.Listener {
        override fun onPacket(channel: Int, body: ByteArray) {
            if (link === owner) this@BandClient.onPacket(channel, body)
        }

        override fun onClosed(cause: Throwable?) {
            if (link !== owner) return
            link = null
            _state.value = if (cause != null) BandState.Failed(cause.message ?: "Conexão perdida") else BandState.Disconnected
        }
    }
}
