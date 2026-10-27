package io.github.rafaeldatsch4afk.mibandfaces.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rafaeldatsch4afk.mibandfaces.MainViewModel
import io.github.rafaeldatsch4afk.mibandfaces.MainViewModel.InstallState
import io.github.rafaeldatsch4afk.mibandfaces.band.BandState
import io.github.rafaeldatsch4afk.mibandfaces.band.WatchfaceManager
import io.github.rafaeldatsch4afk.mibandfaces.face.ArgbImage
import io.github.rafaeldatsch4afk.mibandfaces.face.Compatibility
import io.github.rafaeldatsch4afk.mibandfaces.face.FaceFile
import io.github.rafaeldatsch4afk.mibandfaces.miniapp.RpkFile

@Composable
fun InstallScreen(vm: MainViewModel) {
    val selected by vm.selectedFace.collectAsStateWithLifecycle()
    val selectedApp by vm.selectedApp.collectAsStateWithLifecycle()
    val install by vm.install.collectAsStateWithLifecycle()
    val band by vm.client.state.collectAsStateWithLifecycle()
    val faces by vm.watchfaces.faces.collectAsStateWithLifecycle()
    var acceptedRisk by remember { mutableStateOf(false) }
    val running = install is InstallState.Running

    // Mantém a tela ligada durante o envio: o app precisa ficar aberto até terminar.
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            acceptedRisk = false
            vm.pickFile(uri)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Arquivo") {
            Text(
                "Escolha uma watch face (.face ou .bin) ou um mini-app (.rpk) para a Mi Band 10.",
                style = MaterialTheme.typography.bodyMedium,
            )
            // .face não tem tipo MIME registrado; a validação é feita pelo conteúdo.
            Button(enabled = !running, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Escolher arquivo") }
        }

        selected?.let { sel ->
            FaceDetails(sel.fileName, sel.parsed, sel.preview)
        }
        selectedApp?.let { sel ->
            AppDetails(sel.fileName, sel.parsed, sel.icon)
        }

        val app = selectedApp?.parsed as? RpkFile.Valid
        if (app != null) {
            SectionCard("Instalar o app") {
                WarningBox(
                    "Riscos: um mini-app roda código de outra pessoa na banda. Ele não mexe no firmware, mas " +
                        "um app com defeito pode travar ou fechar sozinho (aí é só removê-lo na aba Faces). " +
                        "Instale só apps de quem você confia. Mantenha a banda carregada e perto do celular.",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acceptedRisk, onCheckedChange = { acceptedRisk = it }, enabled = !running)
                    Text("Entendo os riscos e quero instalar", style = MaterialTheme.typography.bodyMedium)
                }
                if (band !is BandState.Ready) {
                    Text("Conecte a banda na aba Conexão primeiro.", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    enabled = acceptedRisk && !running && band is BandState.Ready,
                    onClick = { vm.installSelected() },
                ) { Text("Instalar o app na banda") }
            }
        }

        val face = selected?.parsed as? FaceFile.V2
        if (face != null && face.canInstall) {
            SectionCard("Instalar") {
                WarningBox(
                    "Riscos: um envio corrompido ou uma face malformada pode travar a banda. O arquivo foi " +
                        "validado e a instalação já funcionou numa Mi Band 10 real, mas cada face é diferente. Mantenha a banda " +
                        "carregada e perto do celular, e não feche o app durante o envio.",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acceptedRisk, onCheckedChange = { acceptedRisk = it }, enabled = !running)
                    Text("Entendo os riscos e quero instalar", style = MaterialTheme.typography.bodyMedium)
                }
                faces?.let { Text("Faces na banda: ${it.size}/${WatchfaceManager.MAX_FACES}", style = MaterialTheme.typography.bodySmall) }
                if (band !is BandState.Ready) {
                    Text("Conecte a banda na aba Conexão primeiro.", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    enabled = acceptedRisk && !running && band is BandState.Ready,
                    onClick = { vm.installSelected() },
                ) { Text("Instalar na banda") }
            }
        }

        when (val state = install) {
            is InstallState.Running -> SectionCard("Enviando") {
                Text(state.progress.stage)
                val fraction = state.progress.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = { vm.cancelInstall() }) { Text("Cancelar") }
                Text(
                    "Cancelar para entre duas partes (nunca no meio de um pacote), não ativa nada e fecha a conexão.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is InstallState.Done -> SectionCard("Pronto") {
                Text(state.message, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { vm.dismissInstallResult() }) { Text("OK") }
            }
            is InstallState.Failed -> SectionCard("Falhou") {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Text("Veja a aba Logs para os detalhes.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.dismissInstallResult() }) { Text("OK") }
            }
            is InstallState.AskReplace -> AlertDialog(
                onDismissRequest = { vm.dismissInstallResult() },
                title = { Text("Face já instalada") },
                text = {
                    Text(
                        "\"${state.existing.name}\" (ID ${state.existing.id}) já está na banda. A versão antiga " +
                            "precisa ser removida antes de instalar a nova." +
                            if (state.existing.active) " Ela está ativa: outra face será ativada antes." else "",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.installSelected(replaceExisting = true) }) { Text("Remover a antiga e instalar") }
                },
                dismissButton = { TextButton(onClick = { vm.dismissInstallResult() }) { Text("Cancelar") } },
            )
            is InstallState.AskReplaceApp -> AlertDialog(
                onDismissRequest = { vm.dismissInstallResult() },
                title = { Text("App já instalado") },
                text = {
                    Text(
                        "\"${state.existing.name}\" (${state.existing.id}) já está na banda. A versão antiga " +
                            "precisa ser removida antes de instalar a nova (os dados salvos pelo app na banda podem se perder).",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.installSelected(replaceExisting = true) }) { Text("Remover o antigo e instalar") }
                },
                dismissButton = { TextButton(onClick = { vm.dismissInstallResult() }) { Text("Cancelar") } },
            )
            InstallState.Idle -> Unit
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun FaceDetails(fileName: String, parsed: FaceFile, preview: ArgbImage?) {
    SectionCard("Validação") {
        LabeledValue("Arquivo", fileName)
        LabeledValue("Tamanho", "${parsed.bytes.size / 1024} KB (${parsed.bytes.size} bytes)")
        when (parsed) {
            is FaceFile.Rejected -> {
                parsed.name?.let { LabeledValue("Nome", it) }
                WarningBox("Não pode ser instalado: ${parsed.reason}")
            }
            is FaceFile.V2 -> {
                LabeledValue("Nome", parsed.name)
                LabeledValue("ID", parsed.id)
                LabeledValue("Formato", "watch face v2, ${parsed.faceCount} tela(s) (normal + AOD)")
                LabeledValue("Preview", parsed.preview?.let { "${it.width}×${it.height}" } ?: "ausente")
                Text("✓ Estrutura interna conferida (tabelas e imagens dentro do arquivo)", style = MaterialTheme.typography.bodySmall)
                when (parsed.compatibility) {
                    Compatibility.EXACT -> Text("✓ ${parsed.compatibility.description}", style = MaterialTheme.typography.bodySmall)
                    Compatibility.SAME_ASPECT, Compatibility.UNKNOWN ->
                        WarningBox(parsed.compatibility.description, color = MaterialTheme.colorScheme.primary)
                    Compatibility.INCOMPATIBLE -> WarningBox("Não pode ser instalado: ${parsed.compatibility.description}")
                }
                if (preview != null) PreviewImage(preview)
            }
        }
    }
}

@Composable
private fun AppDetails(fileName: String, parsed: RpkFile, icon: Bitmap?) {
    SectionCard("Validação do mini-app") {
        LabeledValue("Arquivo", fileName)
        LabeledValue("Tamanho", "${parsed.bytes.size / 1024} KB (${parsed.bytes.size} bytes)")
        when (parsed) {
            is RpkFile.Rejected -> WarningBox("Não pode ser instalado: ${parsed.reason}")
            is RpkFile.Valid -> {
                if (icon != null) {
                    val bitmap = remember(icon) { icon.asImageBitmap() }
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Ícone do app",
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)),
                    )
                }
                LabeledValue("Nome", parsed.name)
                LabeledValue("Pacote", parsed.packageName)
                LabeledValue("Versão", "${parsed.versionName} (versionCode ${parsed.versionCode})")
                parsed.designWidth?.let { LabeledValue("Largura de tela", "$it px") }
                if (parsed.features.isNotEmpty()) LabeledValue("Recursos usados", parsed.features.joinToString())
                Text(
                    "✓ ZIP conferido: ${parsed.fileCount} arquivos, ${parsed.unpackedSize / 1024} KB descompactados, CRC de cada arquivo OK",
                    style = MaterialTheme.typography.bodySmall,
                )
                parsed.warnings.forEach { WarningBox(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun PreviewImage(image: ArgbImage) {
    val bitmap = remember(image) {
        Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Mostra na proporção da tela da banda (212×520).
        Box(
            Modifier
                .height(260.dp)
                .aspectRatio(FaceFile.SCREEN_WIDTH.toFloat() / FaceFile.SCREEN_HEIGHT)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Preview da watch face",
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
