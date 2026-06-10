package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.logging.StructuredLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class AgentMessage(
    val messageId: String,
    val fromAgentId: String,
    val toAgentId: String,
    val payload: String,
    val timestamp: Instant = Instant.now(),
    var acknowledged: Boolean = false
)

class AgentMessageStore(
    private val logger: StructuredLogger,
    private val maxMessagesPerAgent: Int = 1000
) {
    private val messages = ConcurrentHashMap<String, ConcurrentLinkedQueue<AgentMessage>>()
    private val messageById = ConcurrentHashMap<String, AgentMessage>()
    private val waitingFlows = ConcurrentHashMap<String, MutableSharedFlow<AgentMessage>>()

    fun sendMessage(fromAgentId: String, toAgentId: String, payload: String): String {
        val messageId = UUID.randomUUID().toString()
        val message = AgentMessage(messageId, fromAgentId, toAgentId, payload)
        messageById[messageId] = message

        val queue = messages.getOrPut(toAgentId) { ConcurrentLinkedQueue() }
        queue.add(message)

        while (queue.size > maxMessagesPerAgent) {
            queue.poll()?.let { removed -> messageById.remove(removed.messageId) }
        }

        waitingFlows[toAgentId]?.tryEmit(message)

        logger.debug("agent_message_sent", mapOf(
            "message_id" to messageId,
            "from" to fromAgentId,
            "to" to toAgentId
        ))

        return messageId
    }

    fun pollMessages(agentId: String): List<AgentMessage> {
        val queue = messages[agentId] ?: return emptyList()
        val result = mutableListOf<AgentMessage>()
        var message = queue.poll()
        while (message != null && result.size < 100) {
            if (!message.acknowledged) {
                result.add(message)
            }
            message = queue.poll()
        }
        return result
    }

    fun ackMessage(messageId: String): Boolean {
        val message = messageById[messageId]
        if (message == null) return false
        message.acknowledged = true
        logger.debug("agent_message_acked", mapOf("message_id" to messageId))
        return true
    }

    fun getMessage(messageId: String): AgentMessage? = messageById[messageId]

    fun getUnacknowledgedMessages(agentId: String): List<AgentMessage> {
        val queue = messages[agentId] ?: return emptyList()
        return queue.filter { !it.acknowledged }.toList()
    }

    fun waitForMessages(agentId: String): SharedFlow<AgentMessage> {
        return waitingFlows.getOrPut(agentId) {
            MutableSharedFlow(extraBufferCapacity = 100)
        }.asSharedFlow()
    }

    fun clearAgentMessages(agentId: String) {
        val queue = messages.remove(agentId)
        queue?.forEach { messageById.remove(it.messageId) }
        waitingFlows.remove(agentId)
    }
}