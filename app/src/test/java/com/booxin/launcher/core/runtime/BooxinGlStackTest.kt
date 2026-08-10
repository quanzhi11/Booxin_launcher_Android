package com.booxin.launcher.core.runtime

import com.booxin.launcher.core.launch.GlRendererKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BooxinGlStackTest {
    @Test
    fun mitFallbackChain_putsMobileGluesLast() {
        val profile = ModRenderProfile(
            profileId = "sodium",
            preferred = GlRendererKind.BOOXIN_GLUES,
            fallback = listOf(GlRendererKind.VULKAN_ZINK),
            env = emptyMap(),
            matchedMods = listOf("sodium-fabric.jar"),
            features = setOf("multidraw")
        )
        val chain = BooxinGlStack.mitFallbackChain(profile)
        assertEquals(GlRendererKind.BOOXIN_GLUES, chain.first())
        assertEquals(GlRendererKind.MOBILE_GLUES, chain.last())
        assertTrue(chain.indexOf(GlRendererKind.VULKAN_ZINK) < chain.indexOf(GlRendererKind.MOBILE_GLUES))
        assertTrue(chain.indexOf(GlRendererKind.ANGLE) < chain.indexOf(GlRendererKind.MOBILE_GLUES))
    }

    @Test
    fun resolve_withoutZink_usesGl4esMit() {
        val tmp = Files.createTempDirectory("booxin-gl-stack-").toFile()
        try {
            File(tmp, "libgl4es_holy.so").writeText("x")
            val profile = ModRenderProfile(
                profileId = "vanilla",
                preferred = GlRendererKind.BOOXIN_GLUES,
                fallback = emptyList(),
                env = emptyMap(),
                matchedMods = emptyList(),
                features = emptySet()
            )
            val resolved = BooxinGlStack.resolve(tmp, profile)
            assertEquals(BooxinGlEngine.GL4ES_MIT, resolved.engine)
            assertEquals("gl4es", resolved.backendEnv)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun resolve_heavyWithMg_usesMobileGluesCompat() {
        val tmp = Files.createTempDirectory("booxin-gl-stack-mg-").toFile()
        try {
            File(tmp, "libmobileglues.so").writeText("mg")
            File(tmp, "libgl4es_holy.so").writeText("holy")
            val profile = ModRenderProfile(
                profileId = "sodium",
                preferred = GlRendererKind.BOOXIN_GLUES,
                fallback = emptyList(),
                env = emptyMap(),
                matchedMods = listOf("sodium.jar"),
                features = setOf("multidraw")
            )
            val resolved = BooxinGlStack.resolve(tmp, profile)
            assertEquals(BooxinGlEngine.MOBILE_GLUES_COMPAT, resolved.engine)
            assertEquals("mobileglues", resolved.backendEnv)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun resolve_cleanroomPreferredOverMg() {
        val tmp = Files.createTempDirectory("booxin-gl-stack-cr-").toFile()
        try {
            File(tmp, "libbooxingl.so").writeText("cr")
            File(tmp, "libmobileglues.so").writeText("mg")
            File(tmp, "libgl4es_holy.so").writeText("holy")
            val profile = ModRenderProfile(
                profileId = "sodium",
                preferred = GlRendererKind.BOOXIN_GLUES,
                fallback = emptyList(),
                env = emptyMap(),
                matchedMods = listOf("sodium.jar"),
                features = setOf("multidraw")
            )
            val resolved = BooxinGlStack.resolve(tmp, profile)
            assertEquals(BooxinGlEngine.CLEANROOM, resolved.engine)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
