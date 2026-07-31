package com.booxin.launcher.core.multiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialServerCatalogTest {

    @Test
    fun parseGuanfuSample() {
        val text = """
            server ip:"svip2.minekuai.com:23825"
            name:"Booxin launcher官方服务器"
            version:"1.21.11"
            mod_forge:"61.0.1"
            mod list"http://www.boonix.art/booxin_server_auth-1.0.0.jar"//每个一行URL
            time:7-21
        """.trimIndent()

        val servers = OfficialServerCatalog.parse(text)
        assertEquals("servers=$servers", 1, servers.size)
        val s = servers.first()
        assertEquals("Booxin launcher官方服务器", s.name)
        assertEquals("svip2.minekuai.com", s.host)
        assertEquals(23825, s.port)
        assertEquals("1.21.11", s.version)
        assertEquals("61.0.1", s.forgeVersion)
        assertEquals("mods=${s.modUrls}", 1, s.modUrls.size)
        assertTrue(s.modUrls.first().contains("booxin_server_auth"))
        assertEquals(7, s.modsUpdatedAt?.monthValue)
        assertEquals(21, s.modsUpdatedAt?.dayOfMonth)
        assertEquals("svip2.minekuai.com:23825", s.serverAddress)
    }
}
