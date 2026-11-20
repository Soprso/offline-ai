package com.soprso.dhurandharai.chat

import com.soprso.dhurandharai.database.Message

/** Clipboard text for a chat message. Labels, timestamps and metadata are excluded. */
object MessageCopyPayload {
    fun from(message: Message): String = message.content
}
