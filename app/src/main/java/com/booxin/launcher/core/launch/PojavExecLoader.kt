package com.booxin.launcher.core.launch

import com.booxin.launcher.core.runtime.ExecBridgeLoader

/** @deprecated Use [ExecBridgeLoader]. */
@Deprecated("Use ExecBridgeLoader", ReplaceWith("ExecBridgeLoader", "com.booxin.launcher.core.runtime.ExecBridgeLoader"))
object PojavExecLoader {
    fun ensureLoaded() = ExecBridgeLoader.ensureLoaded()
}
