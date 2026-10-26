package io.github.rafaeldatsch4afk.mibandfaces.key

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Uma chave candidata encontrada num arquivo importado. */
data class KeyCandidate(
    val key: String,
    /** De onde veio, para o usuário decidir (ex.: "registerList_de", "authKey em XiaomiFit.device.log"). */
    val source: String,
    /** Veio de um campo conhecido (encryptKey, authKey…) e não só de um texto hex solto. */
    val recommended: Boolean,
)

/** Linha de banco SQLite que menciona registerList_<região>: o nome e os valores (texto/blob) da linha. */
data class SqliteRow(val label: String, val values: List<ByteArray>)

/** Leitura de um banco SQLite (implementada com android.database.sqlite; trocável nos testes). */
fun interface SqliteRowSource {
    /** Retorna as linhas com registerList*, ou lança se o arquivo não puder ser aberto. */
    fun registerListRows(database: ByteArray, wal: ByteArray?): List<SqliteRow>
}

/**
 * Procura a auth key em arquivos exportados do Mi Fitness, sem assumir uma região:
 *
 * - **Logs (Android):** campos `encryptKey`, `token`, `authKey` e `huamiAuthKey` seguidos de 32
 *   caracteres hex (documentação de pareamento do Gadgetbridge). A última ocorrência vem primeiro.
 * - **Pasta do Mi Fitness (iPhone/iPad), compactada em ZIP:** `manifest.sqlite` (+ `-wal`) com
 *   linhas `registerList_<região>` (de, sg, ru, us, …), cada uma com uma plist que tem
 *   `encryptKey` (layout documentado no miband-hack, src/core/mifitness-files.ts). Todas as
 *   linhas registerList_* são lidas.
 * - Qualquer outro arquivo onde apareça `registerList_<região>`: os textos hex de 32 caracteres
 *   logo depois de cada ocorrência.
 *
 * O formato exato desses arquivos não está no código do Gadgetbridge, por isso o importador
 * devolve candidatas com a origem, e quem escolhe é o usuário.
 */
class AuthKeyImporter(private val sqlite: SqliteRowSource?) {

    fun import(fileName: String, bytes: ByteArray): List<KeyCandidate> {
        val found = mutableListOf<KeyCandidate>()
        if (isZip(bytes)) {
            val entries = unzip(bytes)
            val databases = entries.keys.filter { it.endsWith(".sqlite") || it.endsWith(".db") }
            for (db in databases) {
                found += fromSqlite(db.substringAfterLast('/'), entries.getValue(db), entries["$db-wal"], entries)
            }
            for ((name, data) in entries) {
                if (name in databases) continue
                found += scan(name.substringAfterLast('/'), data)
            }
        } else if (isSqlite(bytes)) {
            found += fromSqlite(fileName, bytes, null, emptyMap())
        } else {
            found += scan(fileName, bytes)
        }
        // Remove repetidas, mantendo a primeira (a de maior prioridade).
        return found
            .sortedByDescending { it.recommended }
            .distinctBy { it.key }
    }

    private fun fromSqlite(name: String, db: ByteArray, wal: ByteArray?, zip: Map<String, ByteArray>): List<KeyCandidate> {
        val rows = try {
            sqlite?.registerListRows(db, wal)
        } catch (e: Exception) {
            null
        } ?: return scan(name, db) + (wal?.let { scan("$name-wal", it) } ?: emptyList())

        val out = mutableListOf<KeyCandidate>()
        for (row in rows) {
            for (value in row.values) {
                out += hexRuns(value).map { KeyCandidate(it, "${row.label} ($name)", recommended = true) }
                // O YYCache guarda valores grandes fora do banco, num arquivo com o nome da coluna "filename".
                val text = String(value, Charsets.ISO_8859_1)
                val looksLikeFileName = text.length in 8..200 && text.all { it.isLetterOrDigit() || it in "._-" }
                if (!looksLikeFileName) continue
                zip.entries.firstOrNull { (path, _) -> path.endsWith("/$text") }?.let { (_, data) ->
                    out += hexRuns(data).map { KeyCandidate(it, "${row.label} ($text)", recommended = true) }
                }
            }
        }
        // O WAL pode ter versões mais novas das linhas que o banco ainda não incorporou.
        if (wal != null) out += scan("$name-wal", wal)
        return out
    }

    /** Varredura de texto/binário: campos conhecidos e trechos depois de registerList_<região>. */
    fun scan(name: String, bytes: ByteArray): List<KeyCandidate> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val fields = FIELD_REGEX.findAll(text).toList().asReversed().map {
            KeyCandidate(it.groupValues[2].lowercase(), "${it.groupValues[1]} em $name", recommended = true)
        }
        // Cada trecho vai do fim de um registerList_<região> até o próximo (ou até REGISTER_WINDOW).
        val matches = REGISTER_LIST_REGEX.findAll(text).toList()
        val registerLists = matches.flatMapIndexed { i, match ->
            val start = match.range.last + 1
            val end = minOf(text.length, start + REGISTER_WINDOW, matches.getOrNull(i + 1)?.range?.first ?: Int.MAX_VALUE)
            HEX32_REGEX.findAll(text.substring(start, end))
                .map { KeyCandidate(it.value.lowercase(), "${match.value} em $name", recommended = false) }
                .toList()
        }
        return fields + registerLists
    }

    private fun hexRuns(value: ByteArray): List<String> =
        HEX32_REGEX.findAll(String(value, Charsets.ISO_8859_1)).map { it.value.lowercase() }.toList()

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || "__MACOSX/" in entry.name) continue
                val data = zip.readBytes()
                total += data.size
                if (total > MAX_UNZIPPED) throw IllegalArgumentException("ZIP grande demais")
                out[entry.name] = data
            }
        }
        return out
    }

    companion object {
        const val MAX_FILE_SIZE = 64 * 1024 * 1024
        private const val MAX_UNZIPPED = 256L * 1024 * 1024
        private const val REGISTER_WINDOW = 16 * 1024

        /** Mesmos campos do comando grep da documentação do Gadgetbridge (e variantes com "_"). */
        private val FIELD_REGEX = Regex(
            "(encryptKey|encrypt_key|huamiAuthKey|authKey|auth_key|token)[\"':= ]+(?:0x)?([0-9a-fA-F]{32})(?![0-9a-fA-F])",
        )

        /** Qualquer região: registerList_de, registerList_sg, registerList_ru… */
        private val REGISTER_LIST_REGEX = Regex("registerList_[A-Za-z]{2,10}")
        private val HEX32_REGEX = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{32}(?![0-9a-fA-F])")

        fun isZip(b: ByteArray) = b.size > 4 && b[0] == 0x50.toByte() && b[1] == 0x4b.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()

        fun isSqlite(b: ByteArray) = b.size >= 16 && String(b, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
    }
}
