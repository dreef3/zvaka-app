package com.dreef3.weightlossapp.chat

class ChatToolFailureException(
    tool: String,
    errorType: String?,
    detail: String?,
) : Exception("Chat tool '$tool' failed: ${errorType ?: "unknown"} — $detail")
