package com.booxin.launcher.core.plugin

import android.content.Context
import android.util.Log
import com.booxin.launcher.core.launch.GlRendererKind
import com.booxin.launcher.core.runtime.RendererPackage
import com.booxin.launcher.core.runtime.RendererPackages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * FCL-inspired plugin hub: init/refresh discovery, install, enable/disable, uninstall.
 */
object PluginManager {
    private const val TAG = "PluginManager"

    private val mutex = Mutex()
    private val plugins = CopyOnWriteArrayList<PluginDescriptor>()

    @Volatile
    private var initialized = false

    fun isAvailable(): Boolean = plugins.any { it.installed && it.enabled }

    fun all(): List<PluginDescriptor> = plugins.toList()

    fun renderers(): List<PluginDescriptor> =
        plugins.filter { it.type == PluginType.RENDERER }

    fun drivers(): List<PluginDescriptor> =
        plugins.filter { it.type == PluginType.DRIVER }

    fun findById(id: String): PluginDescriptor? =
        plugins.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun findByKind(kind: GlRendererKind): PluginDescriptor? =
        plugins.firstOrNull { it.kind == kind }

    fun enabledRendererNativeDir(kind: GlRendererKind): File? {
        val p = findByKind(kind) ?: return null
        if (!p.enabled || !p.installed) return null
        return p.nativeDir?.takeIf { PluginInstaller.isReady(it, p.glLib) || File(it, p.glLib).isFile }
    }

    suspend fun init(context: Context) = mutex.withLock {
        if (initialized) return@withLock
        refreshLocked(context.applicationContext)
        initialized = true
        Log.i(TAG, "init: ${plugins.size} plugins (${plugins.count { it.installed }} installed)")
    }

    suspend fun refresh(context: Context) = mutex.withLock {
        refreshLocked(context.applicationContext)
        Log.i(TAG, "refresh: ${plugins.size} plugins")
    }

    private fun refreshLocked(context: Context) {
        plugins.clear()
        plugins.addAll(PluginDiscovery.discoverAll(context))
    }

    suspend fun setEnabled(context: Context, id: String, enabled: Boolean) = mutex.withLock {
        PluginStateStore.setEnabled(id, enabled)
        refreshLocked(context.applicationContext)
    }

    suspend fun uninstall(context: Context, id: String) = mutex.withLock {
        PluginInstaller.uninstall(id)
        refreshLocked(context.applicationContext)
    }

    suspend fun installBuiltin(context: Context, kind: GlRendererKind): Result<PluginDescriptor> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pkg = requireNotNull(RendererPackages.forKind(kind)) {
                    "无内置目录: ${kind.displayName}"
                }
                installBuiltinPackage(context, pkg)
            }
        }

    suspend fun installBuiltinPackage(context: Context, pkg: RendererPackage): PluginDescriptor =
        mutex.withLock {
            PluginInstaller.installBuiltin(pkg)
            refreshLocked(context.applicationContext)
            findById(pkg.id)
                ?: error("安装后未发现插件: ${pkg.id}")
        }

    suspend fun installFromUrl(
        context: Context,
        id: String,
        url: String,
        name: String = id
    ): Result<PluginDescriptor> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val dir = PluginInstaller.installFromUrl(id, url, name)
                refreshLocked(context.applicationContext)
                findById(dir.name)
                    ?: PluginDiscovery.descriptorFromInstallDir(dir)
                    ?: error("安装后未发现插件: $id")
            }
        }
    }

    suspend fun installFromApk(
        context: Context,
        apk: File,
        preferredId: String? = null,
        preferredName: String? = null
    ): Result<PluginDescriptor> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val dir = PluginInstaller.installFromApkFile(apk, preferredId, preferredName)
                refreshLocked(context.applicationContext)
                PluginDiscovery.descriptorFromInstallDir(dir)
                    ?: findById(dir.name)
                    ?: error("导入失败")
            }
        }
    }

    /** Builtin catalog entries that can be downloaded from settings. */
    fun downloadableCatalog(): List<PluginDescriptor> =
        plugins.filter { it.source == PluginSource.BUILTIN || it.downloadUrl != null }
}
