package com.booxin.launcher.core.multiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialServerCatalogTest {

    @Test
    fun parseGuanfuSample() {
        val text = """
            server ip:"example.com:25565"
            name:"Booxin sample server"
            version:"1.21.11"
            mod_forge:"61.0.1"
            mod list"https://example.com/mods/sample-mod-1.0.0.jar"//每个一行URL
            time:7-21
        """.trimIndent()

        val servers = OfficialServerCatalog.parse(text)
        assertEquals("servers=$servers", 1, servers.size)
        val s = servers.first()
        assertEquals("Booxin sample server", s.name)
        assertEquals("example.com", s.host)
        assertEquals(25565, s.port)
        assertEquals("1.21.11", s.version)
        assertEquals("61.0.1", s.forgeVersion)
        assertEquals("mods=${s.modUrls}", 1, s.modUrls.size)
        assertTrue(s.modUrls.first().contains("sample-mod"))
        assertEquals(7, s.modsUpdatedAt?.monthValue)
        assertEquals(21, s.modsUpdatedAt?.dayOfMonth)
        assertEquals("example.com:25565", s.serverAddress)
    }
}
