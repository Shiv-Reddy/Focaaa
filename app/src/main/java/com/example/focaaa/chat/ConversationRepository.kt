package com.example.focaaa.chat

import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    val conversation: Conversation
    val messages: Flow<List<ConversationMessage>>

    suspend fun sendDemoMessage(text: String)
    suspend fun deleteMessage(messageId: String)
    suspend fun deleteAllLocalData()
}
