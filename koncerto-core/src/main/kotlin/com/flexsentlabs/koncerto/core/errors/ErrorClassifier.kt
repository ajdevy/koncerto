package com.flexsentlabs.koncerto.core.errors

interface ErrorClassifier {
    fun classify(source: String, message: String): AgentErrorType
}
