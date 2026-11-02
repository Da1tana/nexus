package com.nexus.core.engine.connector

import com.nexus.core.engine.reply.SendResult
import com.nexus.core.engine.source.SourceCapabilities

/** Результат подключения коннектора. */
sealed interface ConnectorResult {
    data object Connected : ConnectorResult
    data class NeedsUserAction(val instruction: String) : ConnectorResult
    data class Unsupported(val reason: String) : ConnectorResult
    data class Failed(val reason: String) : ConnectorResult
}

/** Данные, которые сервис может и не предоставлять. */
sealed interface ConnectorData<out T> {
    data class Available<T>(val value: T) : ConnectorData<T>
    data class Unsupported(val reason: String) : ConnectorData<Nothing>
}

data class RemoteConversation(val externalKey: String, val title: String, val isGroup: Boolean)

data class RemoteMessage(
    val externalId: String,
    val senderName: String?,
    val text: String,
    val timestamp: Long,
    val outgoing: Boolean,
)

/**
 * Единый контракт источника сообщений.
 * Методы, которые сервис не поддерживает официально, обязаны возвращать Unsupported,
 * а не пустые данные или выдуманный успех.
 */
interface MessagingConnector {
    val id: String
    val displayName: String
    val capabilities: SourceCapabilities

    suspend fun connect(): ConnectorResult
    suspend fun getConversations(): ConnectorData<List<RemoteConversation>>
    suspend fun getMessages(conversationExternalKey: String): ConnectorData<List<RemoteMessage>>
    suspend fun sendMessage(conversationExternalKey: String, text: String): SendResult
}
