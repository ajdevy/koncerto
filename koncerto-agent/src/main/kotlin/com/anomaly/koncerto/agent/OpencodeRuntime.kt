package com.anomaly.koncerto.agent

import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Path

class OpencodeRuntime(
    command: String,
    workspacePath: Path,
    logger: StructuredLogger,
    model: String? = null,
    freeModelCycler: FreeModelCycler? = null
) : StdioAgentRuntime(command, workspacePath, logger, "opencode") {
    val isFreeModel: Boolean = model?.lowercase() == "free"
    val freeModelCycler = freeModelCycler
}
