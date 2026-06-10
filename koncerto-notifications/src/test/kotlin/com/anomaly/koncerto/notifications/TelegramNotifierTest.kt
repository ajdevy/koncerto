package com.anomaly.koncerto.notifications

import com.anomaly.koncerto.notifications.channel.TelegramNotifier
import org.junit.jupiter.api.Test

class TelegramNotifierTest {

    @Test
    fun `constructs with valid bot token and chat id`() {
        TelegramNotifier(botToken = "12345:ABC-DEF", chatId = "-1001234567890")
    }
}
