package io.github.rafaeldatsch4afk.mibandfaces.log

import android.util.Log
import io.github.rafaeldatsch4afk.mibandfaces.util.toHexPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log em memória do app, exibido na aba "Logs". Registra, com detalhes, os pacotes enviados (TX)
 * e recebidos (RX) em hexadecimal, para depurar a comunicação com a banda real.
 *
 * Nunca registre aqui a chave de autenticação nem as chaves de sessão derivadas dela.
 */
class PacketLog(private val maxEntries: Int = 3000) {

    enum class Level { DEBUG, INFO, WARN, ERROR, TX, RX }

    data class Entry(val timeMillis: Long, val level: Level, val message: String) {
        fun format(): String = "${TIME_FORMAT.get()!!.format(Date(timeMillis))} ${level.name.padEnd(5)} $message"
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun debug(message: String) = add(Level.DEBUG, message)
    fun info(message: String) = add(Level.INFO, message)
    fun warn(message: String) = add(Level.WARN, message)
    fun error(message: String, error: Throwable? = null) =
        add(Level.ERROR, if (error != null) "$message: ${error.message ?: error.javaClass.simpleName}" else message)

    /** Bytes enviados para a banda. */
    fun tx(label: String, bytes: ByteArray) = add(Level.TX, "$label [${bytes.size}] ${bytes.toHexPreview()}")

    /** Bytes recebidos da banda. */
    fun rx(label: String, bytes: ByteArray) = add(Level.RX, "$label [${bytes.size}] ${bytes.toHexPreview()}")

    fun clear() = _entries.update { emptyList() }

    fun export(): String = _entries.value.joinToString("\n") { it.format() }

    private fun add(level: Level, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, message)
        try {
            when (level) {
                Level.ERROR -> Log.e(TAG, message)
                Level.WARN -> Log.w(TAG, message)
                else -> Log.d(TAG, "${level.name} $message")
            }
        } catch (_: RuntimeException) {
            // android.util.Log não existe nos testes unitários da JVM.
        }
        _entries.update { old ->
            val next = if (old.size >= maxEntries) old.drop(old.size - maxEntries + 1) else old
            next + entry
        }
    }

    private companion object {
        const val TAG = "MiBandFaces"
        val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
        }
    }
}
