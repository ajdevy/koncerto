package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class GitHubPRQueryImplTest {

    private val logger = StructuredLogger(emptyList())
    private val query = GitHubPRQueryImpl(logger)

    @Test
    fun `listOpenPRs returns empty when gh fails`() = runTest {
        assertThat(query.listOpenPRs("owner/nonexistent-repo-xyz")).isEmpty()
    }

    @Test
    fun `getModifiedFiles returns empty when gh fails`() = runTest {
        assertThat(query.getModifiedFiles(99999, "owner/nonexistent-repo-xyz")).isEmpty()
    }

    @Test
    fun `parsePRList extracts pull request fields`() {
        val json = """
            [{"number":42,"title":"Add Docker","headRefName":"feat/docker","baseRefName":"main",
              "labels":[{"name":"docker-setup"}],"statusCheckRollup":[]}]
        """.trimIndent()

        val prs = invokeParsePRList(json)

        assertThat(prs).hasSize(1)
        assertThat(prs[0].number).isEqualTo(42)
        assertThat(prs[0].title).isEqualTo("Add Docker")
        assertThat(prs[0].headBranch).isEqualTo("feat/docker")
        assertThat(prs[0].baseBranch).isEqualTo("main")
        assertThat(prs[0].labels).isEqualTo(listOf("docker-setup"))
    }

    @Test
    fun `parseFileList extracts file paths`() {
        val json = """{"files":[{"path":"docker-compose.yml"},{"path":"src/Main.kt"}]}"""

        val files = invokeParseFileList(json)

        assertThat(files).isEqualTo(listOf("docker-compose.yml", "src/Main.kt"))
    }

    @Test
    fun `parsePRList handles multiple PR objects`() {
        val json = """
            [{"number":1,"title":"A","headRefName":"a","baseRefName":"main","labels":[]},
             {"number":2,"title":"B","headRefName":"b","baseRefName":"main","labels":[{"name":"infra"}]}]
        """.trimIndent()

        val prs = invokeParsePRList(json)

        assertThat(prs).hasSize(2)
        assertThat(prs[1].labels).isEqualTo(listOf("infra"))
    }

    @Test
    fun `extractJsonObjects splits top-level JSON objects`() {
        val json = """[{"a":1},{"b":2}]"""
        val objects = invokeExtractJsonObjects(json)
        assert(objects.size == 2)
        assert(objects[0].contains("\"a\":1"))
    }

    private fun invokeExtractJsonObjects(json: String): List<String> {
        val method = GitHubPRQueryImpl::class.java.getDeclaredMethod("extractJsonObjects", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(query, json) as List<String>
    }

    private fun invokeParsePRList(json: String): List<PRInfo> {
        val method = GitHubPRQueryImpl::class.java.getDeclaredMethod("parsePRList", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(query, json) as List<PRInfo>
    }

    private fun invokeParseFileList(json: String): List<String> {
        val method = GitHubPRQueryImpl::class.java.getDeclaredMethod("parseFileList", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(query, json) as List<String>
    }
}
