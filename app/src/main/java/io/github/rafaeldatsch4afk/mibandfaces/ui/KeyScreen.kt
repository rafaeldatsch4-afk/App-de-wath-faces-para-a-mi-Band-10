package io.github.rafaeldatsch4afk.mibandfaces.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rafaeldatsch4afk.mibandfaces.MainViewModel
import io.github.rafaeldatsch4afk.mibandfaces.cloud.XiaomiCloud
import io.github.rafaeldatsch4afk.mibandfaces.key.AuthKey
import io.github.rafaeldatsch4afk.mibandfaces.key.KeyCandidate
import kotlinx.coroutines.delay

@Composable
fun KeyScreen(vm: MainViewModel) {
    val saved by vm.savedKeyMasked.collectAsStateWithLifecycle()
    val candidates by vm.keyCandidates.collectAsStateWithLifecycle()
    val cloud by vm.cloudLogin.collectAsStateWithLifecycle()
    var region by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importKey(uri)
    }
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Chave salva") {
            if (saved == null) {
                Text("Nenhuma chave salva.")
            } else {
                Text("Chave: $saved", fontFamily = FontFamily.Monospace)
                Text(
                    "Guardada cifrada com o Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { vm.deleteKey() }) { Text("Apagar chave") }
            }
        }

        SectionCard("Digitar a chave") {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Auth key (32 caracteres hex)") },
                placeholder = { Text("0x0123456789abcdef0123456789abcdef") },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: "${input.filterNot { it.isWhitespace() }.removePrefix("0x").length}/32") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Mostrar chave")
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    error = vm.saveKey(input)
                    if (error == null) input = ""
                }) { Text("Salvar") }
            }
        }

        SectionCard("Buscar na conta Xiaomi") {
            CloudLoginSection(cloud, region, onRegion = { region = it }, vm = vm)
        }

        // As chaves achadas (na conta ou em arquivo) aparecem aqui.
        candidates?.let { list -> CandidatesCard(list, vm) }

        SectionCard("Importar de um arquivo") {
            Text(
                "Escolha um log do Mi Fitness (.log/.txt), o manifest.sqlite ou um ZIP da pasta do Mi Fitness. " +
                    "O app procura os campos encryptKey/token/authKey/huamiAuthKey e as entradas " +
                    "registerList_<região> de todas as regiões.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Escolher arquivo") }
        }

        SectionCard("Como obter a chave") {
            Text(
                "A chave é criada quando a banda é vinculada à sua conta no Mi Fitness. Formas conhecidas " +
                    "(documentação do Gadgetbridge e do miband-hack):",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "• Android: logs do Mi Fitness em Android/data/com.mi.health/files/log/ (ou com.xiaomi.wearable), " +
                    "campos encryptKey, token, authKey ou huamiAuthKey. Use a última que aparecer no arquivo.\n" +
                    "• iPhone/iPad: pasta do Mi Fitness → MHWCahe/…/VirtualDevice_registerList/manifest.sqlite, " +
                    "entradas registerList_<região> (ex.: registerList_de, registerList_sg).\n" +
                    "• No Android 11+ a pasta Android/data não aparece para outros apps: copie o log pelo computador " +
                    "(USB/adb) ou por um gerenciador de arquivos que tenha acesso.\n" +
                    "• Não desvincule a banda no Mi Fitness: isso invalida a chave. Um reset de fábrica também.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun CandidatesCard(list: List<KeyCandidate>, vm: MainViewModel) {
    SectionCard("Chaves encontradas") {
        if (list.isEmpty()) {
            Text("Nenhuma chave encontrada.", color = MaterialTheme.colorScheme.error)
        } else {
            Text("Encontradas ${list.size}. Toque na que quer usar:", style = MaterialTheme.typography.bodyMedium)
            list.forEach { c ->
                ListItem(
                    modifier = Modifier.clickable { vm.useCandidate(c) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    headlineContent = { Text(AuthKey.mask(c.key), fontFamily = FontFamily.Monospace) },
                    supportingContent = {
                        Text(
                            c.source + if (c.recommended) " • campo conhecido" else " • confira",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }
        }
        TextButton(onClick = { vm.dismissCandidates() }) { Text("Fechar") }
    }
}

@Composable
private fun CloudLoginSection(
    state: MainViewModel.CloudLoginState,
    region: String?,
    onRegion: (String?) -> Unit,
    vm: MainViewModel,
) {
    val context = LocalContext.current
    Text(
        "O app abre o login da própria Xiaomi (por QR code ou pelo navegador) e lê, na sua conta do Mi Fitness, " +
            "a chave da banda. O app nunca vê a sua senha, a sessão fica só na memória e é descartada, " +
            "e nada vai para o log. Este recurso usa a internet (só ele).",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Ainda não foi confirmado com uma conta real (o miband-hack, de onde o fluxo foi portado, também avisa isso). " +
            "Se falhar, a aba Logs mostra em que passo parou.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (state) {
        is MainViewModel.CloudLoginState.Idle, is MainViewModel.CloudLoginState.Failed -> {
            if (state is MainViewModel.CloudLoginState.Failed) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
            RegionPicker(region, onRegion)
            Button(onClick = { vm.startCloudLogin(region) }) { Text("Entrar na conta Xiaomi") }
        }
        is MainViewModel.CloudLoginState.Working -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(state.step)
            }
            OutlinedButton(onClick = { vm.cancelCloudLogin() }) { Text("Cancelar") }
        }
        is MainViewModel.CloudLoginState.WaitingForLogin -> {
            Text(
                "Entre com a MESMA conta Xiaomi do Mi Fitness. Opção 1: escaneie o QR com a câmera de outro celular. " +
                    "Opção 2: toque no botão abaixo e entre pelo navegador deste celular. Depois volte para este app.",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.qrImage?.let { bytes ->
                val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "QR code de login da Xiaomi",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(220.dp).background(Color.White).padding(8.dp),
                    )
                }
            }
            if (XiaomiCloud.isXiaomiUrl(state.loginUrl)) {
                Button(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.loginUrl)))
                    } catch (e: ActivityNotFoundException) {
                        vm.log.warn("Nenhum navegador para abrir a página de login")
                    }
                }) { Text("Abrir a página de login neste celular") }
            }
            val secondsLeft by produceState(0L, state.expiresAtMillis) {
                while (true) {
                    value = ((state.expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    delay(1000)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Esperando você confirmar o login… QR válido por mais %d:%02d".format(secondsLeft / 60, secondsLeft % 60),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.startCloudLogin(region) }) { Text("Gerar QR novo") }
                OutlinedButton(onClick = { vm.cancelCloudLogin() }) { Text("Cancelar") }
            }
        }
    }
}

@Composable
private fun RegionPicker(region: String?, onRegion: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("Região da conta: ${region?.uppercase() ?: "automática"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Automática") }, onClick = { onRegion(null); open = false })
            XiaomiCloud.REGIONS.forEach { r ->
                DropdownMenuItem(text = { Text(r.uppercase()) }, onClick = { onRegion(r); open = false })
            }
        }
    }
}
