package com.flexsentlabs.koncerto.demo.recorder

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class DomInventoryCrawlerTest {

    private fun crawler() = DomInventoryCrawler(StructuredLogger(emptyList()))

    /** A fake crawl process: runs `printf` (exit 0) emitting [out] on stdout, or `exit <code>`. */
    private fun fakeProcess(out: String? = null, exitCode: Int = 0): ((String) -> ProcessBuilder) = {
        val script = if (out != null) "printf %s ${shellQuote(out)}" else "exit $exitCode"
        ProcessBuilder("bash", "-c", script)
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    @AfterEach
    fun reset() {
        DomInventoryCrawler.testCrawlProcessBuilder = null
    }

    @Test
    fun `crawl returns the inventory JSON emitted on stdout`() = runTest {
        val json = """[{"route":"/login","testids":["send-code-button"]}]"""
        DomInventoryCrawler.testCrawlProcessBuilder = fakeProcess(out = json)
        assertThat(crawler().crawl("http://c:5000")).isEqualTo(json)
    }

    @Test
    fun `crawl returns null for an empty inventory array`() = runTest {
        DomInventoryCrawler.testCrawlProcessBuilder = fakeProcess(out = "[]")
        assertThat(crawler().crawl("http://c:5000")).isNull()
    }

    @Test
    fun `crawl returns null for unparseable output`() = runTest {
        DomInventoryCrawler.testCrawlProcessBuilder = fakeProcess(out = "not json at all")
        assertThat(crawler().crawl("http://c:5000")).isNull()
    }

    @Test
    fun `crawl returns null on a non-zero exit`() = runTest {
        DomInventoryCrawler.testCrawlProcessBuilder = fakeProcess(exitCode = 3)
        assertThat(crawler().crawl("http://c:5000")).isNull()
    }

    @Test
    fun `crawl returns null when starting the process throws`() = runTest {
        DomInventoryCrawler.testCrawlProcessBuilder = { ProcessBuilder("this-command-does-not-exist-xyz") }
        assertThat(crawler().crawl("http://c:5000")).isNull()
    }

    @Test
    fun `extractInventoryJson pulls the array out of surrounding noise`() {
        val raw = "log line\n[{\"route\":\"/\"}]\ntrailing"
        assertThat(crawler().extractInventoryJson(raw)).isEqualTo("[{\"route\":\"/\"}]")
    }

    @Test
    fun `extractInventoryJson returns null for empty or bracketless output`() {
        val c = crawler()
        assertThat(c.extractInventoryJson("[]")).isNull()
        assertThat(c.extractInventoryJson("[   ]")).isNull()
        assertThat(c.extractInventoryJson("no brackets here")).isNull()
        assertThat(c.extractInventoryJson("]backwards[")).isNull()
    }

    @Test
    fun `capInventory leaves a small inventory untouched`() {
        val small = """[{"route":"/a"}]"""
        assertThat(crawler().capInventory(small, maxBytes = 1000)).isEqualTo(small)
    }

    @Test
    fun `capInventory truncates oversized inventory back to a route boundary`() {
        val big = """[{"route":"/a"},{"route":"/b"},{"route":"/c"}]"""
        val capped = crawler().capInventory(big, maxBytes = 20)
        // Trimmed to the last complete "}," boundary within the cap, then the array is closed.
        assertThat(capped).isEqualTo("""[{"route":"/a"}]""")
    }

    @Test
    fun `capInventory hard-truncates when no route boundary fits the cap`() {
        val noBoundary = """[{"route":"/aaaaaaaaaaaaaaaaaaaa"}]"""
        val capped = crawler().capInventory(noBoundary, maxBytes = 8)
        assertThat(capped).isEqualTo(noBoundary.substring(0, 8))
    }
}
