package com.booxin.launcher.core.java

import org.junit.Assert.assertEquals
import org.junit.Test

class MinecraftJavaRequirementTest {

    @Test
    fun mapsLegacyVersionsToJava8() {
        assertEquals(8, MinecraftJavaRequirement.requiredMajor("1.12.2"))
        assertEquals(8, MinecraftJavaRequirement.requiredMajor("1.16.1"))
        assertEquals(8, MinecraftJavaRequirement.requiredMajor("1.16.5"))
    }

    @Test
    fun mapsModernVersionsToJava17() {
        assertEquals(17, MinecraftJavaRequirement.requiredMajor("1.17"))
        assertEquals(17, MinecraftJavaRequirement.requiredMajor("1.20.4"))
    }

    @Test
    fun mapsRecentVersionsToJava21() {
        assertEquals(21, MinecraftJavaRequirement.requiredMajor("1.20.5"))
        assertEquals(21, MinecraftJavaRequirement.requiredMajor("1.21.4"))
    }

    @Test
    fun mapsYearBasedVersionsToJava25() {
        assertEquals(25, MinecraftJavaRequirement.requiredMajor("26.2"))
        assertEquals(25, MinecraftJavaRequirement.requiredMajor("26.1"))
    }
}
