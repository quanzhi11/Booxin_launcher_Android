package com.booxin.launcher.core.skin

import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSkinServiceTest {

    @Test
    fun parseModes() {
        assertEquals(OfflineSkinMode.STEVE, OfflineSkinMode.parse("steve"))
        assertEquals(OfflineSkinMode.CUSTOM, OfflineSkinMode.parse("custom"))
        assertEquals(OfflineSkinMode.RANDOM, OfflineSkinMode.parse(null))
    }

    @Test
    fun forceSkinModelFlipsParity() {
        val base = "2a386545f1ff370eab061a766a28340a"
        val slim = OfflineSkinService.forceSkinModel(base, slim = true)
        val classic = OfflineSkinService.forceSkinModel(base, slim = false)
        assertTrue(OfflineSkinService.isSlimUuid(slim))
        assertFalse(OfflineSkinService.isSlimUuid(classic))
    }

    @Test
    fun playerUuidKeptOn119ButNot120() {
        val account = LauncherAccount(
            id = "o1",
            name = "Player",
            type = AccountType.OFFLINE,
            uuid = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            skinMode = "player",
            skinPlayerUuid = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        )
        assertEquals(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            OfflineSkinService.resolveLaunchUuid(account, "1.19.2")
        )
        assertEquals(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            OfflineSkinService.resolveLaunchUuid(account, "1.20.1")
        )
    }

    @Test
    fun packFormatMatchesPcTables() {
        assertEquals(1, OfflineSkinService.getPackFormat("1.8.9"))
        assertEquals(9, OfflineSkinService.getPackFormat("1.19.2"))
        assertEquals(34, OfflineSkinService.getPackFormat("1.21.1"))
        assertEquals(84, OfflineSkinService.getPackFormat("26.1.2"))
        assertEquals(88, OfflineSkinService.getPackFormat("26.2"))
    }
}
