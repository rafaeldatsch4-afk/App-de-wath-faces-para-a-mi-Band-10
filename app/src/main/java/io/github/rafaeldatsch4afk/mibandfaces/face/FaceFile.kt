package io.github.rafaeldatsch4afk.mibandfaces.face

import io.github.rafaeldatsch4afk.mibandfaces.band.BandModel
import io.github.rafaeldatsch4afk.mibandfaces.util.cString
import io.github.rafaeldatsch4afk.mibandfaces.util.readU16le
import io.github.rafaeldatsch4afk.mibandfaces.util.readU32le
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compatibilidade do preview com a tela do modelo escolhido (regra de XiaomiInstallHandler). */
enum class Compatibility {
    /** Preview do tamanho da tela. */
    EXACT,

    /** Preview reduzido de um tamanho já visto em faces para o modelo. */
    KNOWN_SIZE,
    SAME_ASPECT,
    UNKNOWN,
    INCOMPATIBLE,
    ;

    fun describe(model: BandModel): String = when (this) {
        EXACT -> "O preview tem exatamente ${model.screenText}, o tamanho da tela da ${model.label}."
        KNOWN_SIZE -> "O preview tem um tamanho reduzido já visto em faces para a ${model.label}."
        SAME_ASPECT -> "O preview é menor, mas tem a mesma proporção da tela da ${model.label}: provavelmente compatível."
        UNKNOWN -> "O arquivo não tem preview: não dá para conferir se é para a ${model.label}."
        INCOMPATIBLE -> "A resolução do preview não bate com a tela da ${model.label} (${model.screenText}): a face é de outro modelo."
    }
}

/**
 * Leitura e validação básica de um arquivo de watch face (.face / .bin). Portado de
 * XiaomiFWHelper (Gadgetbridge) e de src/core/files.ts (miband-hack).
 */
sealed interface FaceFile {
    val bytes: ByteArray

    /** Watch face v2 (cabeçalho 5A A5): a única que este app instala. */
    class V2(
        override val bytes: ByteArray,
        val id: String,
        val name: String,
        val preview: PreviewHeader?,
        val faceCount: Int,
        /** Modelo para o qual o arquivo foi validado. */
        val model: BandModel,
        val compatibility: Compatibility,
    ) : FaceFile {
        val canInstall: Boolean get() = compatibility != Compatibility.INCOMPATIBLE
        val compatibilityText: String get() = compatibility.describe(model)
    }

    /** Arquivo que não pode ser instalado, com o motivo. */
    class Rejected(override val bytes: ByteArray, val reason: String, val name: String? = null) : FaceFile

    /** Cabeçalho da imagem de preview: formato, compressão, tamanho e onde estão os dados. */
    data class PreviewHeader(
        val offset: Int,
        val pixelFormat: Int,
        val compression: Int,
        val width: Int,
        val height: Int,
        val dataSize: Int,
    )

    companion object {
        /** Limite de sanidade. Faces de 212×520 costumam ter bem menos de 2 MB. */
        const val MAX_SIZE = 16 * 1024 * 1024
        private const val MIN_V2_SIZE = 0xa8

        private val V3_HEADER = byteArrayOf(0x00, 0x00, 0x06, 0x07, 0x00, 0x02, 0x00, 0x01)

        /** Ordem dos idiomas na tabela de nomes traduzidos (FACE_LOCALE_BITMAP_LIST no Gadgetbridge). */
        private val LOCALES = listOf(
            "en_US", "zh_CN", "zh_TW", "ja_JP", "es_ES", "fr_FR", "de_DE", "ru_RU", "pt_BR", "pt_PT",
            "it_IT", "ko_KR", "tr_TR", "nl_NL", "th_TH", "sv_SE", "da_DK", "vi_VN", "nb_NO", "pl_PL",
            "fi_FI", "in_ID", "el_GR", "ro_RO", "cs_CZ", "uk_UA", "hu_HU", "sk_SK", "ar_EG", "iw_IL", "zh_HK",
        )

        /** Lê e valida [bytes] como uma face para [model]. */
        fun parse(bytes: ByteArray, model: BandModel = BandModel.BAND_10): FaceFile {
            if (bytes.isEmpty()) return Rejected(bytes, "Arquivo vazio")
            if (bytes.size > MAX_SIZE) return Rejected(bytes, "Arquivo grande demais (${bytes.size / 1024} KB) para uma watch face")
            if (bytes.size >= 2 && bytes[0] == 0x5a.toByte() && bytes[1] == 0xa5.toByte()) return parseV2(bytes, model)
            if (bytes.size >= 100 && V3_HEADER.indices.all { bytes[it] == V3_HEADER[it] }) {
                val nameLength = bytes[20].toInt() and 0xff
                val name = if (21 + nameLength <= bytes.size) String(bytes, 21, nameLength, Charsets.UTF_8) else null
                return Rejected(
                    bytes,
                    "Watch face no formato v3: o Gadgetbridge não consegue ler o ID desse formato e não a instala; este app também não.",
                    name,
                )
            }
            if (bytes.size > 4 && bytes[0].toInt() == 96 && bytes[1].toInt() == 90 && bytes[2].toInt() == 90 && bytes[4].toInt() == 126) {
                return Rejected(bytes, "Isto parece um FIRMWARE, não uma watch face. Este app não instala firmware.")
            }
            if (bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
                return Rejected(bytes, "Isto é um arquivo ZIP (talvez um app .rpk), não uma watch face.")
            }
            return Rejected(bytes, "Formato desconhecido: não começa com o cabeçalho de watch face (5A A5).")
        }

        private fun parseV2(fw: ByteArray, model: BandModel): FaceFile {
            if (fw.size < MIN_V2_SIZE) return Rejected(fw, "Arquivo curto demais para uma watch face v2")
            val id = fw.cString(0x28, 64)
            if (id.isNullOrEmpty() || !id.all { it in '0'..'9' }) {
                return Rejected(fw, "ID da watch face ausente ou não numérico (offset 0x28)")
            }
            val name = readName(fw) ?: "Watch face $id"
            FaceStructureCheck.check(fw)?.let { problem ->
                return Rejected(fw, "Estrutura interna inválida: $problem. Enviar este arquivo poderia travar a banda.", name)
            }
            val faceCount = FaceStructureCheck.faceCount(fw)
            if (model.requiresAodFace && faceCount != 2) {
                // miband-hack (device.ts): uma Mi Band 8 deixou de ligar com uma face sem a tela AOD.
                return Rejected(
                    fw,
                    "Uma face para a ${model.label} precisa ter a tela normal e a AOD (sempre ligada); este arquivo tem " +
                        "$faceCount tela(s). Uma face assim já impediu uma Mi Band 8 de ligar.",
                    name,
                )
            }
            val preview = readPreviewHeader(fw)
            return V2(
                bytes = fw,
                id = id,
                name = name,
                preview = preview,
                faceCount = faceCount,
                model = model,
                compatibility = compatibility(preview, model),
            )
        }

        private fun readName(fw: ByteArray): String? {
            val localized = (0x68 until 0x6c).all { fw[it] == 0xff.toByte() }
            return if (localized) {
                val tableOffset = fw.readU32le(0x74)
                val tableSize = fw.readU32le(0x78)
                if (tableSize > 8) localizedName(fw, tableOffset, tableSize) else null
            } else {
                fw.cString(0x68, 64)?.takeIf { it.isNotBlank() }
            }
        }

        /** XiaomiFWHelper.extractTranslatableString, preferindo português, depois inglês. */
        private fun localizedName(fw: ByteArray, offset: Long, size: Long): String? {
            if (offset < 0 || offset + size > fw.size || size < 8) return null
            val bb = ByteBuffer.wrap(fw, offset.toInt(), size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            val mask = bb.long
            val present = (0 until 64).filter { mask and (1L shl it) != 0L }
            if (size < 8 + 4L * present.size) return null
            val sizes = present.map { bb.int }
            val preferred = listOf("pt_BR", "pt_PT", "en_US").map { LOCALES.indexOf(it) }
            val target = preferred.firstOrNull { it in present } ?: present.firstOrNull() ?: return null
            val index = present.indexOf(target)
            val start = sizes.take(index).sum()
            val length = sizes[index]
            if (start < 0 || length < 0 || bb.remaining() < start + length) return null
            bb.position(bb.position() + start)
            val bytes = ByteArray(length).also { bb.get(it) }
            return String(bytes, Charsets.UTF_8).trimEnd('\u0000').takeIf { it.isNotBlank() }
        }

        private fun readPreviewHeader(fw: ByteArray): PreviewHeader? {
            if (fw.size < 0x24) return null
            val offset = fw.readU32le(0x20)
            if (offset <= 0 || offset + 12 > fw.size) return null
            val o = offset.toInt()
            return PreviewHeader(
                offset = o,
                pixelFormat = fw[o].toInt() and 0xff,
                compression = fw[o + 1].toInt() and 0xff,
                width = fw.readU16le(o + 4),
                height = fw.readU16le(o + 6),
                dataSize = fw.readU32le(o + 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }

        /** Mesma regra de XiaomiInstallHandler.validateInstallation / sameAspectRatio. */
        fun compatibility(preview: PreviewHeader?, model: BandModel = BandModel.BAND_10): Compatibility {
            if (preview == null || preview.width == 0 || preview.height == 0) return Compatibility.UNKNOWN
            if (preview.width == model.screenWidth && preview.height == model.screenHeight) return Compatibility.EXACT
            if ((preview.width to preview.height) in model.knownPreviewSizes) return Compatibility.KNOWN_SIZE
            if (preview.width > model.screenWidth || preview.height > model.screenHeight) return Compatibility.INCOMPATIBLE
            val previewRatio = preview.width.toDouble() / preview.height
            val screenRatio = model.screenWidth.toDouble() / model.screenHeight
            return if (kotlin.math.abs(previewRatio / screenRatio - 1) <= 0.02) Compatibility.SAME_ASPECT else Compatibility.INCOMPATIBLE
        }
    }
}
