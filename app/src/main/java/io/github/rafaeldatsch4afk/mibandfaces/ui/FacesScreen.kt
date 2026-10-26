package io.github.rafaeldatsch4afk.mibandfaces.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rafaeldatsch4afk.mibandfaces.MainViewModel
import io.github.rafaeldatsch4afk.mibandfaces.band.BandState
import io.github.rafaeldatsch4afk.mibandfaces.band.InstalledFace
import io.github.rafaeldatsch4afk.mibandfaces.band.WatchfaceManager

@Composable
fun FacesScreen(vm: MainViewModel) {
    val faces by vm.watchfaces.faces.collectAsStateWithLifecycle()
    val band by vm.client.state.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<InstalledFace?>(null) }
    val ready = band is BandState.Ready

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                faces?.let { "Faces na banda: ${it.size}/${WatchfaceManager.MAX_FACES}" } ?: "Faces na banda",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Button(enabled = ready, onClick = { vm.refreshFaces() }) { Text("Atualizar") }
        }
        if (!ready) {
            Text("Conecte a banda na aba Conexão para ver as faces.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (faces == null) {
            Text("Lendo…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(faces.orEmpty(), key = { it.id }) { face ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    leadingContent = {
                        Icon(
                            when {
                                face.active -> Icons.Filled.CheckCircle
                                !face.canDelete -> Icons.Filled.Lock
                                else -> Icons.Filled.Watch
                            },
                            contentDescription = null,
                            tint = if (face.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    headlineContent = { Text(face.name) },
                    supportingContent = {
                        Column {
                            Text(
                                buildString {
                                    append("ID ${face.id}")
                                    if (face.active) append(" • ativa")
                                    if (!face.canDelete) append(" • do sistema")
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!face.active) {
                                    OutlinedButton(enabled = ready, onClick = { vm.activateFace(face) }) { Text("Ativar") }
                                }
                                if (face.canDelete && !face.active) {
                                    OutlinedButton(enabled = ready, onClick = { toDelete = face }) { Text("Remover") }
                                }
                            }
                        }
                    },
                )
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }

    toDelete?.let { face ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remover face?") },
            text = { Text("Remover \"${face.name}\" (ID ${face.id}) da banda?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFace(face)
                    toDelete = null
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}
