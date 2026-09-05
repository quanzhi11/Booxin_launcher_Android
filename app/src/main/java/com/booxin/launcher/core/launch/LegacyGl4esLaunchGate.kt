package com.booxin.launcher.core.launch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.booxin.launcher.R
import com.booxin.launcher.core.LauncherPrefs
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.java.MinecraftJavaRequirement
import com.booxin.launcher.core.runtime.RendererBackend
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Ancient clients usually need holy GL4ES. If the resolved renderer is not GL4ES,
 * ask before launch: switch to GL4ES, launch anyway, or cancel.
 */
object LegacyGl4esLaunchGate {

    /**
     * @return true to proceed with launch; false if the user cancelled.
     */
    suspend fun confirmIfNeeded(context: Context, versionId: String): Boolean {
        val mcId = runCatching {
            VersionJsonMerger.resolveMinecraftVersionId(versionId)
        }.getOrDefault(versionId)
        if (!MinecraftJavaRequirement.needsGl4esRenderer(mcId)) return true

        val kind = RendererBackend.kindForLaunch(
            instanceVersionId = versionId,
            mcVersionId = mcId
        )
        if (kind == GlRendererKind.GL4ES) return true

        val activity = context.findActivity() ?: return true
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val dialog = MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.legacy_gl4es_title)
                    .setMessage(
                        activity.getString(
                            R.string.legacy_gl4es_message,
                            mcId,
                            kind.displayName
                        )
                    )
                    .setPositiveButton(R.string.legacy_gl4es_switch) { _, _ ->
                        LauncherPrefs.setRendererKind(GlRendererKind.GL4ES)
                        if (cont.isActive) cont.resume(true)
                    }
                    .setNeutralButton(R.string.legacy_gl4es_anyway) { _, _ ->
                        if (cont.isActive) cont.resume(true)
                    }
                    .setNegativeButton(R.string.legacy_gl4es_cancel) { _, _ ->
                        if (cont.isActive) cont.resume(false)
                    }
                    .setOnCancelListener {
                        if (cont.isActive) cont.resume(false)
                    }
                    .create()
                cont.invokeOnCancellation {
                    runCatching { dialog.dismiss() }
                }
                dialog.show()
            }
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
