package com.booxin.launcher.core.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OfflineAuthTest {

    @Test
    fun uuidIsStableForSameUsername() {
        val a = OfflineAuth.uuidString("Steve")
        val b = OfflineAuth.uuidString("Steve")
        assertEquals(a, b)
        assertEquals(32, OfflineAuth.uuidNoDash("Steve").length)
        assertFalse(OfflineAuth.uuidNoDash("Steve").contains("-"))
    }

    @Test
    fun differentUsersGetDifferentUuids() {
        assertFalse(OfflineAuth.uuidString("Steve") == OfflineAuth.uuidString("Alex"))
    }
}
