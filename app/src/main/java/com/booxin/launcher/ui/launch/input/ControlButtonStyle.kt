package com.booxin.launcher.ui.launch.input

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import org.json.JSONArray
import org.json.JSONObject

/**
 * CSS-like button chrome from control_layout.json (`style` / `css`,
 * or layout-level `buttonStyle` / `buttonCss`).
 */
data class ControlButtonStyle(
    val backgroundColor: Int? = null,
    val borderColor: Int? = null,
    val borderWidthDp: Float? = null,
    val borderRadiusDp: Float? = null,
    val shape: Shape? = null,
    val gradientColors: List<Int> = emptyList(),
    val gradientOrientation: GradientDrawable.Orientation? = null,
    val textColor: Int? = null,
    val opacity: Float? = null,
    /** Play-mode press scale (0.72..1). Default 0.90 when unset. */
    val pressScale: Float? = null,
    /** Press animation: squash (instant) or jelly (bounce). */
    val pressEffect: PressEffect? = null
) {
    enum class Shape { OVAL, RECT, ROUND_RECT }
    enum class PressEffect { SQUASH, JELLY }

    fun isEmpty(): Boolean =
        backgroundColor == null &&
            borderColor == null &&
            borderWidthDp == null &&
            borderRadiusDp == null &&
            shape == null &&
            gradientColors.isEmpty() &&
            gradientOrientation == null &&
            textColor == null &&
            opacity == null &&
            pressScale == null &&
            pressEffect == null

    /** Per-button style wins over layout default. */
    fun mergeOver(base: ControlButtonStyle?): ControlButtonStyle {
        if (base == null || base.isEmpty()) return this
        if (isEmpty()) return base
        return ControlButtonStyle(
            backgroundColor = backgroundColor ?: base.backgroundColor,
            borderColor = borderColor ?: base.borderColor,
            borderWidthDp = borderWidthDp ?: base.borderWidthDp,
            borderRadiusDp = borderRadiusDp ?: base.borderRadiusDp,
            shape = shape ?: base.shape,
            gradientColors = gradientColors.ifEmpty { base.gradientColors },
            gradientOrientation = gradientOrientation ?: base.gradientOrientation,
            textColor = textColor ?: base.textColor,
            opacity = opacity ?: base.opacity,
            pressScale = pressScale ?: base.pressScale,
            pressEffect = pressEffect ?: base.pressEffect
        )
    }

    fun resolvedShape(): Shape = shape ?: when {
        borderRadiusDp != null && borderRadiusDp < 998f -> Shape.ROUND_RECT
        else -> Shape.OVAL
    }

    fun toJson(): JSONObject? {
        if (isEmpty()) return null
        return JSONObject().also { o ->
            backgroundColor?.let { o.put("backgroundColor", colorToHex(it)) }
            borderColor?.let { o.put("borderColor", colorToHex(it)) }
            borderWidthDp?.let { o.put("borderWidth", it.toDouble()) }
            borderRadiusDp?.let { o.put("borderRadius", it.toDouble()) }
            shape?.let { o.put("shape", it.name.lowercase()) }
            if (gradientColors.isNotEmpty()) {
                o.put("gradient", JSONArray(gradientColors.map { colorToHex(it) }))
                o.put(
                    "gradientOrientation",
                    orientationToString(gradientOrientation ?: GradientDrawable.Orientation.TL_BR)
                )
            }
            textColor?.let { o.put("textColor", colorToHex(it)) }
            opacity?.let { o.put("opacity", it.toDouble()) }
            pressScale?.let { o.put("pressScale", it.toDouble()) }
            pressEffect?.let { o.put("pressEffect", it.name.lowercase()) }
        }
    }

    companion object {
        fun fromJson(o: JSONObject?): ControlButtonStyle? {
            if (o == null || o.length() == 0) return null
            val bg = parseColor(firstString(o, "backgroundColor", "background", "bg", "background-color"))
            val border = parseColor(firstString(o, "borderColor", "border-color", "strokeColor"))
            val borderWidth = firstNumber(o, "borderWidth", "border-width", "strokeWidth")
                ?.toFloat()?.coerceIn(0f, 24f)
            val radiusRaw = firstString(o, "borderRadius", "border-radius", "radius")
            val radiusNum = firstNumber(o, "borderRadius", "border-radius", "radius")
                ?.toFloat()?.coerceIn(0f, 999f)
            val radius = when {
                radiusRaw.equals("50%", true) ||
                    radiusRaw.equals("full", true) ||
                    radiusRaw.equals("circle", true) -> 999f
                else -> radiusNum
            }
            val shape = parseShape(firstString(o, "shape"), radiusRaw)
            val gradient = parseGradient(o.opt("gradient") ?: o.opt("backgroundImage"))
            val orientation = firstString(o, "gradientOrientation", "gradient-orientation", "gradientDirection")
                ?.let { parseOrientation(it) }
            val text = parseColor(firstString(o, "textColor", "color", "text-color"))
            val opacity = firstNumber(o, "opacity", "alpha")?.toFloat()?.coerceIn(0.15f, 1f)
            val pressScale = firstNumber(o, "pressScale", "press-scale", "clickScale")
                ?.toFloat()?.coerceIn(0.72f, 1f)
            val pressEffect = parsePressEffect(
                firstString(o, "pressEffect", "press-effect", "clickEffect", "click-effect"),
                o.optBoolean("jelly", false)
            )
            val style = ControlButtonStyle(
                backgroundColor = bg,
                borderColor = border,
                borderWidthDp = borderWidth,
                borderRadiusDp = radius,
                shape = shape,
                gradientColors = gradient,
                gradientOrientation = orientation,
                textColor = text,
                opacity = opacity,
                pressScale = pressScale,
                pressEffect = pressEffect
            )
            return style.takeUnless { it.isEmpty() }
        }

        private fun parsePressEffect(raw: String?, jellyFlag: Boolean): PressEffect? {
            if (jellyFlag) return PressEffect.JELLY
            val s = raw?.trim()?.lowercase().orEmpty()
            return when (s) {
                "jelly", "bounce", "spring", "果冻" -> PressEffect.JELLY
                "squash", "press", "scale", "default" -> PressEffect.SQUASH
                "none", "off", "false" -> null
                else -> null
            }
        }

        fun toDrawable(style: ControlButtonStyle, density: Float): GradientDrawable {
            val d = GradientDrawable()
            val shape = style.resolvedShape()
            when (shape) {
                Shape.OVAL -> d.shape = GradientDrawable.OVAL
                Shape.RECT -> {
                    d.shape = GradientDrawable.RECTANGLE
                    d.cornerRadius = 0f
                }
                Shape.ROUND_RECT -> {
                    d.shape = GradientDrawable.RECTANGLE
                    val rDp = style.borderRadiusDp ?: 16f
                    d.cornerRadius = rDp * density
                }
            }
            if (style.gradientColors.size >= 2) {
                d.orientation = style.gradientOrientation ?: GradientDrawable.Orientation.TL_BR
                d.colors = style.gradientColors.toIntArray()
            } else {
                d.setColor(style.backgroundColor ?: 0x99000000.toInt())
            }
            val strokeDp = style.borderWidthDp ?: 0f
            val strokeColor = style.borderColor
            if (strokeColor != null && strokeDp > 0f) {
                d.setStroke((strokeDp * density).toInt().coerceAtLeast(1), strokeColor)
            }
            return d
        }

        private fun parseShape(raw: String?, radiusRaw: String?): Shape? {
            val s = raw?.trim()?.lowercase().orEmpty()
            return when (s) {
                "oval", "circle", "round", "elliptical" -> Shape.OVAL
                "rect", "rectangle", "square" -> Shape.RECT
                "roundrect", "round_rect", "rounded", "roundedrect", "pill" -> Shape.ROUND_RECT
                else -> when {
                    radiusRaw.equals("50%", true) -> Shape.OVAL
                    s.isEmpty() -> null
                    else -> null
                }
            }
        }

        private fun parseGradient(raw: Any?): List<Int> {
            return when (raw) {
                is JSONArray -> buildList {
                    for (i in 0 until raw.length()) {
                        parseColor(raw.optString(i))?.let { add(it) }
                    }
                }
                is String -> {
                    val inner = raw
                        .replace(Regex("""(?i)linear-gradient\(([^)]*)\)"""), "$1")
                        .split(',')
                        .map { it.trim() }
                        .filter { it.startsWith("#") || it.startsWith("0x") }
                    inner.mapNotNull { parseColor(it) }
                }
                else -> emptyList()
            }.let { if (it.size >= 2) it else emptyList() }
        }

        private fun parseOrientation(raw: String): GradientDrawable.Orientation {
            return when (raw.trim().lowercase().replace('-', '_').replace(' ', '_')) {
                "top_bottom", "tb", "to_bottom" -> GradientDrawable.Orientation.TOP_BOTTOM
                "bottom_top", "bt", "to_top" -> GradientDrawable.Orientation.BOTTOM_TOP
                "left_right", "lr", "to_right" -> GradientDrawable.Orientation.LEFT_RIGHT
                "right_left", "rl", "to_left" -> GradientDrawable.Orientation.RIGHT_LEFT
                "tr_bl" -> GradientDrawable.Orientation.TR_BL
                "br_tl" -> GradientDrawable.Orientation.BR_TL
                "bl_tr" -> GradientDrawable.Orientation.BL_TR
                else -> GradientDrawable.Orientation.TL_BR
            }
        }

        fun parseColor(raw: String?): Int? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            return try {
                when {
                    s.startsWith("#") -> {
                        val h = s.substring(1)
                        when (h.length) {
                            3 -> {
                                val r = h[0].toString().repeat(2)
                                val g = h[1].toString().repeat(2)
                                val b = h[2].toString().repeat(2)
                                Color.parseColor("#FF$r$g$b")
                            }
                            4 -> {
                                val a = h[0].toString().repeat(2)
                                val r = h[1].toString().repeat(2)
                                val g = h[2].toString().repeat(2)
                                val b = h[3].toString().repeat(2)
                                Color.parseColor("#$a$r$g$b")
                            }
                            6 -> Color.parseColor("#FF$h")
                            8 -> Color.parseColor("#$h")
                            else -> null
                        }
                    }
                    s.startsWith("0x") || s.startsWith("0X") -> {
                        val hex = s.substring(2)
                        val v = hex.toLong(16)
                        when (hex.length) {
                            6 -> (0xFF000000L or v).toInt()
                            8 -> v.toInt()
                            else -> null
                        }
                    }
                    s.equals("transparent", true) -> Color.TRANSPARENT
                    else -> Color.parseColor(s)
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun colorToHex(c: Int): String = String.format("#%08X", c)

        private fun orientationToString(o: GradientDrawable.Orientation): String = when (o) {
            GradientDrawable.Orientation.TOP_BOTTOM -> "top_bottom"
            GradientDrawable.Orientation.BOTTOM_TOP -> "bottom_top"
            GradientDrawable.Orientation.LEFT_RIGHT -> "left_right"
            GradientDrawable.Orientation.RIGHT_LEFT -> "right_left"
            GradientDrawable.Orientation.TR_BL -> "tr_bl"
            GradientDrawable.Orientation.BR_TL -> "br_tl"
            GradientDrawable.Orientation.BL_TR -> "bl_tr"
            else -> "tl_br"
        }

        private fun firstString(o: JSONObject, vararg keys: String): String? {
            for (k in keys) {
                if (!o.has(k) || o.isNull(k)) continue
                val v = o.optString(k, "").trim()
                if (v.isNotEmpty()) return v
            }
            return null
        }

        private fun firstNumber(o: JSONObject, vararg keys: String): Double? {
            for (k in keys) {
                if (!o.has(k) || o.isNull(k)) continue
                val n = o.optDouble(k, Double.NaN)
                if (!n.isNaN()) return n
                val asStr = o.optString(k).trim()
                    .removeSuffix("px").removeSuffix("dp").removeSuffix("%")
                    .trim()
                asStr.toDoubleOrNull()?.let { return it }
            }
            return null
        }
    }
}
