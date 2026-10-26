package io.github.rafaeldatsch4afk.mibandfaces

import android.content.Context
import androidx.core.content.edit

/** Tipo de transporte usado para falar com a banda. */
enum class TransportKind(val label: String) {
    /** Bluetooth Clássico (SPP/RFCOMM): o que o Gadgetbridge usa para a Mi Band 10. */
    SPP("Bluetooth Clássico (SPP) — recomendado"),

    /** BLE V2 (GATT FE95): alternativa, ainda não validada com a Mi Band 10 real. */
    BLE("Bluetooth Low Energy (BLE V2) — experimental"),
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

    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", true)
        set(value) = prefs.edit { putBoolean("dark_theme", value) }
}
