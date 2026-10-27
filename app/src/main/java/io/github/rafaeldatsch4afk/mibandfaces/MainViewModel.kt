package io.github.rafaeldatsch4afk.mibandfaces

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.bluetooth.BluetoothManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rafaeldatsch4afk.mibandfaces.band.BandState
import io.github.rafaeldatsch4afk.mibandfaces.band.AppAlreadyInstalledException
import io.github.rafaeldatsch4afk.mibandfaces.band.FaceAlreadyInstalledException
import io.github.rafaeldatsch4afk.mibandfaces.band.InstallProgress
import io.github.rafaeldatsch4afk.mibandfaces.band.InstalledApp
import io.github.rafaeldatsch4afk.mibandfaces.band.InstalledFace
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BandScanner
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BandTransport
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BleTransport
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.FoundDevice
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.SppTransport
import io.github.rafaeldatsch4afk.mibandfaces.cloud.CloudDevice
import io.github.rafaeldatsch4afk.mibandfaces.cloud.CloudException
import io.github.rafaeldatsch4afk.mibandfaces.cloud.UrlConnectionHttp
import io.github.rafaeldatsch4afk.mibandfaces.cloud.XiaomiCloud
import io.github.rafaeldatsch4afk.mibandfaces.face.ArgbImage
import io.github.rafaeldatsch4afk.mibandfaces.face.FaceFile
import io.github.rafaeldatsch4afk.mibandfaces.face.FacePreview
import io.github.rafaeldatsch4afk.mibandfaces.key.AndroidSqliteRows
import io.github.rafaeldatsch4afk.mibandfaces.key.AuthKey
import io.github.rafaeldatsch4afk.mibandfaces.key.AuthKeyImporter
import io.github.rafaeldatsch4afk.mibandfaces.key.AuthKeyStore
import io.github.rafaeldatsch4afk.mibandfaces.key.KeyCandidate
import io.github.rafaeldatsch4afk.mibandfaces.miniapp.RpkFile
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

class MainViewModel(app: Application) : AndroidViewModel(app) {
    // Conexão, log e lista de faces ficam no processo (AppContainer), não aqui: o ConnectionService
    // as mantém vivas com o app em segundo plano.
    private val container = (app as App).container
    val log = container.log
    private val settings = Settings(app)
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    val scanner = BandScanner(app, adapter, log)
    val client = container.client
    private val keyStore = AuthKeyStore(app)
    val watchfaces = container.watchfaces
    val miniApps = container.miniApps

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

    /** Mini-app .rpk escolhido (no lugar de uma face) e o ícone dele, se houver. */
    data class SelectedApp(val fileName: String, val parsed: RpkFile, val icon: Bitmap?)

    private val _selectedApp = MutableStateFlow<SelectedApp?>(null)
    val selectedApp: StateFlow<SelectedApp?> = _selectedApp.asStateFlow()

    sealed interface InstallState {
        data object Idle : InstallState
        data class Running(val progress: InstallProgress) : InstallState
        data class Done(val message: String) : InstallState
        data class Failed(val message: String) : InstallState

        /** A mesma face já está na banda: perguntar se deve remover a antiga. */
        data class AskReplace(val existing: InstalledFace) : InstallState

        /** O mesmo mini-app já está na banda: perguntar se deve remover o antigo. */
        data class AskReplaceApp(val existing: InstalledApp) : InstallState
    }

    private val _install = MutableStateFlow<InstallState>(InstallState.Idle)
    val install: StateFlow<InstallState> = _install.asStateFlow()
    private var installJob: Job? = null

    init {
        // Ao autenticar, lê a lista de faces; ao desconectar, esquece a lista.
        viewModelScope.launch {
            client.state.collect { state ->
                when (state) {
                    is BandState.Ready -> {
                        runCatching { watchfaces.refresh() }.onFailure {
                            log.warn("Não consegui ler a lista de faces: ${it.message}")
                        }
                        runCatching { miniApps.refresh() }.onFailure {
                            log.warn("Não consegui ler a lista de mini-apps: ${it.message}")
                        }
                    }
                    is BandState.Disconnected, is BandState.Failed -> {
                        watchfaces.clear()
                        miniApps.clear()
                        ConnectionService.stop(getApplication())
                    }
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

    // ------------------------------------------------------ login na conta Xiaomi ---

    sealed interface CloudLoginState {
        data object Idle : CloudLoginState
        data class Working(val step: String) : CloudLoginState

        /** Esperando o usuário confirmar o login na página da Xiaomi (QR ou navegador). */
        class WaitingForLogin(val qrImage: ByteArray?, val loginUrl: String, val expiresAtMillis: Long) : CloudLoginState
        data class Failed(val message: String) : CloudLoginState
    }

    private val _cloudLogin = MutableStateFlow<CloudLoginState>(CloudLoginState.Idle)
    val cloudLogin: StateFlow<CloudLoginState> = _cloudLogin.asStateFlow()
    private var cloudJob: Job? = null

    /**
     * Busca a auth key na conta Xiaomi: o usuário confirma o login na página da Xiaomi (o app não
     * vê a senha), o app lê a lista de aparelhos do Mi Fitness e mostra as chaves encontradas.
     * [region] null = detectar sozinho. A sessão fica só na memória, dentro desta função.
     */
    fun startCloudLogin(region: String?) {
        cloudJob?.cancel()
        cloudJob = viewModelScope.launch {
            try {
                val cloud = XiaomiCloud(UrlConnectionHttp(), log = { log.info(it) })
                _cloudLogin.value = CloudLoginState.Working("Pedindo o código QR à Xiaomi…")
                val start = cloud.startQrLogin()
                _cloudLogin.value = CloudLoginState.WaitingForLogin(
                    cloud.downloadQr(start),
                    start.loginUrl,
                    System.currentTimeMillis() + start.timeoutSeconds * 1000L,
                )

                val login = cloud.awaitQrLogin(start)

                _cloudLogin.value = CloudLoginState.Working("Entrando no Mi Fitness…")
                val session = cloud.healthSession(login)
                _cloudLogin.value = CloudLoginState.Working("Lendo os aparelhos da conta…")
                val result = cloud.listFitnessDevices(session, region)
                if (!result.ok) {
                    throw CloudException("A Xiaomi recusou a leitura dos aparelhos. Veja os detalhes na aba Logs e tente outra região.")
                }
                val withKey = result.devices.filter { it.key != null }
                if (withKey.isEmpty()) {
                    val names = result.devices.joinToString { it.name.ifBlank { it.model } }
                    throw CloudException(
                        if (result.devices.isEmpty()) "Nenhum aparelho do Mi Fitness nesta conta/região. Tente escolher a região da conta."
                        else "A conta lista aparelhos ($names), mas nenhum veio com chave.",
                    )
                }
                // Mi Band 10 primeiro; depois os campos mais confiáveis.
                val ordered = withKey.sortedWith(
                    compareByDescending<CloudDevice> { "band 10" in it.name.lowercase() }
                        .thenByDescending { it.keyField != "token" },
                )
                _keyCandidates.value = ordered.map {
                    KeyCandidate(
                        key = it.key!!,
                        source = "Conta Xiaomi: ${it.name.ifBlank { it.model }} (${it.keyField}, ${it.host})",
                        recommended = it.keyField != "token",
                    )
                }
                log.info("Conta Xiaomi: ${withKey.size} chave(s) encontrada(s)")
                _cloudLogin.value = CloudLoginState.Idle
            } catch (e: CancellationException) {
                _cloudLogin.value = CloudLoginState.Idle
                throw e
            } catch (e: Exception) {
                log.error("Login na conta Xiaomi falhou", e)
                _cloudLogin.value = CloudLoginState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelCloudLogin() {
        cloudJob?.cancel()
        _cloudLogin.value = CloudLoginState.Idle
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
                // Serviço em primeiro plano: evita que o Android congele o app e derrube a conexão.
                if (!ConnectionService.start(getApplication())) log.warn("Não consegui iniciar o serviço em primeiro plano; a conexão pode cair em segundo plano")
                client.connect({ createTransport(address) }, AuthKey.toBytes(key), autoReconnect = true)
            } catch (e: SecurityException) {
                _message.value = "Permissão de Bluetooth negada"
            } catch (e: Exception) {
                _message.value = e.message ?: "Falha ao conectar"
            }
        }
    }

    fun disconnect() = client.disconnect()

    // ------------------------------------------------------- watch faces e mini-apps ---

    /** Abre um arquivo escolhido: watch face (.face/.bin) ou mini-app (.rpk), pelo conteúdo. */
    fun pickFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val (name, size) = withContext(Dispatchers.IO) { describe(uri) }
                val limit = maxOf(FaceFile.MAX_SIZE, RpkFile.MAX_SIZE)
                if (size != null && size > limit) {
                    _message.value = "Arquivo grande demais (${size / 1024} KB)"
                    return@launch
                }
                val bytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { readLimited(it, limit) }
                }
                if (bytes == null) {
                    _message.value = "Não consegui ler o arquivo (ou ele é grande demais)"
                    return@launch
                }
                _install.value = InstallState.Idle
                if (RpkFile.looksLikeRpk(bytes)) {
                    openApp(name ?: "arquivo", bytes)
                    return@launch
                }
                _selectedApp.value = null
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

    private suspend fun openApp(fileName: String, bytes: ByteArray) {
        val parsed = withContext(Dispatchers.Default) { RpkFile.parse(bytes) }
        val icon = (parsed as? RpkFile.Valid)?.icon?.let { png ->
            withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeByteArray(png, 0, png.size) }.getOrNull() }
        }
        _selectedFace.value = null
        _selectedApp.value = SelectedApp(fileName, parsed, icon)
        when (parsed) {
            is RpkFile.Valid -> log.info(
                "Arquivo $fileName: mini-app \"${parsed.name}\" ${parsed.packageName} v${parsed.versionName} " +
                    "(versionCode ${parsed.versionCode}), ${bytes.size} bytes, ${parsed.fileCount} arquivos" +
                    parsed.warnings.joinToString("") { "; aviso: $it" },
            )
            is RpkFile.Rejected -> log.warn("Arquivo $fileName recusado: ${parsed.reason}")
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
        (_selectedApp.value?.parsed as? RpkFile.Valid)?.let { return installApp(it, replaceExisting) }
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

    private fun installApp(file: RpkFile.Valid, replaceExisting: Boolean) {
        if (installJob?.isActive == true) return
        installJob = viewModelScope.launch {
            _install.value = InstallState.Running(InstallProgress("Começando…", null))
            try {
                miniApps.install(file, replaceExisting) { _install.value = InstallState.Running(it) }
                _install.value = InstallState.Done("App \"${file.name}\" instalado. Procure por ele na lista de apps da banda.")
            } catch (e: AppAlreadyInstalledException) {
                _install.value = InstallState.AskReplaceApp(e.app)
            } catch (e: CancellationException) {
                _install.value = InstallState.Failed("Envio cancelado. O app não foi instalado e a conexão foi fechada.")
                throw e
            } catch (e: Exception) {
                log.error("Instalação do app falhou", e)
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

    fun refreshApps() = faceAction { miniApps.refresh() }

    fun deleteApp(app: InstalledApp) = faceAction { miniApps.delete(app) }

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
        // A conexão NÃO é fechada aqui: ela pertence ao processo e ao ConnectionService.
        scanner.release()
    }

    private companion object {
        const val SCAN_DURATION_MS = 20_000L
    }
}
