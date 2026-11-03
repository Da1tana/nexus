package com.nexus.core.data.db

import androidx.room.Embedded

/** Сообщение с контекстом беседы и источника — для ленты и результатов поиска. */
data class MessageListItem(
    @Embedded val message: MessageEntity,
    val conversationTitle: String,
    val conversationIsGroup: Boolean,
    val sourceName: String,
)

data class ConversationListItem(
    @Embedded val conversation: ConversationEntity,
    val sourceName: String,
    val unreadCount: Int,
    val hasActiveReply: Boolean,
)

data class SourceListItem(
    @Embedded val source: AppSourceEntity,
    val messageCount: Int,
    val conversationCount: Int,
    val notificationsSeen: Long,
)

data class ContactListItem(
    @Embedded val contact: ContactEntity,
    val accountCount: Int,
    val sourceNames: String?,
    val lastMessageAt: Long?,
)

data class ContactAccountItem(
    @Embedded val account: ContactAccountEntity,
    val sourceName: String,
    val messageCount: Int,
)
