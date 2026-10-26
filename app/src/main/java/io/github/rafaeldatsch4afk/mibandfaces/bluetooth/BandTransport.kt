package io.github.rafaeldatsch4afk.mibandfaces.bluetooth

/**
 * Um "cano" de bytes até a banda. A camada de protocolo (SPP V1/V2) não sabe se por baixo há um
 * socket RFCOMM (Bluetooth Clássico) ou um par de características GATT (BLE).
 */
interface BandTransport {
    /** Texto curto para logs e para a tela de status. */
    val description: String

    /** Se a banda precisa da sondagem de versão SPP V1 antes do V2 (só no Bluetooth Clássico). */
    val needsVersionProbe: Boolean

    interface Listener {
        /** Bytes recebidos da banda. Pode ser chamado de qualquer thread. */
        fun onData(bytes: ByteArray)

        /** A conexão caiu ou foi fechada. [cause] é null quando fechada por nós. */
        fun onClosed(cause: Throwable?)
    }

    /** Abre a conexão. Lança exceção se não conseguir. */
    suspend fun open(listener: Listener)

    /** Escreve bytes na banda, na ordem em que foram pedidos. */
    suspend fun write(bytes: ByteArray)

    fun close()
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
