package com.example.ui.theme

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeManager {
    private const val PREFS_NAME = "elbatal_theme_prefs"
    private const val KEY_IS_DARK = "is_dark_mode"

    private val _isDarkModeState = MutableStateFlow(true)
    val isDarkModeState = _isDarkModeState.asStateFlow()
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_IS_DARK)) {
            _isDarkModeState.value = true
        } else {
            _isDarkModeState.value = prefs.getBoolean(KEY_IS_DARK, true)
        }
        isInitialized = true
    }

    fun toggleTheme(context: Context) {
        setTheme(context, !_isDarkModeState.value)
    }

    fun setTheme(context: Context, isDark: Boolean) {
        _isDarkModeState.value = isDark
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_DARK, isDark).apply()
    }
}

private val DarkColorScheme =
  darkColorScheme(
    primary = Primary,
    onPrimary = White,
    primaryContainer = Color(0xFF5C0000),
    onPrimaryContainer = Color(0xFFFFCDD2),
    secondary = Color(0xFFFF5252),
    onSecondary = White,
    background = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2B2B2B),
    onSurfaceVariant = Color(0xFFD5D5D5),
    outline = Color(0xFF555555),
    outlineVariant = Color(0xFF333333)
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Primary,
    onPrimary = White,
    primaryContainer = Color(0xFFFFEBEE),
    onPrimaryContainer = Color(0xFFB71C1C),
    secondary = Color(0xFFD32F2F),
    onSecondary = White,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF0F2F5),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFD1D5DB),
    outlineVariant = Color(0xFFE5E7EB)
  )

@Composable
fun ElBatalExpressTheme(
  darkTheme: Boolean = ThemeManager.isDarkModeState.value,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

