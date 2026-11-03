package com.nexus.core.engine.parser

import com.nexus.core.engine.source.SourceCatalog
import com.nexus.core.model.ActionKind
import com.nexus.core.model.IgnoreReason
import com.nexus.core.model.ParseResult
import com.nexus.core.model.ParsedAction
import com.nexus.core.model.ParsedConversation
import com.nexus.core.model.ParsedMessage
import com.nexus.core.model.RawAction
import com.nexus.core.model.RawNotification
import com.nexus.core.model.ReplyCapability

/**
 * Разбор уведомления в сообщения.
 *
 * Обработчики пробуются по порядку: MessagingStyle → InboxStyle → обычный текст.
 * Приложения структурируют уведомления по-разному, поэтому каждый обработчик
 * возвращает null, если не может уверенно разобрать уведомление.
 */
class NotificationParser(
    private val ownPackageName: String,
    private val handlers: List<NotificationHandler> = defaultHandlers(),
) {

    fun parse(raw: RawNotification): ParseResult {
        if (raw.packageName == ownPackageName) return ParseResult.Ignored(IgnoreReason.OWN_PACKAGE)
        if (raw.isGroupSummary) return ParseResult.Ignored(IgnoreReason.GROUP_SUMMARY)
        if (raw.isOngoing) return ParseResult.Ignored(IgnoreReason.ONGOING)
        if (raw.category != null && raw.category in NON_MESSAGE_CATEGORIES) {
            return ParseResult.Ignored(IgnoreReason.NOT_A_MESSAGE)
        }
        if (isAppLevelSummary(raw)) return ParseResult.Ignored(IgnoreReason.GROUP_SUMMARY)

        val payload = handlers.firstNotNullOfOrNull { it.parse(raw) }
            ?: return ParseResult.Ignored(IgnoreReason.EMPTY)
        if (payload.messages.isEmpty()) return ParseResult.Ignored(IgnoreReason.EMPTY)

        val actions = raw.actions.map { ParsedAction(it.title, kindOf(it)) }
        val replyCapability = if (raw.actions.any { it.hasRemoteInput }) {
            ReplyCapability.NOTIFICATION_REMOTE_INPUT
        } else {
            ReplyCapability.NONE
        }
        return ParseResult.Messages(payload.conversation, payload.messages, actions, replyCapability)
    }

    /**
     * Сводка уровня приложения («WhatsApp: 5 новых сообщений», «12 сообщений из 3 чатов»),
     * не помеченная как group summary. Не является сообщением и не должна создавать беседу.
     * Счётчик с именем конкретного отправителя (скрытый текст в чате) сохраняется как скрытое сообщение.
     */
    private fun isAppLevelSummary(raw: RawNotification): Boolean {
        if (raw.styleMessages.isNotEmpty() || raw.textLines.isNotEmpty()) return false
        val text = raw.bigText.clean() ?: raw.text.clean() ?: return false
        if (!HiddenContentDetector.isCountSummary(text)) return false
        val title = raw.title.clean() ?: return true
        val appName = SourceCatalog.find(raw.packageName)?.displayName
        return text.contains(" из ") || text.contains(" from ") ||
            (appName != null && title.equals(appName, ignoreCase = true))
    }

    companion object {
        const val SEMANTIC_ACTION_REPLY = 1
        const val SEMANTIC_ACTION_MARK_AS_READ = 2

        /** Notification.CATEGORY_* значения, которые заведомо не являются сообщениями. */
        val NON_MESSAGE_CATEGORIES = setOf(
            "progress", "transport", "sys", "service", "status", "navigation",
            "alarm", "stopwatch", "location_sharing", "call", "recommendation",
        )

        fun defaultHandlers(): List<NotificationHandler> = listOf(
            MessagingStyleHandler(),
            InboxStyleHandler(),
            SimpleTextHandler(),
        )

        fun kindOf(action: RawAction): ActionKind = when {
            action.hasRemoteInput || action.semanticAction == SEMANTIC_ACTION_REPLY -> ActionKind.REPLY
            action.semanticAction == SEMANTIC_ACTION_MARK_AS_READ -> ActionKind.MARK_AS_READ
            else -> ActionKind.OTHER
        }
    }
}

data class ParsedPayload(
    val conversation: ParsedConversation,
    val messages: List<ParsedMessage>,
)

fun interface NotificationHandler {
    fun parse(raw: RawNotification): ParsedPayload?
}

/** Ключ беседы: shortcutId стабилен; иначе — по заголовку беседы. */
internal fun conversationKey(raw: RawNotification, title: String): String =
    raw.shortcutId?.takeIf { it.isNotBlank() }?.let { "shortcut:$it" } ?: "title:$title"

internal fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
