package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

private fun buildColorScheme(tl: TlColors, dark: Boolean): ColorScheme {
  val base = if (dark) darkColorScheme() else lightColorScheme()
  return base.copy(
    primary = tl.emerald,
    onPrimary = tl.onAccent,
    primaryContainer = tl.nearestContainer,
    onPrimaryContainer = tl.textPrimary,
    secondary = tl.sky,
    onSecondary = tl.onAccent,
    secondaryContainer = tl.servingContainer,
    onSecondaryContainer = tl.textPrimary,
    tertiary = tl.pink,
    onTertiary = tl.onAccent,
    background = tl.background,
    onBackground = tl.textPrimary,
    surface = tl.surface,
    onSurface = tl.textPrimary,
    surfaceVariant = tl.surfaceVariant,
    onSurfaceVariant = tl.textSecondary,
    surfaceContainer = tl.surface,
    surfaceContainerHigh = tl.surfaceVariant,
    outline = tl.outline,
    outlineVariant = tl.surfaceVariant,
    error = tl.red,
    onError = tl.onAccent,
  )
}

/**
 * TowerLock's theme entry point. Follows the system light/dark setting by default so
 * the whole app — Compose screens and stock Material components alike — re-skins
 * dynamically. Screens read semantic tokens through [TlTheme.colors].
 */
@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val tlColors = if (darkTheme) TlDarkColors else TlLightColors
  CompositionLocalProvider(LocalTlColors provides tlColors) {
    MaterialTheme(
      colorScheme = buildColorScheme(tlColors, darkTheme),
      typography = Typography,
      content = content,
    )
  }
}

/** Accessor for TowerLock's semantic tokens, mirroring the MaterialTheme object style. */
object TlTheme {
  val colors: TlColors
    @Composable @ReadOnlyComposable get() = LocalTlColors.current
}

/** Theme-aware color for an RSRP reading, matching the documented grade thresholds. */
@Composable
@ReadOnlyComposable
fun signalColorForRsrp(rsrp: Int): androidx.compose.ui.graphics.Color {
  val tl = TlTheme.colors
  return when {
    rsrp >= -80 -> tl.signalExcellent
    rsrp >= -95 -> tl.signalGood
    rsrp >= -110 -> tl.signalFair
    else -> tl.signalPoor
  }
}

/** Theme-aware color for a signal grade label produced by the telephony layer. */
@Composable
@ReadOnlyComposable
fun signalColorForGrade(grade: String): androidx.compose.ui.graphics.Color {
  val tl = TlTheme.colors
  return when (grade) {
    "Excellent" -> tl.signalExcellent
    "Good" -> tl.signalGood
    "Fair" -> tl.signalFair
    else -> tl.signalPoor
  }
}
