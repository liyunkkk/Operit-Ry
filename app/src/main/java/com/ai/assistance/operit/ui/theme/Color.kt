package com.ai.assistance.operit.ui.theme

import androidx.compose.ui.graphics.Color

// RainyToken / Bongocat: pale sakura surfaces, white panels and rose accents.
val RainyPink = Color(0xFFFF85A2)
val RainyPinkHover = Color(0xFFFF6B8E)
val RainySakura = Color(0xFFFFB3C6)
val RainyRose = Color(0xFFE91E63)
val RainyAccent = Color(0xFF9F2854)
val RainySecondary = Color(0xFF795064)

// The soft light accents used while "enhanced contrast" is off (the default). They stay in the
// sakura family instead of berry, yet still clear 3:1 as accent text on the pink card fill
// (RainyLightHover) and carry white icons at about 4.3:1.
val RainySoftRose = Color(0xFFCA5079)
val RainySoftMauve = Color(0xFF9E6A82)
val RainySoftCoral = Color(0xFFC24C72)

/** The light app bar and status bar: a sakura band that separates from the paler page. */
val RainyLightAppBar = Color(0xFFFFDDE6)

val RainyLightBackground = Color(0xFFFFF8FA)
val RainyLightPanel = Color(0xFFFFFFFF)
val RainyLightElement = Color(0xFFFFF1F5)
val RainyLightHover = Color(0xFFFFD1DC)
val RainyLightText = Color(0xFF392B33)
val RainyLightMuted = Color(0xFF806371)
val RainyLightBorder = Color(0xFFAA8193)
val RainyLightDivider = Color(0xFFEFD8E1)

val RainyDarkBackground = Color(0xFF1F1419)
val RainyDarkPanel = Color(0xFF2A1F25)
val RainyDarkElement = Color(0xFF3D2A33)
val RainyDarkText = Color(0xFFEFE0E5)
val RainyDarkMuted = Color(0xFFC9B8BE)
val RainyDarkBorder = Color(0xFF4D3A42)

val RainySuccess = Color(0xFF66BB6A)
val RainyWarning = Color(0xFFFFA726)
val RainyInfo = Color(0xFF64B5F6)

/**
 * The warning hue kept legible as small text on the light background. RainyWarning is a decorative
 * orange with too little contrast there to be read as a label, and a stopped run's label also lands
 * on the selected row's tinted container, which is the darkest surface it is drawn on.
 */
val RainyWarningTextLight = Color(0xFF963F12)
