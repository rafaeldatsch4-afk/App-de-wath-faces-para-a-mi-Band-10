package io.github.rafaeldatsch4afk.mibandfaces.miniapp

import io.github.rafaeldatsch4afk.mibandfaces.band.BandModel
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Mini-app da banda (Xiaomi Vela / QuickApp), arquivo `.rpk`: um ZIP com `manifest.json`, `app.js`,
 * as páginas compiladas em JavaScript e as imagens.
 *
 * O Gadgetbridge (XiaomiFWHelper.parseAsRpk) só lê o `manifest.json` (package, name, versionName,
 * versionCode). Aqui também conferimos que o ZIP está inteiro (CRC de cada arquivo), que não há
 * nomes estranhos e que os arquivos que o manifest cita existem, antes de liberar o envio.
 */
sealed interface RpkFile {
    val bytes: ByteArray

    class Valid(
        override val bytes: ByteArray,
        /** Identificador do app na banda (campo `package` do manifest). */
        val packageName: String,
        val name: String,
        val versionName: String,
        val versionCode: Int,
        /** Largura de tela para a qual o app foi feito (`config.designWidth`), se informada. */
        val designWidth: Int?,
        val features: List<String>,
        val fileCount: Int,
        val unpackedSize: Long,
        /** PNG do ícone, se o manifest apontar para um arquivo que existe. */
        val icon: ByteArray?,
        /** Avisos que não impedem a instalação, mas que o usuário deve ver. */
        val warnings: List<String>,
    ) : RpkFile

    class Rejected(override val bytes: ByteArray, val reason: String) : RpkFile

    companion object {
        /** Os .rpk costumam ter de dezenas de KB a poucos MB. */
        const val MAX_SIZE = 16 * 1024 * 1024

        /** Limite do conteúdo descompactado, contra ZIPs que "explodem" ao abrir. */
        private const val MAX_UNPACKED = 64L * 1024 * 1024
        private const val MAX_FILES = 2_000

        /** Nem todo app usa o formato "com.exemplo.app": há pacotes reais como "Fruits_cut". */
        private val PACKAGE_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")

        /** Um .rpk é um ZIP: começa com "PK\u0003\u0004". */
        fun looksLikeRpk(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
                bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

        /** Lê e valida [bytes] como um mini-app para [model]. */
        fun parse(bytes: ByteArray, model: BandModel = BandModel.BAND_10): RpkFile {
            if (!model.supportsApps) {
                return Rejected(bytes, "A ${model.label} não aceita mini-apps (.rpk): só watch faces. Ela não respondeu a nenhum comando de app nos testes do miband-hack.")
            }
            if (bytes.size > MAX_SIZE) return Rejected(bytes, "Arquivo grande demais (${bytes.size / 1024} KB) para um mini-app")
            if (!looksLikeRpk(bytes)) return Rejected(bytes, "Não é um mini-app .rpk (não é um arquivo ZIP)")

            val files = LinkedHashMap<String, ByteArray>()
            var unpacked = 0L
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        if (name.startsWith("/") || name.contains('\\') || name.split('/').any { it == ".." }) {
                            return Rejected(bytes, "O pacote tem um caminho inválido: $name")
                        }
                        if (entry.isDirectory) continue
                        // Ler o arquivo inteiro faz o ZipInputStream conferir o CRC32 dele.
                        val content = zip.readBytes()
                        unpacked += content.size
                        if (unpacked > MAX_UNPACKED) return Rejected(bytes, "O conteúdo descompactado passa de ${MAX_UNPACKED / 1024 / 1024} MB")
                        if (files.size >= MAX_FILES) return Rejected(bytes, "O pacote tem arquivos demais")
                        if (files.put(name, content) != null) return Rejected(bytes, "O pacote tem o arquivo $name repetido")
                    }
                }
            } catch (e: ZipException) {
                return Rejected(bytes, "O arquivo ZIP está corrompido: ${e.message}")
            } catch (e: java.io.IOException) {
                return Rejected(bytes, "Não consegui ler o arquivo ZIP: ${e.message}")
            } catch (e: IllegalArgumentException) {
                // Nome de arquivo com bytes inválidos dentro do ZIP.
                return Rejected(bytes, "O arquivo ZIP está corrompido: ${e.message}")
            }

            val manifestBytes = files["manifest.json"] ?: return Rejected(bytes, "Não tem manifest.json: não é um mini-app")
            val manifest = try {
                JSONObject(String(manifestBytes, Charsets.UTF_8))
            } catch (e: Exception) {
                return Rejected(bytes, "O manifest.json não é um JSON válido")
            }
            val packageName = manifest.optString("package")
            if (packageName.isBlank()) return Rejected(bytes, "O manifest.json não tem o campo \"package\"")
            if (!PACKAGE_REGEX.matches(packageName) || packageName.length > 128) {
                return Rejected(bytes, "Nome de pacote inválido no manifest.json: $packageName")
            }
            val name = manifest.optString("name").ifBlank { packageName }
            val versionName = manifest.optString("versionName").ifBlank { "?" }
            // Mesmo padrão do Gadgetbridge quando falta o versionCode.
            val versionCode = manifest.optInt("versionCode", 1)

            val warnings = mutableListOf<String>()
            val deviceTypes = manifest.optJSONArray("deviceTypeList")?.let { a -> List(a.length()) { a.optString(it) } }
            if (deviceTypes != null && "watch" !in deviceTypes) {
                warnings += "O manifest não lista \"watch\" entre os aparelhos (${deviceTypes.joinToString()}): pode não ser para a banda."
            }
            val designWidth = manifest.optJSONObject("config")?.takeIf { it.has("designWidth") }?.optInt("designWidth")
            if (designWidth != null && designWidth != model.screenWidth) {
                warnings += "Feito para uma tela de $designWidth px de largura; a ${model.label} tem ${model.screenWidth} px. " +
                    "O app pode aparecer fora de proporção."
            }
            if ("app.js" !in files) warnings += "Não tem app.js: o app pode não abrir."
            entryPageFile(manifest)?.let { entry ->
                if (entry !in files) warnings += "A página inicial ($entry) não está no pacote: o app pode não abrir."
            }
            if (files.keys.none { it.startsWith("META-INF/") }) warnings += "O pacote não é assinado (sem META-INF)."

            val features = manifest.optJSONArray("features")?.let { a ->
                List(a.length()) { a.optJSONObject(it)?.optString("name").orEmpty() }.filter { it.isNotBlank() }
            }.orEmpty()
            val icon = manifest.optString("icon").trimStart('/').takeIf { it.isNotBlank() }?.let { files[it] }

            return Valid(
                bytes = bytes,
                packageName = packageName,
                name = name,
                versionName = versionName,
                versionCode = versionCode,
                designWidth = designWidth,
                features = features,
                fileCount = files.size,
                unpackedSize = unpacked,
                icon = icon,
                warnings = warnings,
            )
        }

        /** `router.entry` + componente da página, ex.: "pages/index" → "pages/index/index.js". */
        private fun entryPageFile(manifest: JSONObject): String? {
            val router = manifest.optJSONObject("router") ?: return null
            val entry = router.optString("entry").trim('/').ifBlank { return null }
            val component = router.optJSONObject("pages")?.optJSONObject(entry)?.optString("component")?.ifBlank { null } ?: "index"
            return "$entry/$component.js"
        }
    }
}
