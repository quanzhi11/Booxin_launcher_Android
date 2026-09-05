package com.booxin.launcher.core.runtime

import android.content.Context
import android.util.Log
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.LauncherPrefs
import com.booxin.launcher.core.launch.GlRendererKind
import java.io.File

/**
 * REL world-join crashes on some Adreno devices with:
 *   REL ERROR: GL_OUT_OF_MEMORY in glTexImage2D
 *   then SIGSEGV in glDrawElements (null memcpy)
 *
 * Root cause is GPU/system VRAM pressure (full phone resolution + chunk textures),
 * not "REL is forbidden". Prefer mitigations; only fall back after a mitigated launch
 * still dies.
 */
object RendererCrashHeal {
    private const val TAG = "RelStability"
    private const val RECENT_MS = 7 * 24 * 60 * 60_000L
    private const val MITIGATION_MARKER = ".booxin_rel_vram_mitigated_v1"

    data class VramGuard(
        val width: Int,
        val height: Int,
        val maxMemoryMb: Int,
        val mipmapLevels: Int,
        val notes: List<String>
    )

    /**
     * Apply when renderer is REL: shrink FBO, leave RAM for GPU, cap mipmaps.
     */
    fun buildVramGuard(
        width: Int,
        height: Int,
        requestedMemoryMb: Int,
        kind: GlRendererKind
    ): VramGuard? {
        if (kind != GlRendererKind.REL) return null
        if (width < 2 || height < 2) return null

        val notes = ArrayList<String>()
        var w = width
        var h = height
        // 2772x1280 (~3.5MP) OOMs Adreno during world texture upload.
        // Cap long edge ~1600 (~1.3–1.8MP) — keeps UI usable, cuts FBO/VRAM hard.
        val maxLong = 1600
        val longEdge = maxOf(w, h)
        val pixels = w.toLong() * h
        if (longEdge > maxLong || pixels >= 2_000_000L) {
            val scale = maxLong.toFloat() / longEdge.toFloat()
            w = (w * scale).toInt().coerceAtLeast(640)
            h = (h * scale).toInt().coerceAtLeast(360)
            notes += "分辨率 ${width}x${height} → ${w}x${h}"
        }

        // Unified memory: oversized Java heap starves GLES texture uploads.
        val cappedMem = LauncherPrefs.clampMemory(
            minOf(requestedMemoryMb, 1536).coerceAtLeast(LauncherPrefs.MEMORY_MIN_MB)
        )
        if (cappedMem < requestedMemoryMb) {
            notes += "内存 ${requestedMemoryMb}MB → ${cappedMem}MB（给 GPU 留空间）"
        }

        notes += "mipmap≤2"
        return VramGuard(
            width = w,
            height = h,
            maxMemoryMb = cappedMem,
            mipmapLevels = 2,
            notes = notes
        )
    }

    fun markMitigationsApplied(versionId: String) {
        if (!LauncherPaths.isInitialized) return
        val marker = File(LauncherPaths.versionsDir, "$versionId/$MITIGATION_MARKER")
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(System.currentTimeMillis().toString())
        }
    }

    fun mitigationsWereApplied(versionId: String): Boolean {
        if (!LauncherPaths.isInitialized) return false
        return File(LauncherPaths.versionsDir, "$versionId/$MITIGATION_MARKER").isFile
    }

    /**
     * Last-resort only: previous launch already used VRAM mitigations and still
     * produced a REL native GL crash → switch to MobileGlues once.
     */
    fun healRelWorldJoinCrash(context: Context, versionId: String): String? {
        val forceRel = forceRendererIsRel(context)
        val prefsRel = LauncherPrefs.rendererKind() == GlRendererKind.REL
        if (!forceRel && !prefsRel) return null
        if (!mitigationsWereApplied(versionId)) {
            Log.i(TAG, "skip heal: REL mitigations not yet applied for $versionId")
            return null
        }

        val hsErr = newestRelCrashEvidence(versionId) ?: return null
        // Only heal crashes newer than the mitigation marker (i.e. failed *with* guard).
        val marker = File(LauncherPaths.versionsDir, "$versionId/$MITIGATION_MARKER")
        if (hsErr.lastModified() < marker.lastModified()) {
            Log.i(TAG, "skip heal: crash older than mitigations")
            return null
        }
        val healMarker = File(hsErr.parentFile, ".booxin_healed_${hsErr.name}")
        if (healMarker.isFile) return null

        val before = LauncherPrefs.rendererPreference()
        LauncherPrefs.setRendererKind(GlRendererKind.MOBILE_GLUES)
        clearForceRendererSidecar(context)
        runCatching { healMarker.writeText("healed→MobileGlues from=$before afterMitigation") }

        val msg =
            "REL 在显存防护下仍进世界崩溃（${hsErr.name}），已回退 MobileGlues"
        Log.w(TAG, msg)
        return msg
    }

    /** @deprecated Use [buildVramGuard]; kept for call-site compatibility. */
    fun maybeScaleRelWindow(width: Int, height: Int, kind: GlRendererKind): Pair<Int, Int>? {
        val g = buildVramGuard(width, height, LauncherPrefs.maxMemoryMb(), kind) ?: return null
        if (g.width == width && g.height == height) return null
        return g.width to g.height
    }

    private fun forceRendererIsRel(context: Context): Boolean {
        val file = File(context.getExternalFilesDir(null), "force_renderer.txt")
        if (!file.isFile) return false
        val name = runCatching { file.readText().trim() }.getOrNull().orEmpty()
        return name.equals(GlRendererKind.REL.name, ignoreCase = true)
    }

    private fun newestRelCrashEvidence(versionId: String): File? {
        val cutoff = System.currentTimeMillis() - RECENT_MS
        val candidates = ArrayList<File>()
        if (LauncherPaths.isInitialized) {
            val versionRoot = File(LauncherPaths.versionsDir, versionId)
            versionRoot.listFiles()
                ?.filter { it.isFile && it.name.startsWith("hs_err_pid") }
                ?.let { candidates += it }
            File(versionRoot, "crash-reports").listFiles()
                ?.filter { it.isFile }
                ?.let { candidates += it }
            runCatching {
                val ctx = com.booxin.launcher.BooxinApp.getAppContext()
                ctx.getExternalFilesDir(null)?.let { ext ->
                    File(ext, "crash").listFiles()
                        ?.filter {
                            it.isFile &&
                                it.name.contains(versionId, ignoreCase = true) &&
                                (it.name.contains("hs_err") || it.name.contains("crash"))
                        }
                        ?.let { candidates += it }
                }
            }
        }
        return candidates
            .filter { it.lastModified() >= cutoff }
            .sortedByDescending { it.lastModified() }
            .firstOrNull { looksLikeRelNativeGlCrash(it) }
    }

    private fun looksLikeRelNativeGlCrash(file: File): Boolean {
        val text = runCatching { file.readText().take(200_000) }.getOrNull() ?: return false
        val lower = text.lowercase()
        val isRel =
            "librel.so" in lower ||
                ("opengl.libname=" in lower && "librel" in lower) ||
                "opengles3_rel" in lower ||
                "booxin_renderer=opengles3_rel" in lower
        if (!isRel) return false
        return "sigsegv" in lower ||
            "gl_out_of_memory" in lower ||
            "gldrawelements" in lower ||
            "glteximage2d" in lower ||
            "libglesv2_adreno" in lower
    }

    private fun clearForceRendererSidecar(context: Context) {
        runCatching {
            val file = File(context.getExternalFilesDir(null), "force_renderer.txt")
            if (file.isFile) {
                file.delete()
                Log.i(TAG, "removed force_renderer.txt")
            }
        }
    }
}
