package io.github.rafaeldatsch4afk.mibandfaces

import android.app.Application
import android.bluetooth.BluetoothManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rafaeldatsch4afk.mibandfaces.band.BandClient
import io.github.rafaeldatsch4afk.mibandfaces.band.BandState
import io.github.rafaeldatsch4afk.mibandfaces.band.CompanionInfo
import io.github.rafaeldatsch4afk.mibandfaces.band.FaceAlreadyInstalledException
import io.github.rafaeldatsch4afk.mibandfaces.band.InstallProgress
import io.github.rafaeldatsch4afk.mibandfaces.band.InstalledFace
import io.github.rafaeldatsch4afk.mibandfaces.band.WatchfaceManager
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BandScanner
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BandTransport
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BleTransport
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.FoundDevice
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.SppTransport
import io.github.rafaeldatsch4afk.mibandfaces.face.ArgbImage
import io.github.rafaeldatsch4afk.mibandfaces.face.FaceFile
import io.github.rafaeldatsch4afk.mibandfaces.face.FacePreview
import io.github.rafaeldatsch4afk.mibandfaces.key.AndroidSqliteRows
import io.github.rafaeldatsch4afk.mibandfaces.key.AuthKey
import io.github.rafaeldatsch4afk.mibandfaces.key.AuthKeyImporter
import io.github.rafaeldatsch4afk.mibandfaces.key.AuthKeyStore
import io.github.rafaeldatsch4afk.mibandfaces.key.KeyCandidate
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val log = PacketLog()
    private val settings = Settings(app)
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    val scanner = BandScanner(app, adapter, log)
    val client = BandClient(
        log,
        CompanionInfo(
            apiLevel = Build.VERSION.SDK_INT,
            phoneModel = Build.MODEL ?: "Android",
            // Mesmo critério do Gadgetbridge: as duas letras do idioma, em maiúsculas.
            region = Locale.getDefault().language.take(2).uppercase(Locale.ROOT).ifEmpty { "EN" },
        ),
    )
    private val keyStore = AuthKeyStore(app)
    val watchfaces = WatchfaceManager(client, log)

    /** Chave salva (normalizada) ou null. Só a versão mascarada vai para a tela. */
    private val _savedKey = MutableStateFlow(keyStore.load())
    val savedKeyMasked: StateFlow<String?> = _savedKey
        .map { it?.let(AuthKey::mask) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _savedKey.value?.let(AuthKey::mask))

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

    /** Arquivo de watch face escolhido e o resultado da validação. */
    data class SelectedFace(val fileName: String, val parsed: FaceFile, val preview: ArgbImage?)

    private val _selectedFace = MutableStateFlow<SelectedFace?>(null)
    val selectedFace: StateFlow<SelectedFace?> = _selectedFace.asStateFlow()

    sealed interface InstallState {
        data object Idle : InstallState
        data class Running(val progress: InstallProgress) : InstallState
        data class Done(val message: String) : InstallState
        data class Failed(val message: String) : InstallState

        /** A mesma face já está na banda: perguntar se deve remover a antiga. */
        data class AskReplace(val existing: InstalledFace) : InstallState
    }

    private val _install = MutableStateFlow<InstallState>(InstallState.Idle)
    val install: StateFlow<InstallState> = _install.asStateFlow()
    private var installJob: Job? = null

    init {
        // Ao autenticar, lê a lista de faces; ao desconectar, esquece a lista.
        viewModelScope.launch {
            client.state.collect { state ->
                when (state) {
                    is BandState.Ready -> runCatching { watchfaces.refresh() }.onFailure {
                        log.warn("Não consegui ler a lista de faces: ${it.message}")
                    }
                    is BandState.Disconnected, is BandState.Failed -> watchfaces.clear()
                    else -> Unit
                }
            }
        }
    }

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

    /** Valida e salva a chave. Retorna a mensagem de erro, ou null se deu certo. */
    fun saveKey(input: String): String? {
        val key = AuthKey.normalize(input).getOrElse { return it.message }
        return try {
            keyStore.save(key)
            _savedKey.value = key
            log.info("Auth key salva no Keystore (${AuthKey.mask(key)})")
            null
        } catch (e: Exception) {
            log.error("Falha ao salvar a chave no Keystore", e)
            "Não consegui salvar a chave no Keystore: ${e.message}"
        }
    }

    /** Chaves encontradas no último arquivo importado (null = nenhuma importação pendente). */
    private val _keyCandidates = MutableStateFlow<List<KeyCandidate>?>(null)
    val keyCandidates: StateFlow<List<KeyCandidate>?> = _keyCandidates.asStateFlow()

    fun importKey(uri: Uri) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val (name, _) = withContext(Dispatchers.IO) { describe(uri) }
                val bytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { readLimited(it, AuthKeyImporter.MAX_FILE_SIZE) }
                } ?: run {
                    _message.value = "Não consegui ler o arquivo (ou ele passa de 64 MB)"
                    return@launch
                }
                val found = withContext(Dispatchers.IO) {
                    AuthKeyImporter(AndroidSqliteRows(app)).import(name ?: "arquivo", bytes)
                }
                log.info("Importação de chave em ${name ?: "arquivo"}: ${found.size} candidata(s)")
                _keyCandidates.value = found
                if (found.isEmpty()) _message.value = "Nenhuma chave encontrada nesse arquivo"
            } catch (e: Exception) {
                log.error("Falha ao importar a chave", e)
                _message.value = "Falha ao importar: ${e.message}"
            }
        }
    }

    fun useCandidate(candidate: KeyCandidate) {
        val error = saveKey(candidate.key)
        if (error != null) _message.value = error
        else {
            _keyCandidates.value = null
            _message.value = "Chave importada (${candidate.source})"
        }
    }

    fun dismissCandidates() {
        _keyCandidates.value = null
    }

    fun deleteKey() {
        keyStore.clear()
        _savedKey.value = null
        log.info("Auth key apagada")
    }

    fun connect() {
        val address = _selected.value?.address ?: run {
            _message.value = "Escolha a banda primeiro"
            return
        }
        val key = _savedKey.value ?: run {
            _message.value = "Configure a auth key na aba Chave primeiro"
            return
        }
        viewModelScope.launch {
            try {
                scanner.stop()
                client.connect(createTransport(address), AuthKey.toBytes(key))
            } catch (e: SecurityException) {
                _message.value = "Permissão de Bluetooth negada"
            } catch (e: Exception) {
                _message.value = e.message ?: "Falha ao conectar"
            }
        }
    }

    fun disconnect() = client.disconnect()

    // ---------------------------------------------------------------- watch faces ---

    fun pickFace(uri: Uri) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val (name, size) = withContext(Dispatchers.IO) { describe(uri) }
                if (size != null && size > FaceFile.MAX_SIZE) {
                    _message.value = "Arquivo grande demais para uma watch face (${size / 1024} KB)"
                    return@launch
                }
                val bytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { readLimited(it, FaceFile.MAX_SIZE) }
                }
                if (bytes == null) {
                    _message.value = "Não consegui ler o arquivo (ou ele é grande demais)"
                    return@launch
                }
                val parsed = withContext(Dispatchers.Default) { FaceFile.parse(bytes) }
                val preview = (parsed as? FaceFile.V2)?.preview?.let { header ->
                    withContext(Dispatchers.Default) { runCatching { FacePreview.decode(bytes, header) }.getOrNull() }
                }
                _selectedFace.value = SelectedFace(name ?: "arquivo", parsed, preview)
                _install.value = InstallState.Idle
                when (parsed) {
                    is FaceFile.V2 -> log.info("Arquivo ${name}: face v2 \"${parsed.name}\" id=${parsed.id}, ${bytes.size} bytes, preview=${parsed.preview?.let { "${it.width}x${it.height}" }}")
                    is FaceFile.Rejected -> log.warn("Arquivo ${name} recusado: ${parsed.reason}")
                }
            } catch (e: Exception) {
                log.error("Falha ao abrir o arquivo", e)
                _message.value = "Falha ao abrir o arquivo: ${e.message}"
            }
        }
    }

    /** Lê no máximo [limit] bytes; retorna null se o arquivo for maior, para não estourar a memória. */
    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) return null
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    private fun describe(uri: Uri): Pair<String?, Long?> {
        val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null) ?: return null to null
        cursor.use {
            if (!it.moveToFirst()) return null to null
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) it.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else null
            return name to size
        }
    }

    fun installSelected(replaceExisting: Boolean = false) {
        val face = _selectedFace.value?.parsed as? FaceFile.V2 ?: return
        if (installJob?.isActive == true) return
        installJob = viewModelScope.launch {
            _install.value = InstallState.Running(InstallProgress("Começando…", null))
            try {
                watchfaces.install(face, replaceExisting) { _install.value = InstallState.Running(it) }
                _install.value = InstallState.Done("\"${face.name}\" instalada e ativada.")
            } catch (e: FaceAlreadyInstalledException) {
                _install.value = InstallState.AskReplace(e.face)
            } catch (e: CancellationException) {
                _install.value = InstallState.Failed("Envio cancelado. A face não foi ativada e a conexão foi fechada.")
                throw e
            } catch (e: Exception) {
                log.error("Instalação falhou", e)
                _install.value = InstallState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
    }

    fun dismissInstallResult() {
        if (installJob?.isActive != true) _install.value = InstallState.Idle
    }

    fun refreshFaces() = faceAction { watchfaces.refresh() }

    fun activateFace(face: InstalledFace) = faceAction { watchfaces.setActive(face.id) }

    fun deleteFace(face: InstalledFace) = faceAction { watchfaces.delete(face) }

    private fun faceAction(action: suspend () -> Unit) {
        if (installJob?.isActive == true) {
            _message.value = "Espere a instalação terminar"
            return
        }
        viewModelScope.launch {
            try {
                action()
            } catch (e: Exception) {
                _message.value = e.message ?: "Falhou"
            }
        }
    }

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
