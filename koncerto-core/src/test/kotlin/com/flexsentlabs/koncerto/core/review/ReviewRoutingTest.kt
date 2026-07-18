package com.flexsentlabs.koncerto.core.review

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class ReviewRoutingTest {

    // ---- Glob ----

    @Test
    fun `glob matches double-star directory prefix`() {
        assertThat(Glob.matches("**/generated/**", "src/main/generated/Foo.kt")).isTrue()
        assertThat(Glob.matches("**/generated/**", "src/main/Foo.kt")).isFalse()
    }

    @Test
    fun `glob matches lockfiles and koncerto artifacts`() {
        assertThat(Glob.matches("**/*.lock", "app/package.lock")).isTrue()
        assertThat(Glob.matches(".koncerto/**", ".koncerto/state/x.jsonl")).isTrue()
        assertThat(Glob.matches("*.lock", "yarn.lock")).isTrue()
    }

    @Test
    fun `single star does not cross slash`() {
        assertThat(Glob.matches("src/*.kt", "src/A.kt")).isTrue()
        assertThat(Glob.matches("src/*.kt", "src/sub/A.kt")).isFalse()
    }

    @Test
    fun `question mark matches exactly one non-slash char`() {
        assertThat(Glob.matches("src/?.kt", "src/A.kt")).isTrue()
        assertThat(Glob.matches("src/?.kt", "src/AB.kt")).isFalse()
        assertThat(Glob.matches("src/?.kt", "src//.kt")).isFalse()
    }

    @Test
    fun `bare double star matches across directories`() {
        assertThat(Glob.matches("**", "any/deep/path.kt")).isTrue()
        assertThat(Glob.matches("src/**", "src/a/b/c.kt")).isTrue()
    }

    // ---- Eligibility ----

    @Test
    fun `empty diff skips as no changes`() {
        val d = ReviewEligibility.evaluate(emptyList())
        assertThat(d.shouldReview).isFalse()
        assertThat(d.reason).isEqualTo("skipped_no_changes")
    }

    @Test
    fun `artifact-only diff is skipped`() {
        val d = ReviewEligibility.evaluate(listOf(".koncerto/state.jsonl", ".review-status"))
        assertThat(d.shouldReview).isFalse()
        assertThat(d.reason).isEqualTo("skipped_artifact_only")
    }

    @Test
    fun `docs-only diff is skipped`() {
        val d = ReviewEligibility.evaluate(listOf("README.md", "docs/guide.md"))
        assertThat(d.shouldReview).isFalse()
        assertThat(d.reason).isEqualTo("skipped_docs_only")
    }

    @Test
    fun `real code diff is reviewed`() {
        val d = ReviewEligibility.evaluate(listOf("src/Main.kt", "README.md"))
        assertThat(d.shouldReview).isTrue()
        assertThat(d.reason).isEqualTo("reviewed")
    }

    @Test
    fun `critical-path file overrides docs-only skip`() {
        val policy = ReviewPolicy(criticalGlobs = listOf("**/auth/**"))
        val d = ReviewEligibility.evaluate(listOf("src/auth/policy.md"), policy)
        assertThat(d.shouldReview).isTrue()
    }

    // ---- Risk router ----

    @Test
    fun `critical glob forces critical tier`() {
        val policy = ReviewPolicy(criticalGlobs = listOf("**/payment/**", "**/auth/**"))
        val tier = RiskRouter.classify(listOf("src/payment/Charge.kt"), totalLinesChanged = 5, policy)
        assertThat(tier).isEqualTo(RiskTier.CRITICAL)
    }

    @Test
    fun `large change is at least standard`() {
        val tier = RiskRouter.classify(listOf("src/A.kt"), totalLinesChanged = 800)
        assertThat(tier).isEqualTo(RiskTier.STANDARD)
    }

    @Test
    fun `many files is at least standard`() {
        val files = (1..20).map { "src/File$it.kt" }
        val tier = RiskRouter.classify(files, totalLinesChanged = 50)
        assertThat(tier).isEqualTo(RiskTier.STANDARD)
    }

    @Test
    fun `small ordinary change is low`() {
        val tier = RiskRouter.classify(listOf("src/A.kt"), totalLinesChanged = 20)
        assertThat(tier).isEqualTo(RiskTier.LOW)
    }
}
