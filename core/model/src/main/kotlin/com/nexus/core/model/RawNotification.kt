package com.nexus.core.model

/**
 * Платформенно-независимый снимок системного уведомления.
 * Заполняется на стороне Android (NotificationExtractor) и разбирается в чистом Kotlin (NotificationParser),
 * что позволяет тестировать разбор обычными unit-тестами.
 *
 * Уведомление ≠ сообщение: одно уведомление может содержать 0..N сообщений.
 */
data class RawNotification(
    /** StatusBarNotification.key — стабилен для одного уведомления на время его жизни. */
    val key: String,
    val packageName: String,
    val postTime: Long,
    /** Notification.when — время события, заданное приложением. */
    val whenTime: Long,
    val category: String? = null,
    val isGroupSummary: Boolean = false,
    val isOngoing: Boolean = false,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    /** Строки InboxStyle. */
    val textLines: List<String> = emptyList(),
    /** MessagingStyle: заголовок беседы (обычно название группы). */
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean = false,
    /** MessagingStyle: имя владельца устройства, как его видит приложение. */
    val selfDisplayName: String? = null,
    val styleMessages: List<RawStyleMessage> = emptyList(),
    val shortcutId: String? = null,
    val actions: List<RawAction> = emptyList(),
    val hasContentIntent: Boolean = false,
)

data class RawStyleMessage(
    val text: String?,
    val timestamp: Long,
    val senderName: String?,
    /** Person.key или uri, если приложение их передаёт. */
    val senderKey: String?,
    /** true, если сообщение принадлежит владельцу устройства (sender == null в MessagingStyle). */
    val isFromSelf: Boolean,
    val dataMimeType: String? = null,
)

data class RawAction(
    val title: String,
    val hasRemoteInput: Boolean,
    /** Notification.Action.SEMANTIC_ACTION_* */
    val semanticAction: Int = 0,
)
