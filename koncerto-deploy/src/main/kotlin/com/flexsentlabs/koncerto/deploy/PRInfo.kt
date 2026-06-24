package com.flexsentlabs.koncerto.deploy

data class PRInfo(
    val number: Int,
    val title: String,
    val headBranch: String,
    val baseBranch: String,
    val labels: List<String>,
    val checksPassing: Boolean
)

interface GitHubPRQuery {
    suspend fun listOpenPRs(repoFullName: String): List<PRInfo>
    suspend fun getModifiedFiles(prNumber: Int, repoFullName: String): List<String>
}
