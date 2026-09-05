package com.booxin.launcher.core.version

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Detects Android-incompatible mods.
 *
 * [Severity.HARD]: must disable — wrong arch natives / desktop-only APIs that always crash.
 * Soft heavy mods (MTR, Create, …) are NOT blocked here; they get MobileGlues + config
 * tweaks via [com.booxin.launcher.core.launch.ModCompatPrep] so more packs can run.
 */
object AndroidIncompatibleMods {

    enum class Severity {
        /** Always crash on ARM Android — disable before launch. */
        HARD
    }

    data class Entry(
        val id: String,
        val displayName: String,
        val reason: String,
        val fileHints: List<String> = listOf(id),
        val severity: Severity = Severity.HARD
    )

    data class Match(
        val entry: Entry,
        val modId: String?
    ) {
        val reason: String get() = entry.reason
        val id: String get() = entry.id
    }

    data class ScanResult(
        val disabled: List<String>
    ) {
        /** True when at least one enabled jar was disabled this pass. */
        val changed: Boolean get() = disabled.isNotEmpty()
    }

    /** Only mods that cannot work on ARM Android (wrong .so / glibc / desktop GPU path). */
    val entries: List<Entry> = listOf(
        Entry("axiom", "Axiom", "依赖 x86_64 ImGui，ARM 无法加载"),
        Entry(
            id = "dawn_accessibility",
            displayName = "DawnGuiReader",
            reason = "桌面无障碍/读屏实现，手机不可用",
            fileHints = listOf("dawn_accessibility", "dawnguireader", "dawn-gui")
        ),
        Entry(
            id = "discordipc",
            displayName = "Discord IPC",
            reason = "依赖桌面 Discord 原生/IPC",
            fileHints = listOf("discordipc", "discord-ipc", "discordrpc", "discord-rpc")
        ),
        Entry(
            id = "essential",
            displayName = "Essential",
            reason = "桌面客户端组件，Android 上常直接崩溃",
            fileHints = listOf("essential")
        ),
        Entry("flashback", "Flashback", "依赖 x86_64 ImGui，ARM 无法加载"),
        Entry(
            id = "nvidium",
            displayName = "Nvidium",
            reason = "NVIDIA 桌面显卡路径，手机 GPU 不支持",
            fileHints = listOf("nvidium")
        ),
        Entry(
            id = "physicsmod",
            displayName = "Physics Mod",
            reason = "依赖桌面 PhysX/原生库",
            fileHints = listOf("physicsmod", "physics-mod", "physics_mod")
        ),
        Entry(
            id = "replaymod",
            displayName = "Replay Mod",
            reason = "桌面录像原生路径，手机端无法加载",
            fileHints = listOf("replaymod", "replay-mod")
        ),
        Entry(
            id = "veil",
            displayName = "Veil",
            reason = "依赖 x86_64 ImGui，ARM 无法加载",
            fileHints = listOf("veil")
        ),
        Entry(
            id = "voicechat",
            displayName = "Simple Voice Chat",
            reason = "音频原生库依赖 glibc（libm.so.6），Android 无法加载",
            fileHints = listOf("voicechat", "simple-voice-chat", "simplevoicechat")
        ),
        Entry(
            id = "vulkanmod",
            displayName = "VulkanMod",
            reason = "桌面 Vulkan 渲染路径，手机启动器 GL 桥不兼容",
            fileHints = listOf("vulkanmod", "vulkan-mod")
        ),
        Entry(
            id = "yes_steve_model",
            displayName = "Yes Steve Model",
            reason = "依赖桌面 ImGui/原生库，ARM 上无法加载",
            fileHints = listOf("yes_steve_model", "yesstevemodel", "ysm-")
        ),
        Entry(
            id = "dynamiclights_reforged",
            displayName = "Dynamic Lights Reforged (broken natives)",
            reason = "部分版本含错误架构原生库",
            fileHints = listOf("dynamiclightsreforged")
        )
    )

    private val byId: Map<String, Entry> = entries.associateBy { it.id.lowercase() }

    // ELF e_machine
    private const val EM_X86_64 = 62
    private const val EM_386 = 3
    private const val EM_AARCH64 = 183
    private const val EM_ARM = 40

    fun match(mod: VersionModFile): Match? = matchFile(mod.file)

    fun matchFile(file: File): Match? {
        if (!file.isFile) return null
        val modId = readLoaderModId(file)
        if (modId != null) {
            byId[modId.lowercase()]?.let { return Match(it, modId) }
        }
        val name = file.name.lowercase()
            .removeSuffix(".disabled")
            .removeSuffix(".jar")
        for (entry in entries) {
            if (entry.fileHints.any { hint -> name.contains(hint.lowercase()) }) {
                return Match(entry, modId)
            }
        }
        if (jarHasDesktopOnlyNatives(file)) {
            return Match(
                Entry(
                    id = modId ?: name,
                    displayName = modId ?: file.name,
                    reason = "模组内含 x86/x86_64 原生库，无 ARM 可用库"
                ),
                modId
            )
        }
        return null
    }

    fun describeAll(): List<String> =
        entries.map { "${it.displayName}（${it.id}）：${it.reason}" }

    /** Disable blacklisted / desktop-native jars. Caller should reload the mod list after. */
    fun applyToVersion(versionId: String): List<String> =
        scanAndDisable(versionId).disabled

    /**
     * Scan enabled mods → disable incompatible → return lines for UI/log.
     * Intended flow: scan → disable → reload mod list → continue launch.
     */
    fun scanAndDisable(versionId: String): ScanResult {
        val disabled = ArrayList<String>()
        for (mod in VersionModsManager.list(versionId)) {
            if (!mod.enabled) continue
            val hit = match(mod) ?: continue
            val result = VersionModsManager.toggle(mod)
            if (result.isSuccess) {
                disabled += "${mod.displayName} → 已禁用（${hit.reason}）"
            }
        }
        return ScanResult(disabled)
    }

    /**
     * After a crash, disable jars mentioned by known native/link errors.
     * @return display names that were newly disabled
     */
    fun disableFromCrashText(versionId: String, crashText: String): List<String> {
        if (crashText.isBlank()) return emptyList()
        val lower = crashText.lowercase()
        val hints = buildList {
            if ("em_x86_64" in lower || "instead of em_aarch64" in lower ||
                "imgui-moulberry" in lower || "libimgui" in lower
            ) {
                add("axiom")
                add("flashback")
                add("veil")
                add("yes_steve_model")
            }
            if ("libm.so.6" in lower || "glibc" in lower) {
                add("voicechat")
            }
            for (entry in entries) {
                if (entry.id in lower || entry.fileHints.any { it in lower }) {
                    add(entry.id)
                }
            }
            // Stack frames: com.moulberry.axiom / flashback
            Regex("""com\.moulberry\.([a-z0-9_]+)""", RegexOption.IGNORE_CASE)
                .findAll(crashText)
                .forEach { add(it.groupValues[1].lowercase()) }
        }.distinct()
        if (hints.isEmpty()) return emptyList()

        val disabled = ArrayList<String>()
        for (mod in VersionModsManager.list(versionId)) {
            if (!mod.enabled) continue
            val modId = readLoaderModId(mod.file)?.lowercase()
            val fileName = mod.displayName.lowercase()
            val hit = hints.any { h ->
                modId == h || fileName.contains(h) ||
                    match(mod)?.id?.equals(h, ignoreCase = true) == true
            }
            if (!hit) continue
            if (VersionModsManager.toggle(mod).isSuccess) {
                disabled += mod.displayName
            }
        }
        return disabled
    }

    private fun jarHasDesktopOnlyNatives(jar: File): Boolean = runCatching {
        ZipFile(jar).use { zip ->
            var hasX86 = false
            var hasArm = false
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val n = e.name.lowercase()
                if (!n.endsWith(".so")) continue
                // Path hints first (cheap).
                when {
                    n.contains("x86_64") || n.contains("amd64") ||
                        n.contains("linux-x86") || n.contains("/x64/") -> hasX86 = true
                    n.contains("aarch64") || n.contains("arm64") ||
                        n.contains("linux-arm") -> hasArm = true
                }
                // ELF header for natives/… without arch in path.
                zip.getInputStream(e).use { input ->
                    val hdr = ByteArray(20)
                    val read = input.read(hdr)
                    if (read >= 20 && hdr[0] == 0x7f.toByte() &&
                        hdr[1] == 'E'.code.toByte() &&
                        hdr[2] == 'L'.code.toByte() &&
                        hdr[3] == 'F'.code.toByte()
                    ) {
                        val machine = (hdr[18].toInt() and 0xff) or
                            ((hdr[19].toInt() and 0xff) shl 8)
                        when (machine) {
                            EM_X86_64, EM_386 -> hasX86 = true
                            EM_AARCH64, EM_ARM -> hasArm = true
                        }
                    }
                }
                if (hasX86 && hasArm) return@use false
            }
            hasX86 && !hasArm
        }
    }.getOrDefault(false)

    private fun readLoaderModId(jar: File): String? = runCatching {
        ZipFile(jar).use { zip ->
            readJsonId(zip, "fabric.mod.json")
                ?: readJsonId(zip, "quilt.mod.json")
        }
    }.getOrNull()

    private fun readJsonId(zip: ZipFile, entryName: String): String? {
        val entry = zip.getEntry(entryName) ?: return null
        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        val id = JSONObject(text).optString("id").trim()
        return id.takeIf { it.isNotEmpty() }
    }
}
