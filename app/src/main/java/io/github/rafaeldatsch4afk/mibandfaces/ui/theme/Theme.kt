package io.github.rafaeldatsch4afk.mibandfaces.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Laranja = Color(0xFFFF8A3D)
private val LaranjaEscuro = Color(0xFF8A3B00)

private val Escuro = darkColorScheme(
    primary = Laranja,
    onPrimary = Color(0xFF2B1300),
    primaryContainer = LaranjaEscuro,
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = Color(0xFF8FCBFF),
    background = Color(0xFF111214),
    surface = Color(0xFF111214),
    surfaceVariant = Color(0xFF26282C),
    surfaceContainer = Color(0xFF1B1C1F),
    error = Color(0xFFFF6B6B),
)

private val Claro = lightColorScheme(
    primary = LaranjaEscuro,
    secondary = Color(0xFF00629B),
)

/** Tema do app. Escuro por padrão. */
@Composable
fun MiBandFacesTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Escuro else Claro, content = content)
}
