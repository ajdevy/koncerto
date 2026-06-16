package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import com.flexsentlabs.koncerto.core.model.Issue
import org.junit.jupiter.api.Test

class FollowUpRendererTest {

    private val issue = Issue(
        id = "abc-123", identifier = "ENG-42", title = "Implement auth",
        description = null, priority = 1, state = "In Progress",
        branchName = null, url = "https://linear.app/eng/issue/ENG-42",
        labels = listOf("frontend", "auth"), blockedBy = emptyList(),
        createdAt = null, updatedAt = null
    )

    @Test
    fun `renders issue title`() {
        val result = FollowUpRenderer.render("PR Review: {{ issue.title }}", issue)
        assertThat(result).isEqualTo("PR Review: Implement auth")
    }

    @Test
    fun `renders issue identifier`() {
        val result = FollowUpRenderer.render("Verify: {{ issue.identifier }}", issue)
        assertThat(result).isEqualTo("Verify: ENG-42")
    }

    @Test
    fun `renders issue id`() {
        val result = FollowUpRenderer.render("ID: {{ issue.id }}", issue)
        assertThat(result).isEqualTo("ID: abc-123")
    }

    @Test
    fun `renders issue url`() {
        val result = FollowUpRenderer.render("Review: {{ issue.url }}", issue)
        assertThat(result).isEqualTo("Review: https://linear.app/eng/issue/ENG-42")
    }

    @Test
    fun `renders issue labels as csv`() {
        val result = FollowUpRenderer.render("Labels: {{ issue.labels }}", issue)
        assertThat(result).isEqualTo("Labels: frontend, auth")
    }

    @Test
    fun `renders issue state`() {
        val result = FollowUpRenderer.render("State: {{ issue.state }}", issue)
        assertThat(result).isEqualTo("State: In Progress")
    }

    @Test
    fun `leaves unknown variables as-is`() {
        val result = FollowUpRenderer.render("Hello {{ unknown }}", issue)
        assertThat(result).isEqualTo("Hello {{ unknown }}")
    }

    @Test
    fun `renders now timestamp`() {
        val result = FollowUpRenderer.render("Created: {{ now }}", issue)
        assertThat(result).startsWith("Created: 20")
    }

    @Test
    fun `renders multiple variables in one template`() {
        val result = FollowUpRenderer.render("{{ issue.identifier }}: {{ issue.title }}", issue)
        assertThat(result).isEqualTo("ENG-42: Implement auth")
    }
}
