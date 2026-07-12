package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * TowerLock semantic color tokens. Every screen consumes these via [TlTheme.colors]
 * instead of hardcoded hex values, so the whole app re-skins itself when the system
 * switches between light and dark mode.
 */
@Immutable
data class TlColors(
  /** Page background behind all content. */
  val background: Color,
  /** Cards, sheets, dialogs, top bar. */
  val surface: Color,
  /** Dividers, secondary surfaces, inactive track fills. */
  val surfaceVariant: Color,
  /** Borders and disabled controls. */
  val outline: Color,
  val textPrimary: Color,
  val textSecondary: Color,
  val textMuted: Color,
  /** Primary brand accent / "excellent" signal. */
  val emerald: Color,
  /** Info accent (distance, bearing, links). */
  val sky: Color,
  /** Warnings / "fair" signal. */
  val amber: Color,
  /** Destructive actions / errors. */
  val red: Color,
  /** TA ring / distance accent. */
  val pink: Color,
  /** Text/icons drawn on top of saturated accent fills (badges, pills, buttons). */
  val onAccent: Color,
  // Signal-grade scale (RSRP)
  val signalExcellent: Color,
  val signalGood: Color,
  val signalFair: Color,
  val signalPoor: Color,
  /** Container tint for the currently serving tower card. */
  val servingContainer: Color,
  /** Container tint for the nearest-tower card. */
  val nearestContainer: Color,
)

/** Original "mission control" palette (slate + emerald). */
val TlDarkColors =
  TlColors(
    background = Color(0xFF0F172A), // slate-900
    surface = Color(0xFF1E293B), // slate-800
    surfaceVariant = Color(0xFF334155), // slate-700
    outline = Color(0xFF475569), // slate-600
    textPrimary = Color(0xFFF8FAFC), // slate-50
    textSecondary = Color(0xFF94A3B8), // slate-400
    textMuted = Color(0xFF64748B), // slate-500
    emerald = Color(0xFF10B981),
    sky = Color(0xFF38BDF8),
    amber = Color(0xFFF59E0B),
    red = Color(0xFFEF4444),
    pink = Color(0xFFEC4899),
    onAccent = Color.White,
    signalExcellent = Color(0xFF4CAF50),
    signalGood = Color(0xFF8BC34A),
    signalFair = Color(0xFFFFB74D),
    signalPoor = Color(0xFFE57373),
    servingContainer = Color(0xFF1E3A8A), // deep navy
    nearestContainer = Color(0xFF064E3B), // forest green
  )

/** Light companion palette: same hue families, tuned for contrast on light surfaces. */
val TlLightColors =
  TlColors(
    background = Color(0xFFF1F5F9), // slate-100
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E8F0), // slate-200
    outline = Color(0xFFCBD5E1), // slate-300
    textPrimary = Color(0xFF0F172A), // slate-900
    textSecondary = Color(0xFF475569), // slate-600
    textMuted = Color(0xFF64748B), // slate-500
    emerald = Color(0xFF059669),
    sky = Color(0xFF0284C7),
    amber = Color(0xFFD97706),
    red = Color(0xFFDC2626),
    pink = Color(0xFFDB2777),
    onAccent = Color.White,
    signalExcellent = Color(0xFF15803D),
    signalGood = Color(0xFF65A30D),
    signalFair = Color(0xFFD97706),
    signalPoor = Color(0xFFDC2626),
    servingContainer = Color(0xFFDBEAFE), // blue-100
    nearestContainer = Color(0xFFD1FAE5), // emerald-100
  )

val LocalTlColors = staticCompositionLocalOf { TlDarkColors }
