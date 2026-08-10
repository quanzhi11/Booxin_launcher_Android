package com.booxin.launcher.core.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class ModRenderProfilerTest {
    @Test
    fun mergeAssetOverlay_readsProfileIds() {
        val json = """
            {"version":1,"profiles":[{"id":"iris"},{"id":"sodium"},{"id":""}]}
        """.trimIndent()
        val ids = ModRenderProfiler.mergeAssetOverlay(json)
        assertTrue(ids.contains("iris"))
        assertTrue(ids.contains("sodium"))
        assertTrue(ids.size == 2)
    }
}
