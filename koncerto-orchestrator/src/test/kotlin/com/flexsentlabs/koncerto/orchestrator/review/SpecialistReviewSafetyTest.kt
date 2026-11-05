package com.flexsentlabs.koncerto.orchestrator.review

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.review.ReviewParseResult
import com.flexsentlabs.koncerto.core.review.ReviewUsage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SpecialistReviewSafetyTest {
    @Test fun `missing specialist cannot approve the whole review`() = runBlocking {
        val result = SpecialistReviewCoordinator { path -> if (path == "a") ReviewParseResult(true) else null }
            .run(listOf("a", "b"))
        assertThat(result.verdictPass).isFalse()
    }

    @Test fun `specialist rejection cannot be silently ignored`() = runBlocking {
        val result = SpecialistReviewCoordinator { path -> ReviewParseResult(path == "a") }
            .run(listOf("a", "b"))
        assertThat(result.verdictPass).isFalse()
    }

    @Test fun `specialist execution error cannot approve`() = runBlocking {
        val result = SpecialistReviewCoordinator { ReviewParseResult(true, usage = ReviewUsage(isError = true)) }
            .run(listOf("a"))
        assertThat(result.verdictPass).isFalse()
    }

    @Test fun `all successful approvals pass`() = runBlocking {
        val result = SpecialistReviewCoordinator { ReviewParseResult(true) }.run(listOf("a", "b"))
        assertThat(result.verdictPass).isTrue()
    }
}
