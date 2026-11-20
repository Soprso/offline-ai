package com.soprso.dhurandharai.chat

import com.soprso.dhurandharai.database.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageCopyPayloadTest {
    private fun message(content: String, role: String = "assistant") = Message(
        id = "id",
        conversationId = "conversation",
        role = role,
        content = content,
        createdAt = 1234L,
    )

    @Test
    fun copiesCompleteMultilineTextWithoutMetadata() {
        val text = "first line\nsecond line\n\nthird line"

        assertEquals(text, MessageCopyPayload.from(message(text)))
    }

    @Test
    fun copiesLongResponseVerbatim() {
        val text = "token ".repeat(4_000).trim()

        assertEquals(text, MessageCopyPayload.from(message(text, role = "user")))
    }
}
