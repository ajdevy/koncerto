package com.anomaly.koncerto.app

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.anomaly.koncerto.dashboard.ApiV1Controller
import com.anomaly.koncerto.dashboard.DashboardController
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@Tag("ui")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
    classes = [
        DashboardUiTestConfig::class,
        DashboardController::class,
        ApiV1Controller::class
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.main.web-application-type=reactive"]
)
class DashboardUiTest {

    @LocalServerPort
    private var port: Int = 0

    private lateinit var playwright: Playwright
    private lateinit var browser: Browser
    private lateinit var page: Page

    @BeforeAll
    fun setupPlaywright() {
        playwright = Playwright.create()
        browser = playwright.chromium().launch()
    }

    @AfterAll
    fun teardownPlaywright() {
        browser.close()
        playwright.close()
    }

    @BeforeEach
    fun createPage() {
        page = browser.newPage()
    }

    @AfterEach
    fun closePage() {
        page.close()
    }

    private fun url(path: String) = "http://localhost:$port$path"

    @Test
    fun `dashboard shows running agents table`() {
        page.navigate(url("/"))
        assertThat(page.locator("h1").textContent()).contains("Koncerto Dashboard")
        val rows = page.locator("#running tbody tr")
        assertThat(rows.count()).isEqualTo(4)
        assertThat(rows.nth(0).textContent()).contains("ABC-1")
        assertThat(rows.nth(0).textContent()).contains("thread-1")
        assertThat(rows.nth(2).textContent()).contains("ABC-2")
    }

    @Test
    fun `dashboard shows retrying section heading`() {
        page.navigate(url("/"))
        val headings = page.locator("h2").allTextContents()
        assertThat(headings.any { it.contains("Retrying") }).isTrue()
    }

    @Test
    fun `page loads token totals from state endpoint`() {
        page.navigate(url("/"))
        page.waitForTimeout(1000.0)
        val totals = page.locator("#totals")
        assertThat(totals.textContent()).contains("in=100")
        assertThat(totals.textContent()).contains("total=150")
    }

    @Test
    fun `clicking View toggles agent output`() {
        page.navigate(url("/"))
        val buttons = page.locator("button.btn-out")
        assertThat(buttons.count()).isEqualTo(2)

        buttons.nth(0).click()
        page.waitForTimeout(500.0)

        val outputRow = page.locator("#outrow-ABC-1")
        assertThat(outputRow.isVisible()).isTrue()
        val outputPre = page.locator("#out-ABC-1")
        assertThat(outputPre.textContent()).contains("[stdout] Initializing agent session...")
        assertThat(outputPre.textContent()).contains("[stdout] Turn completed")

        buttons.nth(0).click()
        page.waitForTimeout(300.0)
        assertThat(outputRow.isVisible()).isFalse()
    }

    @Test
    fun `output area shows stderr prefixed lines`() {
        page.navigate(url("/"))
        page.locator("button.btn-out").nth(0).click()
        page.waitForTimeout(500.0)

        val outputPre = page.locator("#out-ABC-1")
        assertThat(outputPre.textContent()).contains("[stderr] debug: workspace ready")
        assertThat(outputPre.textContent()).contains("[stderr] warn: file not found")
    }

    @Test
    fun `output uses EventSource for live streaming`() {
        page.navigate(url("/"))
        page.locator("button.btn-out").first().click()
        page.waitForTimeout(500.0)

        val outputPre = page.locator("#out-ABC-1")
        val text = outputPre.textContent()
        assertThat(text).contains("[stdout] Initializing agent session...")
        assertThat(text).contains("[stdout] Tool call: write_file")
    }
}
