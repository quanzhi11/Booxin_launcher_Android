package com.booxin.launcher.ui.launch.input

/**
 * One on-screen control button. Position is the center as 0..1 of the parent size.
 *
 * [code] is the primary key (backward compatible). When [codes] has 2+ entries,
 * the button fires a key combination (modifiers first, then main key).
 */
data class ControlButtonSpec(
    val id: String,
    val label: String,
    val kind: Kind,
    val code: Int,
    val x: Float,
    val y: Float,
    val sizeDp: Int = 52,
    val codes: List<Int> = emptyList(),
    /** Relative path under the controlLayout plugin dir, e.g. assets/btn.png */
    val icon: String = "",
    /** Optional CSS-like chrome; merged over layout-level buttonStyle. */
    val style: ControlButtonStyle? = null,
    /**
     * When non-blank, tap injects this chat/command text (FCL `outputText`).
     * Key codes are skipped for KEY_TAP when this is set.
     */
    val outputText: String = ""
) {
    enum class Kind {
        /** Hold while finger is down; release on finger up. */
        KEY_HOLD,
        KEY_TAP,
        MOUSE_HOLD,
        SCROLL,
        /** Toggle Android soft keyboard (chat / commands). */
        SOFT_KEYBOARD,
        /**
         * Hold a key while the finger is down; the button graphic follows the finger
         * and springs back on release (does not hand off to look pad).
         */
        KEY_FOLLOW,
        /** Same as [KEY_FOLLOW] but for a mouse button. */
        MOUSE_FOLLOW,
        /** Custom only: tap to latch key down, tap again to release. */
        KEY_TOGGLE,
        /** Custom only: tap to latch mouse button, tap again to release. */
        MOUSE_TOGGLE
    }

    /** Resolved key sequence for KEY_TAP / KEY_HOLD / KEY_FOLLOW. */
    fun effectiveCodes(): List<Int> =
        if (codes.size >= 2) codes else listOf(code)

    fun isFollowKind(): Boolean =
        kind == Kind.KEY_FOLLOW || kind == Kind.MOUSE_FOLLOW

    fun isToggleHoldKind(): Boolean =
        kind == Kind.KEY_TOGGLE || kind == Kind.MOUSE_TOGGLE

    fun isHoldKind(): Boolean =
        kind == Kind.KEY_HOLD || kind == Kind.MOUSE_HOLD || isFollowKind() || isToggleHoldKind()

    /** Soft keyboard / scroll cannot become follow buttons. */
    fun canToggleFollow(): Boolean = when (kind) {
        Kind.KEY_TAP, Kind.KEY_HOLD, Kind.KEY_FOLLOW,
        Kind.MOUSE_HOLD, Kind.MOUSE_FOLLOW -> true
        else -> false
    }

    /** Enable/disable follow while keeping the same key/mouse code. */
    fun withFollow(enabled: Boolean): ControlButtonSpec {
        if (!canToggleFollow()) return this
        val nextKind = if (enabled) {
            when (kind) {
                Kind.MOUSE_HOLD, Kind.MOUSE_FOLLOW -> Kind.MOUSE_FOLLOW
                else -> Kind.KEY_FOLLOW
            }
        } else {
            when (kind) {
                Kind.MOUSE_FOLLOW, Kind.MOUSE_HOLD -> Kind.MOUSE_HOLD
                else -> Kind.KEY_HOLD
            }
        }
        return if (nextKind == kind) this else copy(kind = nextKind)
    }
}
