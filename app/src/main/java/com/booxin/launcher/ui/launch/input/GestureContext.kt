package com.booxin.launcher.ui.launch.input

import com.booxin.runtime.BooxinBridge

/** COMBINED 手势用的准星 / 手持物快照。 */
object GestureContext {
    const val HIT_UNKNOWN = 0
    const val HIT_MISS = 1
    const val HIT_BLOCK = 2
    const val HIT_ENTITY = 3

    const val HELD_UNKNOWN = 0
    const val HELD_EMPTY = 1
    const val HELD_BLOCK = 2
    const val HELD_WEAPON = 3
    const val HELD_OTHER = 4

    data class Snapshot(
        val hit: Int,
        val held: Int
    ) {
        val holdingPlaceable: Boolean get() = held == HELD_BLOCK
        val lookingAtEntity: Boolean get() = hit == HIT_ENTITY

        val preferPlaceOnTap: Boolean
            get() = when (held) {
                HELD_WEAPON, HELD_EMPTY -> false
                else -> true
            }

        /** Long-press dig when not aiming at a mob and tap would place or dig empty. */
        val preferLongPressDig: Boolean
            get() = !lookingAtEntity && (preferPlaceOnTap || held == HELD_EMPTY)
    }

    fun snapshot(): Snapshot = Snapshot(
        hit = BooxinBridge.queryHitResultType(),
        held = BooxinBridge.queryHeldItemKind()
    )

    /** Short-tap button for COMBINED (long-press dig is separate). */
    fun resolveCombinedTapButton(snap: Snapshot = snapshot()): Int {
        if (snap.lookingAtEntity) return GameInput.MOUSE_LEFT
        return if (snap.preferPlaceOnTap) GameInput.MOUSE_RIGHT else GameInput.MOUSE_LEFT
    }
}
