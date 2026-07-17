package com.flexsentlabs.koncerto.e2e

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.review.ParseStatus
import com.flexsentlabs.koncerto.core.review.ReviewMode
import com.flexsentlabs.koncerto.core.review.ReviewOutputParser
import com.flexsentlabs.koncerto.core.review.ReviewPolicy
import com.flexsentlabs.koncerto.core.review.RiskTier
import com.flexsentlabs.koncerto.metrics.SqliteMetricsRepository
import com.flexsentlabs.koncerto.orchestrator.review.ReviewDiffInspector
import com.flexsentlabs.koncerto.orchestrator.review.ReviewRunContext
import com.flexsentlabs.koncerto.orchestrator.review.ReviewTelemetryRecorder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Exercises the review contract against a **real free model** via the opencode CLI
 * (same free-tier model the existing opencode e2e uses — no paid credentials required).
 *
 * This is the test that answers "can a real model actually satisfy our structured-output
 * contract?" — it plants a known defect, runs the real `prompts/review.md` prompt, and
 * asserts the output flows through parse → gate → persistence.
 *
 * Uses the `opencode` binary on PATH (installed from GitHub Releases — see e2e.yml), not
 * `npx @opencode-ai/cli`: that npm tag now resolves to an unrelated rewrite ("lildax") which
 * dropped the `run` command this test needs.
 *
 * Enable with: ./gradlew :koncerto-e2e:e2eTest -Dkoncerto.e2e.review=true
 * Override with the OPENCODE_MODEL / OPENCODE_COMMAND env vars.
 */
@Tag("e2e")
@EnabledIfSystemProperty(named = "koncerto.e2e.review", matches = "true")
class FreeModelReviewE2eTest {

    private val freeModel: String
        get() = System.getenv("OPENCODE_MODEL") ?: "opencode/deepseek-v4-flash-free"

    private val opencodeCommand: String
        get() = System.getenv("OPENCODE_COMMAND") ?: "opencode"

    @Test
    fun `free model emits parseable structured findings for a planted defect`() = runBlocking<Unit> {
        val repo = initRepoWithPlantedDefect()
        val dbPath = Files.createTempFile("free-model-review-", ".db")
        try {
            val diff = ReviewDiffInspector().inspect(repo)
            assertThat(diff.changedFiles.any { it.endsWith("Auth.kt") }).isTrue()

            val prompt = buildReviewPrompt()
            val stdout = runOpencode(repo, prompt)
                ?: run {
                    // The free tier is best-effort; a transport failure must not red the suite.
                    println("free model unavailable — skipping assertions")
                    return@runBlocking
                }

            println("--- free model output (first 1500 chars) ---")
            println(stdout.take(1500))

            // Guard against the agent reviewing the wrong repository. opencode follows $PWD,
            // so a harness mistake can point it at the koncerto checkout instead of the temp
            // repo — in which case every assertion below would be meaningless.
            assertThat(stdout.contains("koncerto-e2e") || stdout.contains("AutoReviewOrchestrator")).isEqualTo(false)

            val parsed = ReviewOutputParser.parse(stdout, promptVersion = "2.0")

            // The contract we actually care about: a real model produced a machine-readable
            // findings block that our parser accepts.
            assertThat(parsed.parseStatus).isEqualTo(ParseStatus.OK)
            assertThat(parsed.findings.size).isGreaterThan(0)

            // Every finding must satisfy the schema the gate and telemetry depend on.
            parsed.findings.forEach { f ->
                assertThat(f.description.isNotBlank()).isTrue()
                assertThat(f.category.isNotBlank()).isTrue()
                f.confidence?.let { assertThat(it in 0.0..1.0).isTrue() }
            }

            // And it flows through the real gate + store.
            val metrics = SqliteMetricsRepository(dbPath.toString())
            val recorder = ReviewTelemetryRecorder(metrics, idGen = { "run-free" })
            val gate = recorder.record(
                ReviewRunContext(
                    issueId = "issue-free", issueIdentifier = "FREE-1", projectSlug = "free",
                    attempt = 1, commitSha = diff.commitSha, prNumber = null, model = freeModel,
                    riskTier = RiskTier.STANDARD, reviewMode = ReviewMode.ADVISORY
                ),
                parsed,
                ReviewPolicy.DEFAULT
            ).gate
            val storedRun = metrics.runs("free").single()
            assertThat(storedRun.findingsTotal).isEqualTo(parsed.findings.size)
            assertThat(storedRun.findingsPublished).isEqualTo(gate.published.size)
            println("free model: ${parsed.findings.size} findings, ${gate.published.size} published")
        } finally {
            repo.toFile().deleteRecursively()
            Files.deleteIfExists(dbPath)
        }
    }

    /** Plants an unambiguous, easily-detected defect so a small free model can find it. */
    private fun initRepoWithPlantedDefect(): Path {
        // toRealPath() is load-bearing on macOS: createTempDirectory returns /var/folders/...,
        // which is a symlink to /private/var/folders/.... opencode resolves the real cwd, sees
        // it differs from the project root it was given, and auto-rejects every tool call as
        // an "external_directory" — leaving the model unable to read the diff.
        val repo = Files.createTempDirectory("koncerto-free-review-").toRealPath()
        exec(repo, "git", "init", "-q")
        exec(repo, "git", "config", "user.email", "e2e@koncerto.test")
        exec(repo, "git", "config", "user.name", "Koncerto E2E")
        exec(repo, "git", "config", "commit.gpgsign", "false")

        Files.writeString(repo.resolve("README.md"), "# demo\n")
        exec(repo, "git", "add", "-A")
        exec(repo, "git", "commit", "-q", "-m", "init", "--no-verify")

        Files.createDirectories(repo.resolve("src"))
        Files.writeString(repo.resolve("src/Auth.kt"), """
            package demo

            class Auth {
                // Defect: hardcoded credential committed to source.
                private val apiKey = "sk-live-SECRET-1234567890"

                fun login(user: String?, password: String): Boolean {
                    // Defect: null dereference — `user` is nullable but dereferenced directly.
                    println("login attempt by " + user!!.lowercase())
                    return password == apiKey
                }
            }
        """.trimIndent())
        exec(repo, "git", "add", "-A")
        exec(repo, "git", "commit", "-q", "-m", "add auth", "--no-verify")
        return repo
    }

    /** Renders prompts/review.md: strips frontmatter and fills the Liquid variables we use. */
    private fun buildReviewPrompt(): String {
        val promptFile = repoRoot().resolve("prompts/review.md")
        val raw = Files.readString(promptFile)
        val body = raw.substringAfter("---\n").substringAfter("---\n")  // drop YAML frontmatter
        // The context section is appended by the orchestrator in production (see D-10), so the
        // prompt itself carries only issue-level Liquid vars.
        //
        // Deliberately no absolute path here: opencode runs with the repo as its working
        // directory, and naming an absolute path makes it treat the repo as an *external*
        // directory and gate the tool calls behind a permission prompt.
        return body
            .replace("{{ issue.identifier }}", "FREE-1")
            .replace("{{ issue.title }}", "Add authentication")
            .trim() + "\n\nReview the diff of the most recent commit in the current repository.\n"
    }

    /** Walks up from the test's working dir to the repo root (where prompts/ lives). */
    private fun repoRoot(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("prompts")) && Files.exists(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        error("could not locate repo root containing prompts/")
    }

    /** Returns the model's stdout, or null when the free tier is unreachable. */
    private fun runOpencode(repo: Path, prompt: String): String? {
        // --auto approves tool permissions non-interactively. Without it opencode auto-REJECTS
        // every tool call, the model never gets to run `git diff`, and the review is vacuous.
        // Safe here: the agent is confined to a throwaway temp repo created by this test.
        val pb = ProcessBuilder(opencodeCommand, "run", "--auto", "-m", freeModel, prompt)
            .directory(repo.toFile())
            .redirectErrorStream(true)
        // opencode resolves its project from the PWD *environment variable*, not the real
        // working directory. ProcessBuilder.directory() sets only the latter, so without this
        // the agent inherits Gradle's PWD and silently reviews the koncerto checkout itself —
        // the test would then pass while asserting nothing about the planted defect.
        pb.environment()["PWD"] = repo.toString()
        val proc = pb.start()
        proc.outputStream.close()

        val output = StringBuilder()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val captureThread = Thread {
            var line: String?
            while (reader.readLine().also { line = it } != null) output.appendLine(line)
        }
        captureThread.isDaemon = true
        captureThread.start()

        val exited = proc.waitFor(240, TimeUnit.SECONDS)
        if (!exited) {
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
            println("free model run timed out after 240s")
            return null
        }
        captureThread.join(2000)
        val text = output.toString()
        if (proc.exitValue() != 0) {
            println("opencode exited ${proc.exitValue()}: ${text.take(500)}")
            return null
        }
        return text.ifBlank { null }
    }

    private fun exec(dir: Path, vararg cmd: String) {
        val p = ProcessBuilder(*cmd).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $out" }
    }
}
