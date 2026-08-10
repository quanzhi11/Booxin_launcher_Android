package com.booxin.launcher.core.uiplugin

data class UiPluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val type: String = "ui",
    val description: String = "",
    /** When true, home title becomes 早上/中午/晚上好 by local time. */
    val homeGreetingByTime: Boolean = false,
    /** When true, home brand ImageView uses [launcherIcon] from the plugin pack. */
    val customLauncherIcon: Boolean = false,
    /** Relative path under the install dir, e.g. icon.png */
    val launcherIcon: String = "",
    /** When true, in-app text uses [fontFile] (.ttf / .otf). */
    val customFont: Boolean = false,
    /** Relative path under the install dir, e.g. fonts/MyFont.ttf */
    val fontFile: String = "",
    /**
     * Master visual pack: brand / colors / nav / wallpaper / welcome.
     * Also unlocks icon + font assets from the same pack when present.
     */
    val customTheme: Boolean = false,
    val theme: UiPluginThemeSpec = UiPluginThemeSpec()
)

data class UiPluginThemeSpec(
    val brandName: String = "",
    val welcome: String = "",
    val welcomeMorning: String = "",
    val welcomeNoon: String = "",
    val welcomeEvening: String = "",
    val backgroundImage: String = "",
    val backgroundVideo: String = "",
    /** 0f..1f; blank/invalid → default 0.85 */
    val backgroundAlpha: Float? = null,
    /** Hide decorative glass orbs overlay when true. */
    val hideOrbs: Boolean = false,
    /** When true (default), tint most TextViews with theme text colors. */
    val applyAllText: Boolean = true,
    /** Home page label overrides. */
    val homeLaunchText: String = "",
    val homeSwitchVersionText: String = "",
    val homeAccountText: String = "",
    val homeStatusText: String = "",
    val homeSelectedVersionLabel: String = "",
    val colors: UiPluginThemeColors = UiPluginThemeColors(),
    val nav: UiPluginNavLabels = UiPluginNavLabels()
)

data class UiPluginThemeColors(
    val primary: Int? = null,
    val accent: Int? = null,
    val text: Int? = null,
    val textSecondary: Int? = null,
    val rim: Int? = null,
    val glassPrimary: Int? = null,
    val surface: Int? = null,
    val navSelected: Int? = null,
    val navUnselected: Int? = null
)

data class UiPluginNavLabels(
    val home: String = "",
    val versions: String = "",
    val community: String = "",
    val multiplayer: String = "",
    val ai: String = "",
    val pluginStore: String = "",
    val settings: String = ""
)

data class UiPluginInstall(
    val manifest: UiPluginManifest,
    val dir: java.io.File,
    val enabled: Boolean
)

data class UiPluginResolvedTheme(
    val install: UiPluginInstall,
    val spec: UiPluginThemeSpec,
    val iconFile: java.io.File? = null,
    val fontFile: java.io.File? = null,
    val backgroundImage: java.io.File? = null,
    val backgroundVideo: java.io.File? = null
)
