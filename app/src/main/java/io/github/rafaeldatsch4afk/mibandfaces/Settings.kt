package io.github.rafaeldatsch4afk.mibandfaces

import android.content.Context
import androidx.core.content.edit
import io.github.rafaeldatsch4afk.mibandfaces.band.BandModel

/** Tipo de transporte usado para falar com a banda. */
enum class TransportKind(val label: String) {
    /** Bluetooth Clássico (SPP/RFCOMM): o que o Gadgetbridge usa para as Mi Band 9 e 10. */
    SPP("Bluetooth Clássico (SPP) — Mi Band 9 e 10"),

    /** BLE "V1" (GATT FE95, características 0051–0055): o protocolo da Mi Band 8. */
    BLE_V1("Bluetooth Low Energy V1 — Mi Band 8"),

    /** BLE V2 (GATT FE95, 005E/005F): alternativa ao SPP, ainda não validada com uma banda real. */
    BLE("Bluetooth Low Energy V2 — experimental"),
    ;

    companion object {
        /** Transporte recomendado para o modelo (o mesmo do Gadgetbridge). */
        fun defaultFor(model: BandModel): TransportKind = if (model.usesBleV1) BLE_V1 else SPP
    }
}

/** Preferências simples (não secretas). A chave de autenticação fica no AuthKeyStore. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var deviceAddress: String?
        get() = prefs.getString("device_address", null)
        set(value) = prefs.edit { putString("device_address", value) }

    var deviceName: String?
        get() = prefs.getString("device_name", null)
        set(value) = prefs.edit { putString("device_name", value) }

    var transport: TransportKind
        get() = runCatching { TransportKind.valueOf(prefs.getString("transport", null) ?: "") }.getOrDefault(TransportKind.SPP)
        set(value) = prefs.edit { putString("transport", value.name) }

    /** Modelo escolhido à mão (null = detectar pelo nome da banda). */
    var bandModel: BandModel?
        get() = prefs.getString("band_model", null)?.let { name -> BandModel.entries.firstOrNull { it.name == name } }
        set(value) = prefs.edit { putString("band_model", value?.name) }

    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", true)
        set(value) = prefs.edit { putBoolean("dark_theme", value) }
}
