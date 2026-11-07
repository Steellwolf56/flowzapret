package com.flowzapret

import com.flowzapret.utility.FlowsealPresets
import com.flowzapret.utility.shellSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowsealPresetsTest {

    @Test
    fun `all 22 flowseal presets are present`() {
        assertEquals(22, FlowsealPresets.presets.size)
    }

    @Test
    fun `preset ids and names are unique`() {
        val presets = FlowsealPresets.presets
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
        assertEquals(presets.size, presets.map { it.name }.distinct().size)
        assertEquals(presets.size, presets.map { it.toCmdString() }.distinct().size)
    }

    @Test
    fun `presets do not contain zapret-only options`() {
        val forbidden = listOf("--filter", "--dpi-desync", "--new", "--hostlist", "--wf-")
        FlowsealPresets.presets.forEach { preset ->
            forbidden.forEach { option ->
                assertFalse(
                    "${preset.name} contains $option",
                    preset.toCmdString().contains(option)
                )
            }
        }
    }

    @Test
    fun `presets contain expected byeDPI blocks`() {
        FlowsealPresets.presets.forEach { preset ->
            val cmd = preset.toCmdString()
            assertTrue("${preset.name} has no QUIC block", cmd.contains("-Ku -V443"))
            assertTrue("${preset.name} has no discord block", cmd.contains("-H:discord.media"))
            assertTrue("${preset.name} has no general list", cmd.contains("-H:\"{list:Flowseal General}\""))
            assertTrue("${preset.name} has no google list", cmd.contains("-H:\"{list:Flowseal Google}\""))
            assertEquals("${preset.name} group count", 8, Regex("-A n").findAll(cmd).count())
        }
    }

    @Test
    fun `list references survive shellSplit as single tokens`() {
        val tokens = shellSplit(FlowsealPresets.presets.first().toCmdString())
        assertTrue(tokens.contains("-H:{list:Flowseal General}"))
        assertTrue(tokens.contains("-H:{list:Flowseal Google}"))
        assertTrue(tokens.contains("-A"))
        assertTrue(tokens.contains("n"))
    }

    @Test
    fun `presets have descriptions`() {
        FlowsealPresets.presets.forEach { preset ->
            assertTrue("${preset.name} has empty description", preset.description.isNotBlank())
        }
    }
}
