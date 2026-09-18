package com.booxin.launcher.core.runtime

import com.booxin.launcher.core.launch.GlRendererKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BooxinZinkRendererTest {
    @Test
    fun selectableKinds_includesBooxinGlues_withoutRemovingBuiltins() {
        val kinds = RendererPackages.selectableKinds()
        assertTrue(kinds.contains(GlRendererKind.BOOXIN_GLUES))
        assertTrue(kinds.contains(GlRendererKind.MOBILE_GLUES))
        assertTrue(kinds.contains(GlRendererKind.REL))
        assertTrue(kinds.contains(GlRendererKind.MCRENDER))
        assertTrue(kinds.contains(GlRendererKind.GL4ES))
        assertTrue(
            kinds.indexOf(GlRendererKind.BOOXIN_GLUES) <
                kinds.indexOf(GlRendererKind.VULKAN_ZINK)
        )
    }

    @Test
    fun booxinGlues_package_isMesaZinkForPearl() {
        val pkg = RendererPackages.forKind(GlRendererKind.BOOXIN_GLUES)
        requireNotNull(pkg)
        assertEquals("libOSMesa_25.so", pkg.glLib)
        assertEquals("zink", pkg.extraEnv["GALLIUM_DRIVER"])
        assertEquals("1", pkg.extraEnv["BOOXIN_GLUES"])
        assertEquals("4.6", pkg.extraEnv["MESA_GL_VERSION_OVERRIDE"])
        assertEquals(false, GlRendererKind.BOOXIN_GLUES.requiresPlugin)
    }
}
