package com.nexus.core.model

/** Результат разбора одного уведомления. */
sealed interface ParseResult {
    data class Messages(
        val conversation: ParsedConversation,
        val messages: List<ParsedMessage>,
        val actions: List<ParsedAction>,
        val replyCapability: ReplyCapability,
    ) : ParseResult

    data class Ignored(val reason: IgnoreReason) : ParseResult
}

enum class IgnoreReason {
    GROUP_SUMMARY,
    ONGOING,
    EMPTY,
    NOT_A_MESSAGE,
    OWN_PACKAGE,
}

data class ParsedConversation(
    /** Стабильный ключ беседы внутри источника. */
    val externalKey: String,
    val title: String,
    val isGroup: Boolean,
)

data class ParsedMessage(
    /** Ключ для защиты от дубликатов; уникален в пределах всей базы. */
    val dedupKey: String,
    val senderName: String?,
    /** Ключ отправителя внутри источника (для связи с контактом). Null — отправитель неизвестен. */
    val senderKey: String?,
    val text: String,
    val timestamp: Long,
    val direction: MessageDirection,
    /** Приложение скрыло содержимое (например, «Новое сообщение»). */
    val textHidden: Boolean,
    val attachmentMimeType: String?,
)

data class ParsedAction(
    val label: String,
    val kind: ActionKind,
)
