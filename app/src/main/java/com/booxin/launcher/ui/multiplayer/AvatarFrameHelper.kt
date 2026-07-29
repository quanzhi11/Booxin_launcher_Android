package com.booxin.launcher.ui.multiplayer

import androidx.annotation.DrawableRes
import com.booxin.launcher.R

object AvatarFrameHelper {
    @DrawableRes
    fun resolveDrawable(frameId: String?): Int? {
        return when (normalize(frameId)) {
            "", "none" -> null
            "gold", "peak" -> R.drawable.bg_avatar_frame_gold
            "diamond", "aurora", "cyan" -> R.drawable.bg_avatar_frame_diamond
            else -> R.drawable.bg_avatar_frame_default
        }
    }

    fun hasActiveFrame(frameId: String?): Boolean = resolveDrawable(frameId) != null

    private fun normalize(frameId: String?): String =
        frameId?.trim()?.lowercase().orEmpty()
}
