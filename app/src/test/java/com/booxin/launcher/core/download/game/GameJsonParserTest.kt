package com.booxin.launcher.core.download.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameJsonParserTest {

    @Test
    fun parsesManifest() {
        val json = """
            {
              "latest": {"release":"1.21.4","snapshot":"24w46a"},
              "versions": [
                {"id":"1.21.4","type":"release","url":"https://example/1.21.4.json","releaseTime":"2024-12-01T00:00:00+00:00"},
                {"id":"24w46a","type":"snapshot","url":"https://example/24w46a.json","releaseTime":"2024-11-01T00:00:00+00:00"}
              ]
            }
        """.trimIndent()
        val manifest = GameJsonParser.parseManifest(json)
        assertEquals("1.21.4", manifest.latestRelease)
        assertEquals(2, manifest.versions.size)
        assertEquals("https://example/1.21.4.json", manifest.versions[0].url)
    }

    @Test
    fun keepsLwjglClassJarsDropsNativesOnly() {
        val json = """
            {
              "id":"1.20.4",
              "mainClass":"net.minecraft.client.main.Main",
              "assetIndex":{"id":"16","url":"https://example/16.json","sha1":"abc","size":1,"totalSize":1},
              "downloads":{"client":{"url":"https://example/client.jar","sha1":"def","size":10}},
              "libraries":[
                {"name":"com.mojang:logging:1.1.1","downloads":{"artifact":{"path":"com/mojang/logging/1.1.1/logging-1.1.1.jar","sha1":"aaa","url":"https://libraries.minecraft.net/com/mojang/logging/1.1.1/logging-1.1.1.jar"}}},
                {"name":"org.lwjgl:lwjgl:3.3.1","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar","sha1":"bbb","url":"https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar"}}},
                {"name":"org.lwjgl:lwjgl-opengl:3.3.1","natives":{"linux":"natives-linux"},"downloads":{"artifact":{"path":"org/lwjgl/lwjgl-opengl/3.3.1/lwjgl-opengl-3.3.1.jar","sha1":"ccc","url":"https://libraries.minecraft.net/org/lwjgl/lwjgl-opengl/3.3.1/lwjgl-opengl-3.3.1.jar"},"classifiers":{"natives-linux":{"path":"org/lwjgl/lwjgl-opengl/3.3.1/lwjgl-opengl-3.3.1-natives-linux.jar"}}}}
              ]
            }
        """.trimIndent()
        val version = GameJsonParser.parseVersionJson(json)
        assertEquals(3, version.libraries.size)
        assertTrue(version.libraries.any { it.name == "org.lwjgl:lwjgl:3.3.1" })
        assertTrue(version.libraries.any { it.name == "org.lwjgl:lwjgl-opengl:3.3.1" })
        assertTrue(version.client?.url?.contains("client.jar") == true)
    }

    @Test
    fun mavenPathBuildsCorrectly() {
        assertEquals(
            "com/mojang/logging/1.1.1/logging-1.1.1.jar",
            GameJsonParser.mavenPath("com.mojang:logging:1.1.1")
        )
    }
}
