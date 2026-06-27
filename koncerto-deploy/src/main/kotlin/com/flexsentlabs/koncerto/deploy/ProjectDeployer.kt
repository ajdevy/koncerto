package com.flexsentlabs.koncerto.deploy

interface ProjectDeployer {
    suspend fun deploy(config: DeployConfig): DeployResult
    suspend fun cleanup(config: DeployConfig)
}
