package io.github.rafaeldatsch4afk.mibandfaces.protocol

import kotlinx.coroutines.Deferred

/** A banda (ou o protocolo) não oferece o que foi pedido. Não é queda: não adianta reconectar. */
class LinkUnsupportedException(message: String) : Exception(message)

/**
 * Enlace já negociado com a banda, independente do protocolo por baixo: SPP V1/V2 ou BLE V2
 * ([XiaomiLink]) ou BLE V1 da Mi Band 8 ([BleV1Link]). Os canais são os lógicos do SPP V2:
 * [SppV2.CHANNEL_PROTOBUF] (comandos) e [SppV2.CHANNEL_DATA] (envio de arquivos).
 */
interface BandLink {
    interface Listener {
        /** Pacote recebido, já decifrado se necessário. */
        fun onPacket(channel: Int, body: ByteArray)

        /** O enlace caiu. [cause] é null quando fechado por nós. */
        fun onClosed(cause: Throwable?)
    }

    /** Texto curto para logs e para a tela de status. */
    val description: String

    val isOpen: Boolean

    /** Quantos pacotes podem estar a caminho ao mesmo tempo (para o log do envio). */
    val effectiveWindow: Int

    /** Abre e negocia o enlace. Lança exceção se não conseguir. */
    suspend fun open(listener: Listener)

    /** Envia e espera a confirmação do enlace (ACK, ou a escrita, onde não há ACK). */
    suspend fun send(channel: Int, body: ByteArray, encrypt: Boolean) {
        enqueue(channel, body, encrypt).await()
    }

    /**
     * Enfileira um pacote. Pode suspender enquanto a janela de envio estiver cheia. O Deferred
     * completa quando o pacote for confirmado, ou falha se o enlace cair.
     */
    suspend fun enqueue(channel: Int, body: ByteArray, encrypt: Boolean): Deferred<Unit>

    /** Autenticação concluída: a partir daqui os pacotes cifrados usam estas chaves. */
    fun onAuthenticated(keys: SessionKeys)

    fun close()
}
