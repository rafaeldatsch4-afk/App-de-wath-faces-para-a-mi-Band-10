package io.github.rafaeldatsch4afk.mibandfaces.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rafaeldatsch4afk.mibandfaces.MainViewModel
import io.github.rafaeldatsch4afk.mibandfaces.TransportKind
import io.github.rafaeldatsch4afk.mibandfaces.band.BandState
import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.FoundDevice

@Composable
fun ConnectionScreen(vm: MainViewModel, gate: PermissionGate) {
    val state by vm.client.state.collectAsStateWithLifecycle()
    val devices by vm.scanner.devices.collectAsStateWithLifecycle()
    val scanning by vm.scanner.scanning.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val transport by vm.transport.collectAsStateWithLifecycle()
    var showAll by rememberSaveable { mutableStateOf(false) }

    val list = devices.values
        .filter { showAll || it.isMiBand10 }
        .sortedWith(compareByDescending<FoundDevice> { it.isMiBand10 }.thenByDescending { it.rssi ?: -999 })
    val selectedInfo = selected?.let { devices[it.address] ?: it }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.size(4.dp)) }
        item { StatusCard(vm, state) }

        item {
            SectionCard("Banda") {
                if (selectedInfo == null) {
                    Text("Nenhuma banda escolhida. Busque e toque na sua Mi Band 10.")
                } else {
                    LabeledValue("Nome", selectedInfo.name ?: "(sem nome)")
                    LabeledValue("Endereço", selectedInfo.address)
                    LabeledValue("Pareada no Android", if (selectedInfo.bonded) "sim" else "não")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scanning) {
                        OutlinedButton(onClick = { vm.stopScan() }) { Text("Parar busca") }
                    } else {
                        Button(onClick = { gate.runWithPermission { vm.startScan() } }) { Text("Buscar") }
                    }
                    if (selectedInfo != null && !selectedInfo.bonded) {
                        OutlinedButton(onClick = { gate.runWithPermission { vm.bond() } }) { Text("Parear") }
                    }
                }
                val connected = state is BandState.Connected
                val connecting = state is BandState.Connecting
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connected || connecting) {
                        OutlinedButton(onClick = { vm.disconnect() }) { Text("Desconectar") }
                    } else {
                        Button(
                            enabled = selectedInfo != null,
                            onClick = { gate.runWithPermission { vm.connect() } },
                        ) { Text("Conectar") }
                    }
                }
            }
        }

        item {
            SectionCard("Transporte") {
                TransportKind.entries.forEach { kind ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = transport == kind, onClick = { vm.setTransport(kind) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = transport == kind, onClick = { vm.setTransport(kind) })
                        Text(kind.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "O Gadgetbridge conecta a Mi Band 10 por Bluetooth Clássico (SPP). O BLE usa o mesmo " +
                        "protocolo sobre GATT e fica como alternativa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Aparelhos encontrados", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Todos", style = MaterialTheme.typography.bodySmall)
                Switch(checked = showAll, onCheckedChange = { showAll = it })
            }
        }
        if (list.isEmpty()) {
            item {
                Text(
                    if (scanning) "Procurando…" else "Nada encontrado ainda. Deixe a banda perto e com a tela ligada.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(list, key = { it.address }) { device ->
            DeviceRow(device, selected = device.address == selected?.address) { vm.select(device) }
        }

        item {
            SectionCard("Antes de conectar") {
                Text(
                    "• A banda precisa estar vinculada à sua conta no Mi Fitness (é de lá que vem a chave).\n" +
                        "• Não desvincule a banda no Mi Fitness: isso invalida a chave.\n" +
                        "• Feche o Mi Fitness (ou tire a permissão de Bluetooth dele) para ele não disputar a conexão.\n" +
                        "• No Bluetooth Clássico a banda precisa estar pareada com o Android (botão \"Parear\").",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Spacer(Modifier.size(16.dp)) }
    }
}

@Composable
private fun StatusCard(vm: MainViewModel, state: BandState) {
    SectionCard("Status") {
        val (text, busy) = when (state) {
            BandState.Disconnected -> "Desconectado" to false
            is BandState.Connecting -> state.step to true
            is BandState.Connected -> "Conectado por ${state.transport}" to false
            is BandState.Failed -> "Erro: ${state.message}" to false
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                color = if (state is BandState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (!vm.hasBluetooth) WarningBox("Este aparelho não tem Bluetooth.")
        else if (!vm.bluetoothEnabled) WarningBox("O Bluetooth está desligado.")
    }
}

@Composable
private fun DeviceRow(device: FoundDevice, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        leadingContent = {
            Icon(
                when {
                    device.isMiBand10 -> Icons.Filled.Watch
                    device.bonded -> Icons.Filled.BluetoothConnected
                    else -> Icons.Filled.Bluetooth
                },
                contentDescription = null,
            )
        },
        headlineContent = { Text(device.name ?: "(sem nome)") },
        supportingContent = {
            val via = listOfNotNull("BLE".takeIf { device.seenByBle }, "Clássico".takeIf { device.seenByClassic })
            Column {
                Text(device.address, style = MaterialTheme.typography.bodySmall)
                Text(
                    buildString {
                        append(if (device.bonded) "pareada" else "não pareada")
                        if (via.isNotEmpty()) append(" • visto por ${via.joinToString("/")}")
                        device.rssi?.let { append(" • $it dBm") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}
