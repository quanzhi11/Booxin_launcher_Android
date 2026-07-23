package com.booxin.launcher.ui.launch.input

/**
 * FCL-compatible gesture mode for grabbed (in-world) touch.
 * BUILD: short tap = place/use (RMB), long press = break (LMB hold)
 * FIGHT: short tap = attack (LMB), long press = use (RMB hold)
 */
enum class GestureMode {
    BUILD,
    FIGHT
}
