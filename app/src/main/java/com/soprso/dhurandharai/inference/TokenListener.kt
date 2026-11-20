package com.soprso.dhurandharai.inference

/**
 * Streaming sink for generated text. Called on the generation thread with
 * complete UTF-8 byte sequences only (never a split codepoint).
 */
fun interface TokenListener {
    fun onToken(bytes: ByteArray)
}
