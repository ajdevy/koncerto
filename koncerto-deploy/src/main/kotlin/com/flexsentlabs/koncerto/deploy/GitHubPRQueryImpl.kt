package com.flexsentlabs.koncerto.deploy

import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.util.concurrent.TimeUnit

class GitHubPRQueryImpl(
    private val logger: StructuredLogger
) : GitHubPRQuery {

    override suspend fun listOpenPRs(repoFullName: String): List<PRInfo> {
        return try {
            val pb = ProcessBuilder(
                "gh", "pr", "list",
                "--repo", repoFullName,
                "--state", "open",
                "--json", "number,title,headRefName,baseRefName,labels,statusCheckRollup",
                "--limit", "50"
            )
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
            if (!ok) {
                logger.warn("gh_pr_list_failed", mapOf("repo" to repoFullName, "output" to output.take(200)))
                return emptyList()
            }
            parsePRList(output)
        } catch (e: Exception) {
            logger.warn("gh_pr_list_error", mapOf("repo" to repoFullName), "error" to (e.message ?: "unknown"))
            emptyList()
        }
    }

    override suspend fun getModifiedFiles(prNumber: Int, repoFullName: String): List<String> {
        return try {
            val pb = ProcessBuilder(
                "gh", "pr", "view", prNumber.toString(),
                "--repo", repoFullName,
                "--json", "files"
            )
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
            if (!ok) return emptyList()
            parseFileList(output)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parsePRList(json: String): List<PRInfo> {
        val prs = mutableListOf<PRInfo>()
        val numberRegex = Regex(""""number":(\d+)""")
        val titleRegex = Regex(""""title":"([^"]+)""")
        val headRegex = Regex(""""headRefName":"([^"]+)""")
        val baseRegex = Regex(""""baseRefName":"([^"]+)""")

        val numbers = numberRegex.findAll(json).map { it.groupValues[1].toInt() }.toList()
        val titles = titleRegex.findAll(json).map { it.groupValues[1] }.toList()
        val heads = headRegex.findAll(json).map { it.groupValues[1] }.toList()
        val bases = baseRegex.findAll(json).map { it.groupValues[1] }.toList()

        val labelRegex = Regex(""""name":"([^"]+)"}""")
        val labelMatches = labelRegex.findAll(json).map { it.groupValues[1] }.toList()

        for (i in numbers.indices) {
            prs.add(PRInfo(
                number = numbers.getOrElse(i) { 0 },
                title = titles.getOrElse(i) { "" },
                headBranch = heads.getOrElse(i) { "" },
                baseBranch = bases.getOrElse(i) { "" },
                labels = labelMatches,
                checksPassing = true
            ))
        }
        return prs
    }

    private fun parseFileList(json: String): List<String> {
        val fileRegex = Regex(""""path":"([^"]+)""")
        return fileRegex.findAll(json).map { it.groupValues[1] }.toList()
    }
}
