package com.anomaly.koncerto.agent

import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Path

class OpencodeRuntime(
    command: String,
    workspacePath: Path,
    logger: StructuredLogger
) : StdioAgentRuntime(command, workspacePath, logger, "opencode")
