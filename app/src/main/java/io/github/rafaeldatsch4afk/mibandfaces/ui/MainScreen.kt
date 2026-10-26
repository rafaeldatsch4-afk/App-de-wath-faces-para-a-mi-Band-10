package io.github.rafaeldatsch4afk.mibandfaces.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rafaeldatsch4afk.mibandfaces.MainViewModel

private enum class Tab(val label: String, val icon: ImageVector) {
    CONNECTION("Conexão", Icons.Filled.Bluetooth),
    KEY("Chave", Icons.Filled.Key),
    LOGS("Logs", Icons.AutoMirrored.Filled.List),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.CONNECTION) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    val dark by vm.darkTheme.collectAsStateWithLifecycle()
    val gate = rememberPermissionGate(onDenied = { vm.log.warn("Permissões de Bluetooth negadas") })

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        vm.consumeMessage()
        snackbar.showSnackbar(text)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mi Band 10 Faces") },
                actions = {
                    IconButton(onClick = { vm.toggleTheme() }) {
                        Icon(if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode, contentDescription = "Alternar tema")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.CONNECTION -> ConnectionScreen(vm, gate)
                Tab.KEY -> KeyScreen(vm)
                Tab.LOGS -> LogScreen(vm.log)
            }
        }
    }
}
