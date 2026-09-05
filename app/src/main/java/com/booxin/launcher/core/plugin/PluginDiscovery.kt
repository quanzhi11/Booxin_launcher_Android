package com.booxin.launcher.core.plugin

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.booxin.launcher.core.runtime.RendererInstaller
import com.booxin.launcher.core.runtime.RendererPackages
import java.io.File

/**
 * Discovers plugins from builtin catalog, local extract dirs, and third-party APKs.
 */
object PluginDiscovery {

    private const val META_BOOXIN = "booxin.plugin"
    private const val META_FCL = "fclPlugin"
    private const val META_FCL_ALT = "org.lwjgl.opengl.libname"

    fun discoverAll(context: Context): List<PluginDescriptor> {
        val byId = linkedMapOf<String, PluginDescriptor>()
        fun put(d: PluginDescriptor) {
            val existing = byId[d.id]
            if (existing == null) {
                byId[d.id] = d
                return
            }
            // Prefer installed/local over catalog-only; PACKAGE over empty nativeDir.
            val score = { p: PluginDescriptor ->
                (if (p.installed) 4 else 0) +
                    (if (p.nativeDir != null) 2 else 0) +
                    (if (p.source == PluginSource.LOCAL) 1 else 0) +
                    (if (p.source == PluginSource.PACKAGE) 1 else 0)
            }
            if (score(d) >= score(existing)) byId[d.id] = d
        }

        discoverBuiltin().forEach(::put)
        discoverLocalDir(PluginInstaller.pluginsRoot()).forEach(::put)
        discoverLocalDir(PluginInstaller.legacyRenderersRoot()).forEach(::put)
        discoverPackages(context).forEach(::put)
        return byId.values.sortedWith(
            compareBy<PluginDescriptor> { it.type.name }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun discoverBuiltin(): List<PluginDescriptor> {
        // Only catalog downloadable plugins; bundled kinds (MobileGlues / REL / GL4ES) skip this.
        return RendererPackages.all.filter { it.kind.requiresPlugin }.map { pkg ->
            val legacyDir = RendererInstaller.installDir(pkg)
            val pluginDir = PluginInstaller.installDir(pkg.id)
            val dir = when {
                PluginInstaller.isReady(pluginDir, pkg.glLib) -> pluginDir
                PluginInstaller.isReady(legacyDir, pkg.glLib) -> legacyDir
                else -> null
            }
            val installed = dir != null
            PluginDescriptor(
                id = pkg.id,
                name = pkg.kind.displayName,
                version = "builtin",
                type = PluginType.RENDERER,
                source = if (installed) PluginSource.LOCAL else PluginSource.BUILTIN,
                enabled = PluginStateStore.isEnabled(pkg.id),
                nativeDir = dir,
                glLib = pkg.glLib,
                eglLib = pkg.eglLib,
                rendererToken = pkg.rendererToken,
                libGlEs = pkg.libGlEs,
                extraEnv = pkg.extraEnv,
                downloadUrl = pkg.downloadUrl,
                kindName = pkg.kind.name,
                installed = installed,
                disguiseAsGl4es = pkg.disguiseAsGl4es
            )
        }
    }

    private fun discoverLocalDir(root: File): List<PluginDescriptor> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> descriptorFromInstallDir(dir) }
            .orEmpty()
    }

    fun descriptorFromInstallDir(dir: File): PluginDescriptor? {
        if (!dir.isDirectory) return null
        val manifest = PluginManifest.read(File(dir, "plugin.json"))
        val glLib = manifest?.glLib
            ?: dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".so") }?.name
            ?: return null
        if (!PluginInstaller.isReady(dir, glLib) && !File(dir, glLib).isFile) return null
        val id = manifest?.id ?: dir.name
        val builtin = RendererPackages.all.firstOrNull { it.id.equals(id, ignoreCase = true) }
        return PluginDescriptor(
            id = id,
            name = manifest?.name ?: builtin?.kind?.displayName ?: id,
            version = manifest?.version ?: "local",
            type = manifest?.type ?: PluginType.RENDERER,
            source = PluginSource.LOCAL,
            enabled = PluginStateStore.isEnabled(id),
            nativeDir = dir,
            glLib = glLib,
            eglLib = manifest?.eglLib ?: builtin?.eglLib ?: "libEGL.so",
            rendererToken = manifest?.rendererToken ?: builtin?.rendererToken ?: "opengles3",
            libGlEs = manifest?.libGlEs ?: builtin?.libGlEs ?: "3",
            extraEnv = manifest?.extraEnv ?: builtin?.extraEnv.orEmpty(),
            downloadUrl = builtin?.downloadUrl,
            kindName = manifest?.kindName ?: builtin?.kind?.name,
            installed = true,
            disguiseAsGl4es = manifest?.disguiseAsGl4es
                ?: builtin?.disguiseAsGl4es
                ?: false
        )
    }

    private fun discoverPackages(context: Context): List<PluginDescriptor> {
        val pm = context.packageManager
        val apps = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (_: Exception) {
            emptyList()
        }
        val out = ArrayList<PluginDescriptor>()
        for (app in apps) {
            val meta = readMeta(pm, app) ?: continue
            val pkgName = app.packageName
            val id = "pkg-" + pkgName.replace('.', '_')
            val nativeDir = File(app.nativeLibraryDir)
            val glLib = meta.glLib
            val glFile = File(nativeDir, glLib)
            if (!glFile.isFile) {
                // Some plugins ship under abi subfolders already expanded by PM.
                val found = nativeDir.listFiles()?.firstOrNull {
                    it.isFile && it.name == glLib
                }
                if (found == null && meta.requireGl) continue
            }
            val label = runCatching {
                pm.getApplicationLabel(app).toString()
            }.getOrDefault(pkgName)
            out += PluginDescriptor(
                id = id,
                name = label,
                version = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        pm.getPackageInfo(
                            pkgName,
                            PackageManager.PackageInfoFlags.of(0)
                        ).versionName
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(pkgName, 0).versionName
                    }
                }.getOrNull().orEmpty().ifBlank { "apk" },
                type = meta.type,
                source = PluginSource.PACKAGE,
                enabled = PluginStateStore.isEnabled(id),
                nativeDir = nativeDir.takeIf { it.isDirectory },
                glLib = glLib,
                eglLib = meta.eglLib,
                rendererToken = meta.rendererToken,
                libGlEs = meta.libGlEs,
                extraEnv = meta.extraEnv,
                packageName = pkgName,
                kindName = meta.kindName,
                installed = true,
                disguiseAsGl4es = meta.disguiseAsGl4es
            )
        }
        return out
    }

    private data class PkgMeta(
        val type: PluginType,
        val glLib: String,
        val eglLib: String,
        val rendererToken: String,
        val libGlEs: String,
        val kindName: String?,
        val disguiseAsGl4es: Boolean,
        val extraEnv: Map<String, String>,
        val requireGl: Boolean
    )

    private fun readMeta(pm: PackageManager, app: ApplicationInfo): PkgMeta? {
        val bundle = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(
                    app.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                ).metaData
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
            }
        } catch (_: Exception) {
            null
        }
        val booxin = bundle?.getString(META_BOOXIN)
        val fcl = bundle?.getString(META_FCL)
        val glHint = bundle?.getString(META_FCL_ALT)
        val pkgLower = app.packageName.lowercase()
        val looksFcl = "fcl" in pkgLower &&
            ("renderer" in pkgLower || "plugin" in pkgLower || "driver" in pkgLower)
        if (booxin.isNullOrBlank() && fcl.isNullOrBlank() && glHint.isNullOrBlank() && !looksFcl) {
            return null
        }
        val type = when {
            booxin.equals("driver", ignoreCase = true) ||
                fcl.equals("driver", ignoreCase = true) ||
                "driver" in pkgLower -> PluginType.DRIVER
            else -> PluginType.RENDERER
        }
        val glLib = when {
            !glHint.isNullOrBlank() -> glHint.substringAfterLast('/')
            "ltw" in pkgLower -> "libltw.so"
            "mcrender" in pkgLower -> "libmcrender.so"
            "rel" in pkgLower || "openrel" in pkgLower -> "librel.so"
            "angle" in pkgLower -> "libGLESv2_angle.so"
            "zink" in pkgLower || "mesa" in pkgLower -> "libOSMesa.so"
            "krypton" in pkgLower || "ngg" in pkgLower -> "libng_gl4es.so"
            else -> "libgl4es_114.so"
        }
        val isRel = "rel" in pkgLower || "openrel" in pkgLower
        val isMcRender = "mcrender" in pkgLower
        return PkgMeta(
            type = type,
            glLib = glLib,
            eglLib = when {
                "angle" in pkgLower -> "libEGL_angle.so"
                isRel -> "librel.so"
                else -> "libEGL.so"
            },
            rendererToken = when {
                "ltw" in pkgLower -> "opengles3_ltw"
                isRel -> "opengles3_rel"
                "zink" in pkgLower -> "vulkan_zink"
                "virgl" in pkgLower -> "gallium_virgl"
                else -> "opengles3"
            },
            libGlEs = "3",
            kindName = when {
                isRel -> "REL"
                isMcRender -> "MCRENDER"
                else -> null
            },
            disguiseAsGl4es =
                "ltw" in pkgLower || "krypton" in pkgLower || "gl4es" in pkgLower ||
                    isRel, // MCrender must keep soname libmcrender.so (no gl4es disguise)
            extraEnv = emptyMap(),
            requireGl = !looksFcl
        )
    }
}
