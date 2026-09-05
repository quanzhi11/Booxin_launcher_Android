package com.booxin.launcher.core.runtime

import android.content.Context
import android.util.Log
import com.booxin.launcher.core.launch.GlRendererKind
import java.io.File

/**
 * libmcrender 启动时会读固定路径上的 mcrender.conf。
 * compat=0：关闭兼容色路径，修正颜色偏色。
 */
object McRenderConfig {
    private const val TAG = "McRenderConfig"
    private const val BODY = "compat=0\n"

    /** SO 内硬编码的候选路径（按优先级）。 */
    private val CANDIDATE_PATHS = listOf(
        "/data/local/tmp/mcrender.conf",
        "/sdcard/123/mcrender.conf"
    )

    fun ensureForLaunch(context: Context, kind: GlRendererKind) {
        if (kind != GlRendererKind.MCRENDER) return
        writeCompatOff(context)
    }

    fun writeCompatOff(context: Context) {
        var ok = 0
        for (path in CANDIDATE_PATHS) {
            val file = File(path)
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(BODY)
                file.setReadable(true, false)
                ok++
                Log.i(TAG, "wrote $path")
            }.onFailure {
                Log.w(TAG, "skip $path: ${it.message}")
            }
        }
        // 应用私有目录备份（便于排查；SO 未必读取）
        runCatching {
            val backup = File(context.filesDir, "mcrender/mcrender.conf").also {
                it.parentFile?.mkdirs()
            }
            backup.writeText(BODY)
            Log.i(TAG, "backup ${backup.absolutePath}")
        }
        if (ok == 0) {
            Log.e(TAG, "failed to write any mcrender.conf path")
        }
    }
}
