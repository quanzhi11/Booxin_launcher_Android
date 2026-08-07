package com.booxin.launcher.ui.launch.input

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persists custom on-screen control layout across launches (:game process shares filesDir).
 */
object ControlLayoutStore {

    private const val FILE_NAME = "control_layout.json"
    private const val LAYOUT_VERSION = 4

    fun load(context: Context): ControlLayoutData {
        val file = file(context)
        if (!file.isFile) return default()
        return try {
            val root = JSONObject(file.readText())
            val savedVersion = root.optInt("version", 0)
            if (savedVersion < LAYOUT_VERSION) return default()
            val buttons = ensureSoftKeyboard(parseButtons(root.optJSONArray("buttons")))
            val joy = root.optJSONObject("joystick")
            val ball = root.optJSONObject("floatingBall")
            val gesture = root.optJSONObject("gestureQuick")
            ControlLayoutData(
                buttons = buttons.ifEmpty { ControlCatalog.defaultLayout() },
                joystick = ControlLayoutData.JoystickSpec(
                    x = joy?.optDouble("x", 0.13)?.toFloat()?.coerceIn(0.08f, 0.92f) ?: 0.13f,
                    y = joy?.optDouble("y", 0.84)?.toFloat()?.coerceIn(0.12f, 0.92f) ?: 0.84f,
                    sizeDp = joy?.optInt("sizeDp", 150)?.coerceIn(120, 220) ?: 150
                ),
                floatingBall = ControlLayoutData.FloatingBallSpec(
                    x = ball?.optDouble("x", 0.96)?.toFloat()?.coerceIn(0.05f, 0.95f) ?: 0.96f,
                    y = ball?.optDouble("y", 0.38)?.toFloat()?.coerceIn(0.05f, 0.95f) ?: 0.38f
                ),
                gestureQuick = ControlLayoutData.GestureQuickSpec(
                    x = gesture?.optDouble("x", 0.96)?.toFloat()?.coerceIn(0.05f, 0.95f) ?: 0.96f,
                    y = gesture?.optDouble("y", 0.26)?.toFloat()?.coerceIn(0.05f, 0.95f) ?: 0.26f
                )
            )
        } catch (_: Exception) {
            default()
        }
    }

    fun save(context: Context, data: ControlLayoutData) {
        val arr = JSONArray()
        data.buttons.forEach { b ->
            arr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("label", b.label)
                    .put("kind", b.kind.name)
                    .put("code", b.code)
                    .put("x", b.x.toDouble())
                    .put("y", b.y.toDouble())
                    .put("sizeDp", b.sizeDp)
                    .also { obj ->
                        if (b.codes.size >= 2) {
                            obj.put("codes", JSONArray(b.codes))
                        }
                    }
            )
        }
        val json = JSONObject()
            .put("version", LAYOUT_VERSION)
            .put("buttons", arr)
            .put(
                "joystick",
                JSONObject()
                    .put("x", data.joystick.x.toDouble())
                    .put("y", data.joystick.y.toDouble())
                    .put("sizeDp", data.joystick.sizeDp)
            )
            .put(
                "floatingBall",
                JSONObject()
                    .put("x", data.floatingBall.x.toDouble())
                    .put("y", data.floatingBall.y.toDouble())
            )
            .put(
                "gestureQuick",
                JSONObject()
                    .put("x", data.gestureQuick.x.toDouble())
                    .put("y", data.gestureQuick.y.toDouble())
            )
        file(context).writeText(json.toString())
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun default(): ControlLayoutData = ControlLayoutData(
        buttons = ControlCatalog.defaultLayout()
    )

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    private fun parseButtons(arr: JSONArray?): List<ControlButtonSpec> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(parseButton(o) ?: continue)
            }
        }
    }

    private fun parseButton(o: JSONObject): ControlButtonSpec? {
        val kindName = o.optString("kind")
        val kind = runCatching { ControlButtonSpec.Kind.valueOf(kindName) }.getOrNull() ?: return null
        val codesArr = o.optJSONArray("codes")
        val codes = if (codesArr != null && codesArr.length() >= 2) {
            buildList {
                for (i in 0 until codesArr.length()) {
                    add(codesArr.optInt(i))
                }
            }.filter { it != 0 || kind == ControlButtonSpec.Kind.SOFT_KEYBOARD }
        } else {
            emptyList()
        }
        val code = o.optInt("code").let { primary ->
            if (primary == 0 && codes.isNotEmpty()) codes.last() else primary
        }
        return ControlButtonSpec(
            id = o.optString("id").ifBlank { newId() },
            label = o.optString("label").ifBlank { "?" },
            kind = kind,
            code = code,
            x = o.optDouble("x", 0.5).toFloat().coerceIn(0.05f, 0.95f),
            y = o.optDouble("y", 0.5).toFloat().coerceIn(0.05f, 0.95f),
            sizeDp = o.optInt("sizeDp", 52).coerceIn(36, 96),
            codes = codes
        )
    }

    private fun ensureSoftKeyboard(buttons: List<ControlButtonSpec>): List<ControlButtonSpec> {
        if (buttons.isEmpty()) return buttons
        if (buttons.any { it.kind == ControlButtonSpec.Kind.SOFT_KEYBOARD }) return buttons
        return buttons + ControlCatalog.softKeyboardButton()
    }
}
