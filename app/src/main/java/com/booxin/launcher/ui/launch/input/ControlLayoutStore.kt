package com.booxin.launcher.ui.launch.input

import android.content.Context
import com.booxin.launcher.core.uiplugin.UiPluginExtraLayout
import com.booxin.launcher.core.uiplugin.UiPluginManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persists custom on-screen control layout across launches (:game process shares filesDir).
 *
 * When an enabled controlLayout UI plugin is present, its JSON supplies chrome
 * (icons / styles). The local user save is authoritative for which buttons exist
 * and their geometry — so add/delete/move survive the next launch.
 *
 * Extra panels (`extraLayouts`) use separate user save files per panel id.
 */
object ControlLayoutStore {

    private const val FILE_NAME = "control_layout.json"
    private const val ACTIVE_LAYOUT_PREF = "booxin_control_active_layout"
    private const val LAYOUT_VERSION = 4
    const val SIZE_DP_MIN = 36
    const val SIZE_DP_MAX = 160

    fun load(context: Context): ControlLayoutData {
        val layoutId = activeLayoutId(context)
        val pluginData = UiPluginManager.resolveControlLayoutFile(layoutId)
            ?.let { parseFile(it, requireMinVersion = false) }
        val localFile = fileForLayout(context, layoutId)
        val localData = if (localFile.isFile) {
            parseFile(localFile, requireMinVersion = true)
        } else {
            null
        }
        return when {
            pluginData != null && localData != null ->
                migrateStockLabels(mergePluginWithUser(pluginData, localData))
            pluginData != null -> migrateStockLabels(ensureSoftKeyboardLayout(pluginData))
            localData != null -> migrateStockLabels(localData)
            else -> default()
        }
    }

    private fun migrateStockLabels(data: ControlLayoutData): ControlLayoutData {
        var changed = false
        val buttons = data.buttons.map { b ->
            val next = ControlCatalog.migratedStockLabel(b.id, b.label)
            if (next != b.label) {
                changed = true
                b.copy(label = next)
            } else {
                b
            }
        }
        return if (changed) data.copy(buttons = buttons) else data
    }

    fun save(context: Context, data: ControlLayoutData) {
        save(context, data, activeLayoutId(context))
    }

    fun save(context: Context, data: ControlLayoutData, layoutId: String) {
        val defaultStyle = data.buttonStyle
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
                        if (b.icon.isNotBlank()) {
                            obj.put("icon", b.icon)
                        }
                        if (b.outputText.isNotBlank()) {
                            obj.put("outputText", b.outputText)
                        }
                        b.style?.toJson()?.let { styleJson ->
                            obj.put("style", styleJson)
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
                    .put("follow", data.joystick.follow)
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
        defaultStyle?.toJson()?.let { json.put("buttonStyle", it) }
        fileForLayout(context, layoutId).writeText(json.toString())
    }

    fun activeLayoutId(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(ACTIVE_LAYOUT_PREF, Context.MODE_PRIVATE)
        val saved = prefs.getString("id", UiPluginManager.MAIN_LAYOUT_ID)
            ?.trim()
            .orEmpty()
        val known = listControlLayouts().map { it.id }
        if (known.isEmpty()) return UiPluginManager.MAIN_LAYOUT_ID
        if (saved.isNotEmpty() && known.any { it.equals(saved, ignoreCase = true) }) {
            return known.first { it.equals(saved, ignoreCase = true) }
        }
        return UiPluginManager.MAIN_LAYOUT_ID
    }

    fun setActiveLayoutId(context: Context, layoutId: String) {
        val id = layoutId.trim().ifBlank { UiPluginManager.MAIN_LAYOUT_ID }
        context.applicationContext
            .getSharedPreferences(ACTIVE_LAYOUT_PREF, Context.MODE_PRIVATE)
            .edit()
            .putString("id", id)
            .apply()
    }

    fun listControlLayouts(): List<UiPluginExtraLayout> =
        UiPluginManager.listControlLayouts()

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun default(): ControlLayoutData = ControlLayoutData(
        buttons = ControlCatalog.defaultLayout()
    )

    /**
     * Look up chrome (icon / style / label) from the active controlLayout plugin
     * for a newly added key. Matches by id-less kind+code (HOLD/FOLLOW treated alike).
     */
    fun findPluginChrome(
        context: Context,
        kind: ControlButtonSpec.Kind,
        code: Int,
        codes: List<Int> = emptyList(),
        outputText: String = ""
    ): ControlButtonSpec? {
        val layoutId = activeLayoutId(context)
        val plugin = UiPluginManager.resolveControlLayoutFile(layoutId)
            ?.let { parseFile(it, requireMinVersion = false) }
            ?: return null
        return matchPluginButton(plugin.buttons, kind, code, codes, outputText)
            ?.let { pb ->
                pb.copy(
                    // Don't reuse plugin id — caller assigns a fresh user id.
                    style = pb.style ?: plugin.buttonStyle
                )
            }
            ?: plugin.buttonStyle?.let { style ->
                // No per-key match; still return a stub so caller can apply layout style.
                ControlButtonSpec(
                    id = "",
                    label = "",
                    kind = kind,
                    code = code,
                    x = 0.5f,
                    y = 0.5f,
                    style = style
                )
            }
    }

    private fun matchPluginButton(
        pluginButtons: List<ControlButtonSpec>,
        kind: ControlButtonSpec.Kind,
        code: Int,
        codes: List<Int>,
        outputText: String
    ): ControlButtonSpec? {
        val exact = kindCodeKey(kind, code, codes, outputText)
        pluginButtons.firstOrNull { kindCodeKey(it) == exact }?.let { return it }
        val baseKind = chromeBaseKind(kind)
        return pluginButtons.firstOrNull {
            chromeBaseKind(it.kind) == baseKind &&
                it.code == code &&
                (codes.isEmpty() || it.codes == codes || it.codes.isEmpty())
        }
    }

    /** FOLLOW/TOGGLE share chrome with the corresponding HOLD kind. */
    private fun chromeBaseKind(kind: ControlButtonSpec.Kind): ControlButtonSpec.Kind = when (kind) {
        ControlButtonSpec.Kind.KEY_FOLLOW, ControlButtonSpec.Kind.KEY_TOGGLE ->
            ControlButtonSpec.Kind.KEY_HOLD
        ControlButtonSpec.Kind.MOUSE_FOLLOW, ControlButtonSpec.Kind.MOUSE_TOGGLE ->
            ControlButtonSpec.Kind.MOUSE_HOLD
        else -> kind
    }

    /**
     * User file is authoritative for *which* buttons exist (add/delete).
     * Plugin supplies chrome (icon / default style / labels) for matching keys.
     */
    private fun mergePluginWithUser(
        plugin: ControlLayoutData,
        user: ControlLayoutData
    ): ControlLayoutData {
        val pluginById = plugin.buttons.associateBy { it.id }

        val merged = user.buttons.map { ub ->
            val pb = pluginById[ub.id]
                ?: matchPluginButton(plugin.buttons, ub.kind, ub.code, ub.codes, ub.outputText)
            if (pb != null) {
                pb.copy(
                    // Keep user's stable id so the next save still matches this row.
                    id = ub.id,
                    x = ub.x,
                    y = ub.y,
                    sizeDp = ub.sizeDp,
                    // Follow / remapped codes come from the user edit session.
                    kind = ub.kind,
                    code = ub.code,
                    codes = if (ub.codes.isNotEmpty()) ub.codes else pb.codes,
                    label = when {
                        ub.label.isNotBlank() && ub.label != "?" -> ub.label
                        pb.label.isNotBlank() -> pb.label
                        else -> ub.label
                    },
                    icon = ub.icon.ifBlank { pb.icon },
                    style = ub.style ?: pb.style ?: plugin.buttonStyle,
                    outputText = ub.outputText.ifBlank { pb.outputText }
                )
            } else {
                // User-added key with no plugin twin — still inherit layout buttonStyle.
                ub.copy(style = ub.style ?: plugin.buttonStyle)
            }
        }.toMutableList()

        if (merged.none { it.kind == ControlButtonSpec.Kind.SOFT_KEYBOARD }) {
            val pluginKbd = plugin.buttons.firstOrNull {
                it.kind == ControlButtonSpec.Kind.SOFT_KEYBOARD
            }
            merged += pluginKbd ?: ControlCatalog.softKeyboardButton()
        }

        return plugin.copy(
            buttons = merged,
            joystick = user.joystick,
            floatingBall = user.floatingBall,
            gestureQuick = user.gestureQuick,
            buttonStyle = plugin.buttonStyle ?: user.buttonStyle
        )
    }

    private fun kindCodeKey(b: ControlButtonSpec): String =
        kindCodeKey(b.kind, b.code, b.codes, b.outputText)

    private fun kindCodeKey(
        kind: ControlButtonSpec.Kind,
        code: Int,
        codes: List<Int>,
        outputText: String
    ): String = "${kind.name}:$code:${codes.joinToString(",")}:$outputText"

    private fun ensureSoftKeyboardLayout(data: ControlLayoutData): ControlLayoutData {
        val buttons = ensureSoftKeyboard(data.buttons)
        return if (buttons === data.buttons) data else data.copy(buttons = buttons)
    }

    private fun fileForLayout(context: Context, layoutId: String): File {
        val id = layoutId.trim().ifBlank { UiPluginManager.MAIN_LAYOUT_ID }
        val name = if (id.equals(UiPluginManager.MAIN_LAYOUT_ID, ignoreCase = true)) {
            FILE_NAME
        } else {
            "control_layout_${sanitizeId(id)}.json"
        }
        return File(context.applicationContext.filesDir, name)
    }

    private fun sanitizeId(id: String): String =
        id.map { ch -> if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_' }
            .joinToString("")
            .ifBlank { "panel" }

    private fun parseFile(file: File, requireMinVersion: Boolean): ControlLayoutData? {
        return try {
            val root = JSONObject(file.readText())
            val savedVersion = root.optInt("version", LAYOUT_VERSION)
            if (requireMinVersion && savedVersion < LAYOUT_VERSION) return null
            val defaultStyle = ControlButtonStyle.fromJson(
                root.optJSONObject("buttonStyle")
                    ?: root.optJSONObject("buttonCss")
                    ?: root.optJSONObject("css")
            )
            val buttons = ensureSoftKeyboard(parseButtons(root.optJSONArray("buttons"), defaultStyle))
            val joy = root.optJSONObject("joystick")
            val ball = root.optJSONObject("floatingBall")
            val gesture = root.optJSONObject("gestureQuick")
            ControlLayoutData(
                buttons = buttons.ifEmpty { ControlCatalog.defaultLayout() },
                joystick = ControlLayoutData.JoystickSpec(
                    x = joy?.optDouble("x", 0.13)?.toFloat()?.coerceIn(0.08f, 0.92f) ?: 0.13f,
                    y = joy?.optDouble("y", 0.84)?.toFloat()?.coerceIn(0.12f, 0.92f) ?: 0.84f,
                    sizeDp = joy?.optInt("sizeDp", 150)?.coerceIn(120, 220) ?: 150,
                    follow = joy?.optBoolean("follow", false) ?: false
                ),
                floatingBall = ControlLayoutData.FloatingBallSpec(
                    x = ball?.optDouble("x", 0.96)?.toFloat()?.coerceIn(0.05f, 0.95f) ?: 0.96f,
                    y = ball?.optDouble("y", 0.38)?.toFloat()?.coerceIn(0.05f, 0.95f) ?: 0.38f
                ),
                gestureQuick = ControlLayoutData.GestureQuickSpec(
                    x = gesture?.optDouble("x", 0.96)?.toFloat()?.coerceIn(0.05f, 0.95f) ?: 0.96f,
                    y = gesture?.optDouble("y", 0.26)?.toFloat()?.coerceIn(0.05f, 0.95f) ?: 0.26f
                ),
                buttonStyle = defaultStyle
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseButtons(
        arr: JSONArray?,
        defaultStyle: ControlButtonStyle?
    ): List<ControlButtonSpec> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(parseButton(o, defaultStyle) ?: continue)
            }
        }
    }

    private fun parseButton(o: JSONObject, defaultStyle: ControlButtonStyle?): ControlButtonSpec? {
        var kindName = o.optString("kind")
        var kind = runCatching { ControlButtonSpec.Kind.valueOf(kindName) }.getOrNull()
            ?: return null
        val outputText = o.optString("outputText").trim()
            .ifBlank { o.optString("text").trim() }

        // Accept both "codes": [...] and "code": [...] (FCL convert style).
        val codesArr = o.optJSONArray("codes") ?: o.optJSONArray("code")
        val rawCodes = if (codesArr != null && codesArr.length() > 0) {
            buildList {
                for (i in 0 until codesArr.length()) {
                    add(codesArr.optInt(i))
                }
            }
        } else {
            listOf(o.optInt("code"))
        }

        val normalized = normalizeSpecialCodes(rawCodes, kind, outputText)
        kind = normalized.kind
        val codes = normalized.codes
        val code = normalized.code

        val perStyle = ControlButtonStyle.fromJson(
            o.optJSONObject("style") ?: o.optJSONObject("css")
        )
        val merged = when {
            perStyle != null && defaultStyle != null -> perStyle.mergeOver(defaultStyle)
            perStyle != null -> perStyle
            defaultStyle != null -> defaultStyle
            else -> null
        }
        return ControlButtonSpec(
            id = o.optString("id").ifBlank { newId() },
            label = o.optString("label").ifBlank { "?" },
            kind = kind,
            code = code,
            x = o.optDouble("x", 0.5).toFloat().coerceIn(0.05f, 0.95f),
            y = o.optDouble("y", 0.5).toFloat().coerceIn(0.05f, 0.95f),
            sizeDp = o.optInt("sizeDp", 52).coerceIn(SIZE_DP_MIN, SIZE_DP_MAX),
            codes = codes,
            icon = o.optString("icon").trim(),
            style = merged,
            outputText = outputText
        )
    }

    /**
     * FCL special codes from convert.py:
     * -1 left mouse, -2 copy (Ctrl+C), -3 right mouse, -4 left arrow, -5 right arrow.
     */
    private data class NormalizedCodes(
        val kind: ControlButtonSpec.Kind,
        val code: Int,
        val codes: List<Int>
    )

    private fun normalizeSpecialCodes(
        raw: List<Int>,
        kind: ControlButtonSpec.Kind,
        outputText: String
    ): NormalizedCodes {
        if (raw.isEmpty()) {
            return NormalizedCodes(kind, 0, emptyList())
        }
        if (raw.size == 1) {
            when (raw[0]) {
                -1 -> return NormalizedCodes(
                    when (kind) {
                        ControlButtonSpec.Kind.KEY_FOLLOW,
                        ControlButtonSpec.Kind.MOUSE_FOLLOW -> ControlButtonSpec.Kind.MOUSE_FOLLOW
                        ControlButtonSpec.Kind.MOUSE_TOGGLE -> ControlButtonSpec.Kind.MOUSE_TOGGLE
                        else -> ControlButtonSpec.Kind.MOUSE_HOLD
                    },
                    GlfwKeys.MOUSE_LEFT,
                    emptyList()
                )
                -3 -> return NormalizedCodes(
                    when (kind) {
                        ControlButtonSpec.Kind.KEY_FOLLOW,
                        ControlButtonSpec.Kind.MOUSE_FOLLOW -> ControlButtonSpec.Kind.MOUSE_FOLLOW
                        ControlButtonSpec.Kind.MOUSE_TOGGLE -> ControlButtonSpec.Kind.MOUSE_TOGGLE
                        else -> ControlButtonSpec.Kind.MOUSE_HOLD
                    },
                    GlfwKeys.MOUSE_RIGHT,
                    emptyList()
                )
                -2 -> return NormalizedCodes(
                    ControlButtonSpec.Kind.KEY_TAP,
                    GlfwKeys.KEY_C,
                    listOf(GlfwKeys.KEY_LEFT_CONTROL, GlfwKeys.KEY_C)
                )
                -4 -> return NormalizedCodes(kind, GlfwKeys.KEY_LEFT, emptyList())
                -5 -> return NormalizedCodes(kind, GlfwKeys.KEY_RIGHT, emptyList())
            }
        }

        val mapped = raw.map { c ->
            when (c) {
                -4 -> GlfwKeys.KEY_LEFT
                -5 -> GlfwKeys.KEY_RIGHT
                else -> c
            }
        }.filter {
            it != 0 || kind == ControlButtonSpec.Kind.SOFT_KEYBOARD || outputText.isNotBlank()
        }

        // Text-only command buttons use code 0 + outputText.
        if (mapped.isEmpty() && outputText.isNotBlank()) {
            return NormalizedCodes(ControlButtonSpec.Kind.KEY_TAP, 0, emptyList())
        }

        return when {
            mapped.size >= 2 -> NormalizedCodes(kind, mapped.last(), mapped)
            mapped.size == 1 -> NormalizedCodes(kind, mapped[0], emptyList())
            else -> NormalizedCodes(kind, 0, emptyList())
        }
    }

    private fun ensureSoftKeyboard(buttons: List<ControlButtonSpec>): List<ControlButtonSpec> {
        if (buttons.isEmpty()) return buttons
        if (buttons.any { it.kind == ControlButtonSpec.Kind.SOFT_KEYBOARD }) return buttons
        return buttons + ControlCatalog.softKeyboardButton()
    }
}
