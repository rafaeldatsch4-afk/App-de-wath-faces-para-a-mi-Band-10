package io.github.rafaeldatsch4afk.mibandfaces.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rafaeldatsch4afk.mibandfaces.MainViewModel
import io.github.rafaeldatsch4afk.mibandfaces.key.AuthKey

@Composable
fun KeyScreen(vm: MainViewModel) {
    val saved by vm.savedKeyMasked.collectAsStateWithLifecycle()
    val candidates by vm.keyCandidates.collectAsStateWithLifecycle()
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

        SectionCard("Importar de um arquivo") {
            Text(
                "Escolha um log do Mi Fitness (.log/.txt), o manifest.sqlite ou um ZIP da pasta do Mi Fitness. " +
                    "O app procura os campos encryptKey/token/authKey/huamiAuthKey e as entradas " +
                    "registerList_<região> de todas as regiões.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Escolher arquivo") }
            candidates?.let { list ->
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
                                    c.source + if (c.recommended) " • campo conhecido" else " • hex solto, confira",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                        )
                    }
                    TextButton(onClick = { vm.dismissCandidates() }) { Text("Fechar") }
                }
            }
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
