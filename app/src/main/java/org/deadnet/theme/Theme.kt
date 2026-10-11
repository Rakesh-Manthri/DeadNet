package org.deadnet.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DeadNetBlueDark,
    secondary = DeadNetTealDark,
    tertiary = DeadNetGreenLight,
    background = Color(0xFF0C1017),
    surface = Color(0xFF141A24),
    surfaceVariant = Color(0xFF1E2636),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFF8B949E),
    outline = Color(0xFF30363D)
)

private val LightColorScheme = lightColorScheme(
    primary = DeadNetBlueLight,
    secondary = DeadNetTealLight,
    tertiary = DeadNetGreen,
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEAEFF5),
    onBackground = Color(0xFF1F2328),
    onSurface = Color(0xFF1F2328),
    onSurfaceVariant = Color(0xFF57606A),
    outline = Color(0xFFD0D7DE)
)

@Composable
fun DeadNetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Set false to ensure consistent tactical DeadNet styling
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
