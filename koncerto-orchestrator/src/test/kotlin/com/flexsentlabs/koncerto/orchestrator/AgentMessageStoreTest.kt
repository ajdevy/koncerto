package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentMessageStoreTest {

    private val logger = StructuredLogger(emptyList())
    private lateinit var store: AgentMessageStore

    @BeforeEach
    fun setup() {
        store = AgentMessageStore(logger)
    }

    @Test
    fun `sendMessage stores message and returns messageId`() = runTest {
        val messageId = store.sendMessage("agent-1", "agent-2", "hello")

        assertThat(messageId).isNotNull()
        assertThat(store.getMessage(messageId)).isNotNull()
        assertThat(store.getMessage(messageId)!!.fromAgentId).isEqualTo("agent-1")
        assertThat(store.getMessage(messageId)!!.toAgentId).isEqualTo("agent-2")
        assertThat(store.getMessage(messageId)!!.payload).isEqualTo("hello")
    }

    @Test
    fun `getUnacknowledgedMessages returns messages for agent`() = runTest {
        store.sendMessage("agent-1", "agent-2", "msg1")
        store.sendMessage("agent-1", "agent-2", "msg2")

        val messages = store.getUnacknowledgedMessages("agent-2")

        assertThat(messages.size).isEqualTo(2)
        assertThat(messages.map { it.payload }).containsExactly("msg1", "msg2")
    }

    @Test
    fun `getUnacknowledgedMessages returns empty for unknown agent`() = runTest {
        val messages = store.getUnacknowledgedMessages("unknown")

        assertThat(messages).isEmpty()
    }

    @Test
    fun `ackMessage marks message as acknowledged`() = runTest {
        val messageId = store.sendMessage("agent-1", "agent-2", "hello")
        val result = store.ackMessage(messageId)

        assertThat(result).isTrue()
        assertThat(store.getMessage(messageId)!!.acknowledged).isTrue()
    }

    @Test
    fun `ackMessage returns false for unknown messageId`() = runTest {
        val result = store.ackMessage("unknown-id")

        assertThat(result).isFalse()
    }

    @Test
    fun `ackMessage removes message from unacknowledged list`() = runTest {
        val messageId = store.sendMessage("agent-1", "agent-2", "hello")
        store.ackMessage(messageId)

        val messages = store.getUnacknowledgedMessages("agent-2")

        assertThat(messages).isEmpty()
    }

    @Test
    fun `pollMessages returns and removes unacknowledged messages up to limit`() = runTest {
        repeat(150) { i ->
            store.sendMessage("agent-$i", "agent-target", "msg$i")
        }

        val messages = store.pollMessages("agent-target")

        assertThat(messages.size).isEqualTo(100)
        assertThat(messages.map { it.payload }.toSet().size).isEqualTo(100)
    }

    @Test
    fun `pollMessages does not return acknowledged messages`() = runTest {
        val id1 = store.sendMessage("agent-1", "agent-2", "msg1")
        store.sendMessage("agent-1", "agent-2", "msg2")
        store.ackMessage(id1)

        val messages = store.pollMessages("agent-2")

        assertThat(messages.size).isEqualTo(1)
        assertThat(messages[0].payload).isEqualTo("msg2")
    }

    @Test
    fun `waitForMessages emits new messages`() = runTest {
        val flow = store.waitForMessages("agent-1")
        
        val received = CompletableDeferred<AgentMessage>()
        launch {
            flow.first().also { received.complete(it) }
        }
        
        // Give the collector time to start
        yield()
        
        store.sendMessage("agent-2", "agent-1", "hello")
        
        val message = received.await()
        assertThat(message.payload).isEqualTo("hello")
        assertThat(message.toAgentId).isEqualTo("agent-1")
    }

    @Test
    fun `clearAgentMessages removes all messages for agent`() = runTest {
        store.sendMessage("agent-1", "agent-2", "msg1")
        store.sendMessage("agent-3", "agent-2", "msg2")
        
        store.clearAgentMessages("agent-2")

        assertThat(store.getUnacknowledgedMessages("agent-2")).isEmpty()
        assertThat(store.waitForMessages("agent-2")).isNotNull()
    }

    @Test
    fun `clearAgentMessages removes from messageById`() = runTest {
        val messageId = store.sendMessage("agent-1", "agent-2", "hello")
        
        store.clearAgentMessages("agent-2")

        assertThat(store.getMessage(messageId)).isNull()
    }

    @Test
    fun `sendMessage enforces maxMessagesPerAgent limit`() = runTest {
        val limitedStore = AgentMessageStore(logger, maxMessagesPerAgent = 2)
        
        limitedStore.sendMessage("a1", "target", "msg1")
        limitedStore.sendMessage("a2", "target", "msg2")
        limitedStore.sendMessage("a3", "target", "msg3")

        val messages = limitedStore.getUnacknowledgedMessages("target")
        
        assertThat(messages.size).isEqualTo(2)
        assertThat(messages.map { it.payload }.toSet()).isEqualTo(setOf("msg2", "msg3"))
    }

    @Test
    fun `message timestamp is set on creation`() = runTest {
        val before = java.time.Instant.now()
        val messageId = store.sendMessage("a1", "a2", "test")
        val after = java.time.Instant.now()

        val message = store.getMessage(messageId)
        assertThat(message).isNotNull()
        assertThat(message!!.timestamp).isNotNull()
        assertThat(message.timestamp.compareTo(before) >= 0).isTrue()
        assertThat(message.timestamp.compareTo(after) <= 0).isTrue()
    }

    @Test
    fun `multiple agents can have independent message queues`() = runTest {
        store.sendMessage("a1", "target1", "for target1")
        store.sendMessage("a2", "target2", "for target2")

        assertThat(store.getUnacknowledgedMessages("target1").single().payload).isEqualTo("for target1")
        assertThat(store.getUnacknowledgedMessages("target2").single().payload).isEqualTo("for target2")
    }
}
