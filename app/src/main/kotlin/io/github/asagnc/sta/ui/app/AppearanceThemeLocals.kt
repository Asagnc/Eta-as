package io.github.asagnc.sta.ui.app

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import io.github.asagnc.sta.data.model.AppearanceSettings
import io.github.asagnc.sta.data.model.AppearanceTopBarBlurStyle

internal val LocalAppearanceSettings = staticCompositionLocalOf { AppearanceSettings() }
internal val LocalBlurEnabled = staticCompositionLocalOf { true }
internal val LocalTopBarBlurStyle = staticCompositionLocalOf { AppearanceTopBarBlurStyle.GAUSSIAN }
internal val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }
