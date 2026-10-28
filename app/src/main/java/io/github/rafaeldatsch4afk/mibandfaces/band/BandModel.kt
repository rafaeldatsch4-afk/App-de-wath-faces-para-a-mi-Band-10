package io.github.rafaeldatsch4afk.mibandfaces.band

/**
 * Modelos suportados e o que muda entre eles. Valores dos "coordinators" do Gadgetbridge
 * (MiBand8Coordinator, MiBand9Coordinator, MiBand10Coordinator) e das observações do miband-hack
 * com bandas reais. As versões Pro e Active têm outra tela e não são suportadas.
 */
enum class BandModel(
    val label: String,
    val screenWidth: Int,
    val screenHeight: Int,
    /** Tamanhos de preview reduzidos já vistos em faces para este modelo (além do tamanho da tela). */
    val knownPreviewSizes: List<Pair<Int, Int>>,
    /** Protocolo de conexão: a Mi Band 8 usa BLE "V1"; a 9 e a 10 usam Bluetooth Clássico (SPP). */
    val usesBleV1: Boolean,
    /**
     * Se a banda aceita mini-apps .rpk. A Mi Band 8 (firmware 2.3.14) não respondeu a nenhum
     * comando de app no miband-hack.
     */
    val supportsApps: Boolean,
    /**
     * Se toda face precisa ter a tela normal e a AOD. Uma Mi Band 8 deixou de ligar depois de uma
     * face sem a AOD (miband-hack, 2026-10-26); as duas faces boas conhecidas tinham as duas.
     */
    val requiresAodFace: Boolean,
) {
    BAND_8(
        label = "Mi Band 8",
        screenWidth = 192,
        screenHeight = 490,
        // "Scaled-down screen resolution, seen in an unofficial watchface" (Gadgetbridge)
        knownPreviewSizes = listOf(122 to 310),
        usesBleV1 = true,
        supportsApps = false,
        requiresAodFace = true,
    ),
    BAND_9(
        label = "Mi Band 9",
        screenWidth = 192,
        screenHeight = 490,
        knownPreviewSizes = emptyList(),
        usesBleV1 = false,
        supportsApps = true,
        requiresAodFace = false,
    ),
    BAND_10(
        label = "Mi Band 10",
        screenWidth = 212,
        screenHeight = 520,
        knownPreviewSizes = emptyList(),
        usesBleV1 = false,
        supportsApps = true,
        requiresAodFace = false,
    ),
    ;

    val screenText: String get() = "${screenWidth}×$screenHeight"

    companion object {
        /** Padrões de nome do Gadgetbridge (getSupportedDeviceName). */
        private val EXACT = listOf(
            BAND_8 to Regex("^Xiaomi Smart Band 8 [A-Z0-9]{4}$"),
            BAND_9 to Regex("^Xiaomi Smart Band 9 [0-9A-F]{4}$"),
            BAND_10 to Regex("^Xiaomi Smart Band 10 [0-9A-F]{4}$"),
        )

        /**
         * Modelo pelo nome Bluetooth, ou null se não for um dos suportados. Aceita também nomes
         * como "Mi Band 9"; recusa Pro, Active e NFC com tela diferente.
         */
        fun fromDeviceName(name: String?): BandModel? {
            if (name == null) return null
            EXACT.firstOrNull { it.second.matches(name) }?.let { return it.first }
            val lower = name.lowercase()
            if ("band" !in lower || "pro" in lower || "active" in lower) return null
            return when {
                Regex("\\bband\\s*10\\b").containsMatchIn(lower) -> BAND_10
                Regex("\\bband\\s*9\\b").containsMatchIn(lower) -> BAND_9
                Regex("\\bband\\s*8\\b").containsMatchIn(lower) -> BAND_8
                else -> null
            }
        }
    }
}
