package com.flexsentlabs.koncerto.workspace

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.logging.StderrSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitWorkflowBranchTest {

    @Test
    fun `subtask branch name is correctly formatted`() {
        val git = createGitWorkflow()
        val branchName = git.subtaskBranchName("KONC-123", "step-1")
        assertThat(branchName).isEqualTo("subtask/KONC-123/step-1")
    }

    private fun createGitWorkflow(): GitWorkflow {
        val config = GitConfig(enabled = false, branchPrefix = "feature/")
        val logger = StructuredLogger(listOf(StderrSink()))
        return GitWorkflow(config, logger)
    }
}
