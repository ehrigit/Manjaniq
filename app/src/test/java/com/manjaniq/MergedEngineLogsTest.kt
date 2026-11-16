package com.manjaniq

import android.util.Log
import com.manjaniq.core.EngineLog
import com.manjaniq.ui.screens.AETHER_SEQ_BASE
import com.manjaniq.ui.screens.aetherLevelOf
import com.manjaniq.ui.screens.aetherSeqOf
import com.manjaniq.ui.screens.mergeEngineLogs
import com.manjaniq.ui.server.AetherLogEntry
import com.manjaniq.ui.server.AetherLogText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergedEngineLogsTest {

    private fun rsta(seq: Long, timeMs: Long, level: String, message: String) =
        EngineLog(seq = seq, timeMs = timeMs, level = level, message = message)

    private fun aether(seq: Long, timeMs: Long, priority: Int, value: String) =
        AetherLogEntry(id = seq, priority = priority, text = AetherLogText.Raw(value), timeMs = timeMs)

    private val resolve: (AetherLogText) -> String = { text ->
        when (text) {
            is AetherLogText.Raw -> text.value
            is AetherLogText.Resource -> "res:${text.resId}(${text.args.joinToString(",")})"
        }
    }

    @Test
    fun aetherLinesCarryADisjointSequenceRange() {
        assertTrue(aetherSeqOf(0) >= AETHER_SEQ_BASE)
        assertTrue(aetherSeqOf(1) > aetherSeqOf(0))
        assertTrue(aetherSeqOf(1) < (AETHER_SEQ_BASE shl 10)) // still comfortably beyond any engine ring seq
    }

    @Test
    fun prioritiesMapOntoTheLevelsTheLogsScreenFilters() {
        assertEquals("ERROR", aetherLevelOf(Log.ERROR))
        assertEquals("WARN", aetherLevelOf(Log.WARN))
        assertEquals("INFO", aetherLevelOf(Log.INFO))
        assertEquals("DEBUG", aetherLevelOf(Log.DEBUG))
        assertEquals("DEBUG", aetherLevelOf(Log.VERBOSE))
        assertEquals("INFO", aetherLevelOf(999))
    }

    @Test
    fun bothEnginesInterleaveByWallClockTime() {
        val merged = mergeEngineLogs(
            rsta = listOf(
                rsta(seq = 7, timeMs = 1_000, level = "INFO", message = "rsta first"),
                rsta(seq = 8, timeMs = 3_000, level = "WARN", message = "rsta third"),
            ),
            aether = listOf(
                aether(seq = 10_000, timeMs = 2_000, priority = Log.INFO, value = "aether second"),
            ),
            resolve = resolve,
        )

        assertEquals(
            listOf("rsta first", "[Aether] aether second", "rsta third"),
            merged.map { it.message },
        )
        assertEquals(listOf(1_000L, 2_000L, 3_000L), merged.map { it.timeMs })
    }

    @Test
    fun aetherLinesKeepLevelsAndAreTaggedAsAether() {
        val merged = mergeEngineLogs(
            rsta = emptyList(),
            aether = listOf(
                aether(seq = 10_000, timeMs = 1, priority = Log.ERROR, value = "died"),
                aether(seq = 10_001, timeMs = 2, priority = Log.WARN, value = "suspect"),
            ),
            resolve = resolve,
        )

        assertEquals(listOf("ERROR", "WARN"), merged.map { it.level })
        assertEquals(listOf("[Aether] died", "[Aether] suspect"), merged.map { it.message })
        // No key collision with RSTA lines.
        assertTrue(merged.all { it.seq >= AETHER_SEQ_BASE })
    }

    @Test
    fun resourceTextsAreResolvedThroughTheCaller() {
        val merged = mergeEngineLogs(
            rsta = emptyList(),
            aether = listOf(
                AetherLogEntry(
                    id = 10_000,
                    priority = Log.INFO,
                    text = AetherLogText.Resource(1234, listOf("a", "b")),
                    timeMs = 5,
                ),
            ),
            resolve = resolve,
        )

        assertEquals("[Aether] res:1234(a,b)", merged.single().message)
    }

    @Test
    fun mergedLinesStaySortedWhenBothClocksAgree() {
        val merged = mergeEngineLogs(
            rsta = listOf(rsta(seq = 3, timeMs = 50, level = "INFO", message = "ring old")),
            aether = listOf(
                aether(seq = 10_001, timeMs = 50, priority = Log.INFO, value = "aether new"),
                aether(seq = 10_000, timeMs = 50, priority = Log.INFO, value = "aether older"),
            ),
            resolve = resolve,
        )

        assertEquals(listOf("ring old", "[Aether] aether older", "[Aether] aether new"), merged.map { it.message })
    }
}
