package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class DockerEntityPolicyTest {

    @Test
    fun `protects persistent volumes`() {
        assertThat(DockerEntityPolicy.isProtectedVolume("koncerto-workspace")).isTrue()
        assertThat(DockerEntityPolicy.isProtectedVolume("koncerto_koncerto-workspace")).isTrue()
        assertThat(DockerEntityPolicy.isProtectedVolume("koncerto-demo_pgdata")).isFalse()
    }

    @Test
    fun `protects running main stack containers`() {
        assertThat(
            DockerEntityPolicy.isProtectedContainer(
                name = "koncerto-koncerto-app-1",
                status = "running",
                composeProject = "koncerto",
                image = "koncerto-koncerto-app:latest"
            )
        ).isTrue()
    }

    @Test
    fun `protects all running containers by default`() {
        assertThat(
            DockerEntityPolicy.isProtectedContainer(
                name = "koncerto-fle52-demo",
                status = "Up 3 hours",
                composeProject = null,
                image = "ghcr.io/ajdevy/koncerto:latest"
            )
        ).isTrue()
    }

    @Test
    fun `marks exited demo containers as orphans`() {
        assertThat(
            DockerEntityPolicy.isOrphanContainer(
                name = "koncerto-demo-1712345678901",
                status = "Exited (0) 2 hours ago",
                composeProject = null,
                image = "koncerto-demo-feature:latest"
            )
        ).isTrue()
    }

    @Test
    fun `marks labeled managed exited containers as orphans`() {
        assertThat(
            DockerEntityPolicy.isOrphanContainer(
                name = "thirsty_almeida",
                status = "Exited (1) 19 hours ago",
                composeProject = null,
                image = "python:3.12-slim",
                labels = mapOf(DockerEntityPolicy.MANAGED_LABEL to DockerEntityPolicy.MANAGED_VALUE)
            )
        ).isTrue()
    }

    @Test
    fun `does not mark unlabeled random containers as orphans`() {
        assertThat(
            DockerEntityPolicy.isOrphanContainer(
                name = "thirsty_almeida",
                status = "Exited (1) 19 hours ago",
                composeProject = null,
                image = "python:3.12-slim"
            )
        ).isFalse()
    }

    @Test
    fun `marks stale koncerto infra containers as orphans`() {
        assertThat(
            DockerEntityPolicy.isOrphanContainer(
                name = "koncerto-minio",
                status = "Exited (255) 5 hours ago",
                composeProject = null,
                image = "quay.io/minio/minio"
            )
        ).isTrue()
    }

    @Test
    fun `identifies orphan compose projects`() {
        assertThat(DockerEntityPolicy.isOrphanComposeProject("koncerto-demo")).isTrue()
        assertThat(DockerEntityPolicy.isOrphanComposeProject("koncerto")).isFalse()
    }

    @Test
    fun `identifies orphan images`() {
        assertThat(DockerEntityPolicy.isOrphanImage("koncerto-demo-feature:latest")).isTrue()
        assertThat(DockerEntityPolicy.isOrphanImage("koncerto-agent:latest")).isFalse()
    }
}
