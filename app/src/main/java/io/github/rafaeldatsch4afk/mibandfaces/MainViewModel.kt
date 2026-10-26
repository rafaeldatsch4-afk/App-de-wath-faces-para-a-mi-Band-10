package io.github.rafaeldatsch4afk.mibandfaces

import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rafaeldatsch4afk.mibandfaces.band.BandClient
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BandScanner
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BandTransport
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BleTransport
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.FoundDevice
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.SppTransport
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val log = PacketLog()
    private val settings = Settings(app)
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    val scanner = BandScanner(app, adapter, log)
    val client = BandClient(log)

    private val _darkTheme = MutableStateFlow(settings.darkTheme)
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _transport = MutableStateFlow(settings.transport)
    val transport: StateFlow<TransportKind> = _transport.asStateFlow()

    private val _selected = MutableStateFlow(settings.deviceAddress?.let { FoundDevice(it, settings.deviceName) })
    val selected: StateFlow<FoundDevice?> = _selected.asStateFlow()

    /** Mensagem curta para o usuário (snackbar). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var scanTimeout: Job? = null

    val hasBluetooth: Boolean get() = adapter != null
    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    fun consumeMessage() {
        _message.value = null
    }

    fun toggleTheme() {
        val next = !_darkTheme.value
        _darkTheme.value = next
        settings.darkTheme = next
    }

    fun setTransport(kind: TransportKind) {
        _transport.value = kind
        settings.transport = kind
    }

    fun startScan() = guarded {
        scanner.start()
        scanTimeout?.cancel()
        scanTimeout = viewModelScope.launch {
            delay(SCAN_DURATION_MS)
            scanner.stop()
        }
    }

    fun stopScan() = guarded { scanner.stop() }

    fun refreshBonded() = guarded { scanner.refreshBonded() }

    fun select(device: FoundDevice) {
        _selected.value = device
        settings.deviceAddress = device.address
        settings.deviceName = device.name
        log.info("Banda escolhida: ${device.name ?: "?"} (${device.address})")
    }

    fun bond() = guarded {
        val address = _selected.value?.address ?: return@guarded
        if (!scanner.bond(address)) _message.value = "O Android não iniciou o pareamento. Tente de novo."
    }

    fun connect() {
        val address = _selected.value?.address ?: run {
            _message.value = "Escolha a banda primeiro"
            return
        }
        viewModelScope.launch {
            try {
                scanner.stop()
                client.connect(createTransport(address))
            } catch (e: SecurityException) {
                _message.value = "Permissão de Bluetooth negada"
            } catch (e: Exception) {
                _message.value = e.message ?: "Falha ao conectar"
            }
        }
    }

    fun disconnect() = client.disconnect()

    private fun createTransport(address: String): BandTransport {
        val a = adapter ?: throw IllegalStateException("Sem Bluetooth")
        val device = a.getRemoteDevice(address)
        return when (_transport.value) {
            TransportKind.SPP -> SppTransport(a, device, log)
            TransportKind.BLE -> BleTransport(getApplication(), device, log)
        }
    }

    /** Executa uma ação que usa a API de Bluetooth, tratando a falta de permissão. */
    private fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (e: SecurityException) {
            _message.value = "Permissão de Bluetooth negada"
            log.error("Permissão negada", e)
        }
    }

    override fun onCleared() {
        scanner.release()
        client.disconnect()
    }

    private companion object {
        const val SCAN_DURATION_MS = 20_000L
    }
}
