package com.booxin.launcher.core.uiplugin

import android.graphics.Color
import com.booxin.launcher.core.LauncherPaths
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Lightweight UI plugin packs (zip + booxin-plugin.json), separate from GL renderer plugins.
 */
object UiPluginManager {

    fun root(): File = File(LauncherPaths.runtimeDir, "ui-plugins").also { it.mkdirs() }

    fun listInstalled(): List<UiPluginInstall> {
        val root = root()
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val manifest = readManifest(File(dir, "booxin-plugin.json")) ?: return@mapNotNull null
                UiPluginInstall(
                    manifest = manifest,
                    dir = dir,
                    enabled = !File(dir, ".disabled").isFile
                )
            }
            .orEmpty()
            .sortedBy { it.manifest.name.lowercase() }
    }

    fun isFeatureEnabled(feature: String): Boolean {
        return listInstalled().any { install ->
            if (!install.enabled) return@any false
            val m = install.manifest
            when (feature) {
                "homeGreetingByTime" ->
                    m.homeGreetingByTime ||
                        (m.customTheme && hasAnyWelcomeOverride(m.theme))
                "customLauncherIcon" -> m.customLauncherIcon || m.customTheme
                "customFont" -> m.customFont || m.customTheme
                "customTheme" -> m.customTheme
                else -> false
            }
        }
    }

    fun resolveActiveTheme(): UiPluginResolvedTheme? {
        for (install in listInstalled()) {
            if (!install.enabled || !install.manifest.customTheme) continue
            return UiPluginResolvedTheme(
                install = install,
                spec = install.manifest.theme,
                iconFile = resolveIconFor(install, requireFeature = false),
                fontFile = resolveFontFor(install, requireFeature = false),
                backgroundImage = resolveAssetFile(
                    install,
                    declared = install.manifest.theme.backgroundImage,
                    defaults = listOf(
                        "backgrounds/bg.png",
                        "backgrounds/bg.webp",
                        "backgrounds/bg.jpg",
                        "background.png",
                        "bg.png",
                        "assets/background.png"
                    ),
                    allowedExt = setOf("png", "webp", "jpg", "jpeg")
                ),
                backgroundVideo = resolveAssetFile(
                    install,
                    declared = install.manifest.theme.backgroundVideo,
                    defaults = listOf(
                        "backgrounds/loop.mp4",
                        "backgrounds/bg.mp4",
                        "background.mp4",
                        "bg.mp4",
                        "assets/background.mp4"
                    ),
                    allowedExt = setOf("mp4", "webm", "mkv")
                )
            )
        }
        return null
    }

    /**
     * First enabled plugin with customLauncherIcon / customTheme and a readable image file.
     * Does not change the Android system launcher icon — only in-app brand mark.
     */
    fun resolveCustomLauncherIconFile(): File? {
        for (install in listInstalled()) {
            if (!install.enabled) continue
            if (!install.manifest.customLauncherIcon && !install.manifest.customTheme) continue
            resolveIconFor(install, requireFeature = false)?.let { return it }
        }
        return null
    }

    /**
     * First enabled plugin with customFont / customTheme and a readable .ttf/.otf.
     */
    fun resolveCustomFontFile(): File? {
        for (install in listInstalled()) {
            if (!install.enabled) continue
            if (!install.manifest.customFont && !install.manifest.customTheme) continue
            resolveFontFor(install, requireFeature = false)?.let { return it }
        }
        return null
    }

    private fun resolveIconFor(install: UiPluginInstall, requireFeature: Boolean): File? {
        if (requireFeature &&
            !install.manifest.customLauncherIcon &&
            !install.manifest.customTheme
        ) {
            return null
        }
        return resolveAssetFile(
            install,
            declared = install.manifest.launcherIcon,
            defaults = listOf(
                "icon.png",
                "icon.webp",
                "icon.jpg",
                "icon.jpeg",
                "launcher-icon.png",
                "assets/icon.png"
            )
        )
    }

    private fun resolveFontFor(install: UiPluginInstall, requireFeature: Boolean): File? {
        if (requireFeature && !install.manifest.customFont && !install.manifest.customTheme) {
            return null
        }
        return resolveAssetFile(
            install,
            declared = install.manifest.fontFile,
            defaults = listOf(
                "font.ttf",
                "font.otf",
                "fonts/font.ttf",
                "fonts/font.otf",
                "assets/font.ttf",
                "assets/font.otf"
            ),
            allowedExt = setOf("ttf", "otf", "ttc")
        )
    }

    private fun resolveAssetFile(
        install: UiPluginInstall,
        declared: String,
        defaults: List<String>,
        allowedExt: Set<String>? = null
    ): File? {
        val candidates = buildList {
            val path = declared.trim().trimStart('/', '\\')
            if (path.isNotEmpty() && ".." !in path.replace('\\', '/')) {
                add(path)
            }
            addAll(defaults)
        }
        val root = runCatching { install.dir.canonicalFile }.getOrElse { install.dir }
        for (rel in candidates) {
            val f = File(install.dir, rel.replace('\\', '/'))
            val canonical = runCatching { f.canonicalFile }.getOrElse { f }
            val underRoot = canonical.path.startsWith(root.path + File.separator) ||
                canonical.path == root.path
            if (!canonical.isFile || canonical.length() <= 0L || !underRoot) continue
            if (allowedExt != null) {
                val ext = canonical.extension.lowercase()
                if (ext !in allowedExt) continue
            }
            return canonical
        }
        return null
    }

    fun installFromZip(zip: File): Result<UiPluginInstall> = runCatching {
        require(zip.isFile && zip.length() > 0L) { "无效的插件包" }
        ZipFile(zip).use { zf ->
            val manifestEntry = zf.getEntry("booxin-plugin.json")
                ?: zf.entries().asSequence().firstOrNull {
                    !it.isDirectory && it.name.replace('\\', '/').endsWith("booxin-plugin.json")
                }
                ?: error("缺少 booxin-plugin.json")
            val manifestJson = zf.getInputStream(manifestEntry).bufferedReader().readText()
            val manifest = parseManifest(JSONObject(manifestJson))
            val dest = File(root(), sanitizeId(manifest.id))
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()

            val stripPrefix = manifestEntry.name.replace('\\', '/')
                .removeSuffix("booxin-plugin.json")

            zf.entries().asSequence().forEach { e ->
                if (e.isDirectory) return@forEach
                val full = e.name.replace('\\', '/').trimStart('/')
                if (".." in full) return@forEach
                val relative = if (stripPrefix.isNotEmpty() && full.startsWith(stripPrefix)) {
                    full.removePrefix(stripPrefix)
                } else {
                    full.substringAfterLast('/')
                        .takeIf { full == it || !full.contains('/') }
                        ?: full
                }
                if (relative.isBlank() || ".." in relative) return@forEach
                val target = File(dest, relative)
                target.parentFile?.mkdirs()
                zf.getInputStream(e).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val rootManifest = File(dest, "booxin-plugin.json")
            if (!rootManifest.isFile) {
                rootManifest.writeText(manifestJson)
            }
            File(dest, ".ready").writeText(System.currentTimeMillis().toString())
            onPluginsChanged()
            UiPluginInstall(manifest = manifest, dir = dest, enabled = true)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val dir = File(root(), sanitizeId(id))
        val marker = File(dir, ".disabled")
        if (enabled) marker.delete() else marker.writeText("1")
        onPluginsChanged()
    }

    fun uninstall(id: String) {
        File(root(), sanitizeId(id)).deleteRecursively()
        onPluginsChanged()
    }

    private fun onPluginsChanged() {
        UiPluginFonts.invalidate()
        UiPluginTheme.invalidate()
    }

    private fun sanitizeId(id: String): String =
        id.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "ui-plugin" }

    private fun readManifest(file: File): UiPluginManifest? {
        if (!file.isFile) return null
        return runCatching { parseManifest(JSONObject(file.readText())) }.getOrNull()
    }

    private fun parseManifest(o: JSONObject): UiPluginManifest {
        val features = o.optJSONObject("features")
        val launcherIcon = firstNonBlank(
            o.optString("launcherIcon"),
            o.optString("icon"),
            features?.optString("launcherIcon"),
            features?.optString("icon")
        )
        val fontFile = firstNonBlank(
            o.optString("font"),
            o.optString("fontFile"),
            features?.optString("font"),
            features?.optString("fontFile")
        )
        val themeObj = o.optJSONObject("theme")
        val customTheme = features?.optBoolean("customTheme") == true ||
            o.optBoolean("customTheme") ||
            themeObj != null
        return UiPluginManifest(
            id = o.optString("id").ifBlank { error("plugin id 为空") },
            name = o.optString("name").ifBlank { o.optString("id") },
            version = o.optString("version", "1.0.0"),
            type = o.optString("type", "ui"),
            description = o.optString("description"),
            homeGreetingByTime = features?.optBoolean("homeGreetingByTime") == true ||
                o.optBoolean("homeGreetingByTime"),
            customLauncherIcon = features?.optBoolean("customLauncherIcon") == true ||
                o.optBoolean("customLauncherIcon"),
            launcherIcon = launcherIcon,
            customFont = features?.optBoolean("customFont") == true ||
                o.optBoolean("customFont"),
            fontFile = fontFile,
            customTheme = customTheme,
            theme = parseTheme(themeObj)
        )
    }

    private fun parseTheme(o: JSONObject?): UiPluginThemeSpec {
        if (o == null) return UiPluginThemeSpec()
        val colors = o.optJSONObject("colors")
        val nav = o.optJSONObject("nav")
        val alphaRaw = o.optDouble("backgroundAlpha", Double.NaN)
        val alpha = alphaRaw.takeIf { !it.isNaN() }?.toFloat()?.coerceIn(0f, 1f)
        return UiPluginThemeSpec(
            brandName = o.optString("brandName").trim(),
            welcome = o.optString("welcome").trim(),
            welcomeMorning = o.optString("welcomeMorning").trim(),
            welcomeNoon = o.optString("welcomeNoon").trim(),
            welcomeEvening = o.optString("welcomeEvening").trim(),
            backgroundImage = firstNonBlank(
                o.optString("backgroundImage"),
                o.optString("background")
            ),
            backgroundVideo = o.optString("backgroundVideo").trim(),
            backgroundAlpha = alpha,
            hideOrbs = o.optBoolean("hideOrbs", false),
            applyAllText = if (o.has("applyAllText")) o.optBoolean("applyAllText", true) else true,
            homeLaunchText = firstNonBlank(
                o.optString("homeLaunchText"),
                o.optString("launchText")
            ),
            homeSwitchVersionText = firstNonBlank(
                o.optString("homeSwitchVersionText"),
                o.optString("switchVersionText")
            ),
            homeAccountText = firstNonBlank(
                o.optString("homeAccountText"),
                o.optString("accountText")
            ),
            homeStatusText = firstNonBlank(
                o.optString("homeStatusText"),
                o.optString("statusText")
            ),
            homeSelectedVersionLabel = firstNonBlank(
                o.optString("homeSelectedVersionLabel"),
                o.optString("selectedVersionLabel")
            ),
            colors = UiPluginThemeColors(
                primary = parseColor(colors?.optString("primary")),
                accent = parseColor(colors?.optString("accent")),
                text = parseColor(colors?.optString("text")),
                textSecondary = parseColor(colors?.optString("textSecondary")),
                rim = parseColor(colors?.optString("rim")),
                glassPrimary = parseColor(
                    colors?.optString("glassPrimary") ?: colors?.optString("button")
                ),
                surface = parseColor(colors?.optString("surface")),
                navSelected = parseColor(colors?.optString("navSelected")),
                navUnselected = parseColor(colors?.optString("navUnselected"))
            ),
            nav = UiPluginNavLabels(
                home = nav?.optString("home").orEmpty().trim(),
                versions = nav?.optString("versions").orEmpty().trim(),
                community = nav?.optString("community").orEmpty().trim(),
                multiplayer = nav?.optString("multiplayer").orEmpty().trim(),
                ai = nav?.optString("ai").orEmpty().trim(),
                pluginStore = firstNonBlank(
                    nav?.optString("pluginStore"),
                    nav?.optString("plugins"),
                    nav?.optString("plugin")
                ),
                settings = nav?.optString("settings").orEmpty().trim()
            )
        )
    }

    private fun hasAnyWelcomeOverride(theme: UiPluginThemeSpec): Boolean =
        theme.welcome.isNotBlank() ||
            theme.welcomeMorning.isNotBlank() ||
            theme.welcomeNoon.isNotBlank() ||
            theme.welcomeEvening.isNotBlank()

    private fun parseColor(raw: String?): Int? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return runCatching {
            val hex = if (s.startsWith("#")) s else "#$s"
            Color.parseColor(hex)
        }.getOrNull()
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.asSequence().map { it?.trim().orEmpty() }.firstOrNull { it.isNotEmpty() }.orEmpty()
}
