package io.github.rafaeldatsch4afk.mibandfaces

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rafaeldatsch4afk.mibandfaces.ui.MainScreen
import io.github.rafaeldatsch4afk.mibandfaces.ui.theme.MiBandFacesTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark by viewModel.darkTheme.collectAsStateWithLifecycle()
            MiBandFacesTheme(dark = dark) {
                MainScreen(viewModel)
            }
        }
    }
}
