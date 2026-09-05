package com.booxin.launcher.core.uiplugin

data class UiPluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val type: String = "ui",
    val description: String = "",
    /** When true, home title becomes 早上/中午/晚上好 by local time. */
    val homeGreetingByTime: Boolean = false,
    /** When true, top-nav tab switches and enter/exit game use horizontal slide transitions. */
    val pageSlideTransitions: Boolean = false,
    /** When true, home left hero shows a 3D model instead of the welcome text block. */
    val home3dModel: Boolean = false,
    /** Relative path under the install dir, e.g. models/hero.glb */
    val modelFile: String = "",
    /** When true, home brand ImageView uses [launcherIcon] from the plugin pack. */
    val customLauncherIcon: Boolean = false,
    /** Relative path under the install dir, e.g. icon.png */
    val launcherIcon: String = "",
    /** When true, in-app text uses [fontFile] (.ttf / .otf). */
    val customFont: Boolean = false,
    /** Relative path under the install dir, e.g. fonts/MyFont.ttf */
    val fontFile: String = "",
    /** When true, in-game on-screen controls use [layoutFile] from this pack. */
    val controlLayout: Boolean = false,
    /** Relative path under the install dir, e.g. control_layout.json */
    val layoutFile: String = "",
    /**
     * Extra control panels (FCL-style view groups), switchable in-game.
     * Main layout stays [layoutFile]; these are alternate overlays.
     */
    val extraLayouts: List<UiPluginExtraLayout> = emptyList(),
    /**
     * Master visual pack: brand / colors / nav / wallpaper / welcome.
     * Also unlocks icon + font assets from the same pack when present.
     */
    val customTheme: Boolean = false,
    val theme: UiPluginThemeSpec = UiPluginThemeSpec(),
    /**
     * Declares intent to use the allowlisted JS bridge inside plugin pages.
     * Runtime still requires [UiPluginPermissionStore] user grant.
     */
    val jsCommands: Boolean = false,
    /** When true (or [pages] non-empty), plugin may contribute launcher-hosted pages. */
    val customPages: Boolean = false,
    /** Extra pages hosted by the launcher WebView (HTML under the plugin dir). */
    val pages: List<UiPluginPageSpec> = emptyList()
)

data class UiPluginExtraLayout(
    val id: String,
    val name: String,
    /** Relative path under the plugin install dir, e.g. control_layout_mode.json */
    val file: String
)

data class UiPluginPageSpec(
    val id: String,
    val title: String,
    /** Relative HTML path under the plugin install dir, e.g. pages/home.html */
    val entry: String = "",
    /**
     * Optional https URL for an in-app WebView shell (e.g. forum).
     * When set, the launcher loads this URL directly instead of local HTML.
     */
    val url: String = "",
    /** portrait | landscape | unspecified */
    val orientation: String = "",
    /** Hide plugin page chrome and use immersive layout. */
    val fullscreen: Boolean = false
) {
    fun remoteHttpsUrl(): String? {
        val raw = url.trim()
        if (raw.isEmpty()) return null
        val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank()) return null
        return raw
    }

    fun normalizedOrientation(): String =
        when (orientation.trim().lowercase()) {
            "portrait", "vertical", "竖屏" -> "portrait"
            "landscape", "horizontal", "横屏" -> "landscape"
            else -> ""
        }
}

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
    val enabled: Boolean,
    /** Marketplace id when installed from the plugin store. */
    val storePluginId: String? = null,
    /** Marketplace version recorded at install/update time. */
    val storeVersion: String? = null
)

data class UiPluginResolvedTheme(
    val install: UiPluginInstall,
    val spec: UiPluginThemeSpec,
    val iconFile: java.io.File? = null,
    val fontFile: java.io.File? = null,
    val backgroundImage: java.io.File? = null,
    val backgroundVideo: java.io.File? = null
)
