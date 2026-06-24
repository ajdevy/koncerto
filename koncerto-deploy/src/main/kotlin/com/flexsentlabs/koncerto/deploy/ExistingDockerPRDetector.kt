package com.flexsentlabs.koncerto.deploy

class ExistingDockerPRDetector(
    private val ghApi: GitHubPRQuery
) {
    suspend fun findExisting(
        repoFullName: String,
        baseBranch: String,
        dockerLabels: List<String> = listOf("docker-setup", "infrastructure"),
        dockerPaths: List<String> = listOf("docker-compose", "Dockerfile", ".github/workflows")
    ): PRInfo? {
        val prs = ghApi.listOpenPRs(repoFullName)
            .filter { it.baseBranch == baseBranch }

        val labeled = prs.firstOrNull { pr ->
            pr.labels.any { label ->
                dockerLabels.any { it.equals(label, ignoreCase = true) }
            }
        }
        if (labeled != null) return labeled

        for (pr in prs) {
            val files = ghApi.getModifiedFiles(pr.number, repoFullName)
            val matchesDocker = files.any { file ->
                dockerPaths.any { pattern ->
                    file.contains(pattern, ignoreCase = true)
                }
            }
            if (matchesDocker) return pr
        }

        return null
    }
}
