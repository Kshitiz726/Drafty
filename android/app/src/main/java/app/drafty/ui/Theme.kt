package app.drafty.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.drafty.data.Prefs
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFD97757)
val AccentSoft = Color(0x1FD97757)
val Success = Color(0xFF34C759)
val Danger = Color(0xFFD1453B)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBE9E3),
    onPrimaryContainer = Color(0xFF5A2516),
    secondaryContainer = Color(0xFFF5EDE8),
    background = Color(0xFFFAF8F5),
    onBackground = Color(0xFF1D1D1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFF3F0EC),
    onSurfaceVariant = Color(0xFF6E6E73),
    surfaceContainer = Color(0xFFF3F0EC),
    outline = Color(0xFFE2DDD6),
    outlineVariant = Color(0xFFEAE6E0),
)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A2418),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondaryContainer = Color(0xFF2E2A28),
    background = Color(0xFF141517),
    onBackground = Color(0xFFECECEE),
    surface = Color(0xFF1E2023),
    onSurface = Color(0xFFECECEE),
    surfaceVariant = Color(0xFF26292D),
    onSurfaceVariant = Color(0xFF9A9CA3),
    surfaceContainer = Color(0xFF1E2023),
    outline = Color(0xFF34373C),
    outlineVariant = Color(0xFF2C2F33),
)

private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

/** App-wide theme mode: "system", "light" or "dark". Changes apply instantly everywhere. */
object ThemeMode {
    var mode by mutableStateOf(Prefs.theme)
    fun set(v: String) { mode = v; Prefs.theme = v }
}

@Composable
fun isDark(): Boolean = when (ThemeMode.mode) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun DraftyTheme(content: @Composable () -> Unit) {
    val dark = isDark()
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
    }
    val scheme = if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = Type) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
            Box(Modifier.fillMaxSize().background(scheme.background)) { content() }
        }
    }
}
