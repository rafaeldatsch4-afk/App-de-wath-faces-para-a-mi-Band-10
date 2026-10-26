package io.github.rafaeldatsch4afk.mibandfaces.key

import io.github.rafaeldatsch4afk.mibandfaces.util.hexToBytes

/** Validação da auth key digitada ou importada: 32 caracteres hexadecimais (16 bytes). */
object AuthKey {
    private val HEX32 = Regex("^[0-9a-f]{32}$")

    /**
     * Normaliza o texto: tira espaços e quebras de linha, aceita o prefixo "0x" (como no
     * Gadgetbridge) e passa para minúsculas. Retorna a chave ou uma mensagem de erro.
     */
    fun normalize(input: String): Result<String> {
        var text = input.filterNot { it.isWhitespace() }.lowercase()
        if (text.startsWith("0x")) text = text.substring(2)
        return when {
            text.isEmpty() -> Result.failure(IllegalArgumentException("Digite a chave"))
            text.any { it !in "0123456789abcdef" } -> Result.failure(IllegalArgumentException("A chave só pode ter 0-9 e a-f"))
            text.length != 32 -> Result.failure(IllegalArgumentException("A chave precisa ter 32 caracteres (tem ${text.length})"))
            !HEX32.matches(text) -> Result.failure(IllegalArgumentException("Chave inválida"))
            else -> Result.success(text)
        }
    }

    fun toBytes(normalized: String): ByteArray = normalized.hexToBytes()

    /** Mostra só o começo e o fim, para conferir sem expor a chave inteira na tela. */
    fun mask(normalized: String): String = normalized.take(4) + "…" + normalized.takeLast(4)
}
