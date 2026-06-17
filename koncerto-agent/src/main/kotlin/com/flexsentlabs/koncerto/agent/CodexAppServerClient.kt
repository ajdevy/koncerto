package com.flexsentlabs.koncerto.agent

import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Path

class CodexRuntime(
    command: String,
    workspacePath: Path,
    logger: StructuredLogger,
    model: String? = null
) : StdioAgentRuntime(
    if (model != null) "$command --model $model" else command,
    workspacePath, logger, "codex"
)

@Suppress("unused")
typealias CodexAppServerClient = CodexRuntime
