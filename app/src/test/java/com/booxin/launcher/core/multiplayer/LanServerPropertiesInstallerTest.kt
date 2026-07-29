package com.booxin.launcher.core.multiplayer

import com.booxin.launcher.core.java.MinecraftJavaRequirement
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanServerPropertiesInstallerTest {

    @Test
    fun prefersSameMinorWhenResolving1161Fallback() {
        val target = MinecraftJavaRequirement.parseVersion("1.16.1")!!
        val d162 = LanServerPropertiesInstaller.versionDistance(
            target,
            MinecraftJavaRequirement.parseVersion("1.16.2")!!
        )
        val d155 = LanServerPropertiesInstaller.versionDistance(
            target,
            MinecraftJavaRequirement.parseVersion("1.15.2")!!
        )
        val d171 = LanServerPropertiesInstaller.versionDistance(
            target,
            MinecraftJavaRequirement.parseVersion("1.17.1")!!
        )
        assertTrue(d162 < d155)
        assertTrue(d162 < d171)
    }

    @Test
    fun resolveGameVersionUsesInheritsFrom() {
        val root = JSONObject("""{"id":"1.16.5-fabric","inheritsFrom":"1.16.5","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient"}""")
        assertEquals(
            "1.16.5",
            LanServerPropertiesInstaller.resolveGameVersion("1.16.5-fabric", root)
        )
    }

    @Test
    fun parseFabricLoaderStyleIds() {
        assertEquals(
            Triple(1, 16, 5),
            MinecraftJavaRequirement.parseVersion("fabric-loader-0.14.22-1.16.5")
        )
        assertEquals(8, MinecraftJavaRequirement.requiredMajor("fabric-loader-0.14.22-1.16.5"))
        assertEquals(8, MinecraftJavaRequirement.requiredMajor("1.16.1"))
        assertEquals(8, MinecraftJavaRequirement.requiredMajor("1.12.2"))
    }
}
