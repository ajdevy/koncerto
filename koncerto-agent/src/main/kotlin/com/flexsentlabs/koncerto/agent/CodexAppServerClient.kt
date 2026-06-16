package com.flexsentlabs.koncerto.agent

import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Path

class CodexRuntime(
    command: String,
    workspacePath: Path,
    logger: StructuredLogger
) : StdioAgentRuntime(command, workspacePath, logger, "codex")

@Suppress("unused")
typealias CodexAppServerClient = CodexRuntime
