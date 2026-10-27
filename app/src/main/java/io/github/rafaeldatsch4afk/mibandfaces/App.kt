package io.github.rafaeldatsch4afk.mibandfaces

import android.app.Application
import android.os.Build
import android.text.format.DateFormat
import io.github.rafaeldatsch4afk.mibandfaces.band.BandClient
import io.github.rafaeldatsch4afk.mibandfaces.band.CompanionInfo
import io.github.rafaeldatsch4afk.mibandfaces.band.MiniAppManager
import io.github.rafaeldatsch4afk.mibandfaces.band.WatchfaceManager
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.AclLogger
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import java.util.Locale

/**
 * Objetos que precisam sobreviver à tela: a conexão com a banda, o log e as listas de faces e apps.
 * Ficam no processo (e não no ViewModel) para que o [ConnectionService] mantenha a conexão viva
 * quando o app vai para segundo plano ou a Activity é recriada.
 */
class AppContainer(app: Application) {
    val log = PacketLog()
    val client = BandClient(
        log,
        CompanionInfo(
            apiLevel = Build.VERSION.SDK_INT,
            phoneModel = Build.MODEL ?: "Android",
            // Mesmo critério do Gadgetbridge: as duas letras do idioma, em maiúsculas.
            region = Locale.getDefault().language.take(2).uppercase(Locale.ROOT).ifEmpty { "EN" },
        ),
        is24HourFormat = { DateFormat.is24HourFormat(app) },
    )
    val watchfaces = WatchfaceManager(client, log)
    val miniApps = MiniAppManager(client, log)
    private val settings = Settings(app)

    init {
        // Registra no log quando o Android conecta/desconecta a banda (de que lado a conexão cai).
        AclLogger(app, log) { settings.deviceAddress }.register()
    }
}

class App : Application() {
    val container by lazy { AppContainer(this) }
}
