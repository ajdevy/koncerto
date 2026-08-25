package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class DemoPlatformDecisionTest {

    /** Shaped like real DomInventoryCrawler output: a route carrying interactive elements. */
    private val uiInventory = """
        [{"route":"/login","headings":["Sign in"],"testids":["send-code-button"],"fields":[],"buttons":["Continue"]}]
    """.trimIndent()

    /**
     * What the crawler emits for a service with no UI. It pushes the `/` entry unconditionally
     * (`if (!hasSignal && route !== '/') continue`), so this is non-blank yet carries no signal —
     * the case that made the API path unreachable when the predicate was mere blankness.
     */
    private val signallessInventory = """
        [{"route":"/","headings":[],"testids":[],"fields":[],"buttons":[]}]
    """.trimIndent()

    private val apiInventory = """{"route_count":1,"routes":[{"method":"GET","path":"/api/items"}]}"""

    @Test
    fun `a browsable UI wins even when the app also publishes a spec`() {
        val verdict = DemoPlatformDecision.decide(uiInventory, apiInventory)

        assertThat(verdict.kind).isEqualTo(DemoPlatformKind.BROWSER)
        assertThat(verdict.reason.contains("browsable UI")).isTrue()
    }

    @Test
    fun `a browsable UI with no spec stays on the browser path`() {
        assertThat(DemoPlatformDecision.decide(uiInventory, null).kind).isEqualTo(DemoPlatformKind.BROWSER)
    }

    @Test
    fun `a signalless root entry does not count as a UI`() {
        val verdict = DemoPlatformDecision.decide(signallessInventory, apiInventory)

        assertThat(verdict.kind).isEqualTo(DemoPlatformKind.API)
        assertThat(verdict.reason.contains("OpenAPI")).isTrue()
    }

    @Test
    fun `a spec with no crawled inventory at all selects the API recorder`() {
        assertThat(DemoPlatformDecision.decide(null, apiInventory).kind).isEqualTo(DemoPlatformKind.API)
    }

    @Test
    fun `an inconclusive probe keeps the browser path rather than guessing`() {
        val verdict = DemoPlatformDecision.decide(null, null)

        assertThat(verdict.kind).isEqualTo(DemoPlatformKind.BROWSER)
        assertThat(verdict.reason.contains("keeping the browser path")).isTrue()
    }

    @Test
    fun `a signalless inventory with no spec keeps the browser path`() {
        assertThat(DemoPlatformDecision.decide(signallessInventory, null).kind).isEqualTo(DemoPlatformKind.BROWSER)
    }

    @Test
    fun `a blank inventory counts as absent`() {
        assertThat(DemoPlatformDecision.decide("   ", apiInventory).kind).isEqualTo(DemoPlatformKind.API)
        assertThat(DemoPlatformDecision.decide("", "").kind).isEqualTo(DemoPlatformKind.BROWSER)
        assertThat(DemoPlatformDecision.decide(uiInventory, "  ").kind).isEqualTo(DemoPlatformKind.BROWSER)
    }

    @Test
    fun `any one signal key is enough`() {
        listOf("testids", "fields", "buttons").forEach { key ->
            val inventory = """[{"route":"/x","$key":["something"]}]"""
            assertThat(DemoPlatformDecision.hasUiSignal(inventory)).isTrue()
        }
    }

    @Test
    fun `a later route carrying signal counts even when the root is empty`() {
        val inventory = """
            [{"route":"/","testids":[],"fields":[],"buttons":[]},
             {"route":"/app","testids":["dashboard"],"fields":[],"buttons":[]}]
        """.trimIndent()

        assertThat(DemoPlatformDecision.hasUiSignal(inventory)).isTrue()
    }

    @Test
    fun `unparseable inventory is treated as having a UI`() {
        // Choosing API is the destructive branch — it discards the browser scenario — so an
        // inventory we cannot read must never be the reason we take it.
        assertThat(DemoPlatformDecision.hasUiSignal("not json at all")).isTrue()
        assertThat(DemoPlatformDecision.hasUiSignal("""{"routes":[]}""")).isTrue()
        assertThat(DemoPlatformDecision.hasUiSignal("""["a string element"]""")).isTrue()
    }

    @Test
    fun `an empty array carries no signal`() {
        assertThat(DemoPlatformDecision.hasUiSignal("[]")).isFalse()
        assertThat(DemoPlatformDecision.hasUiSignal(null)).isFalse()
        assertThat(DemoPlatformDecision.hasUiSignal("  ")).isFalse()
    }
}
