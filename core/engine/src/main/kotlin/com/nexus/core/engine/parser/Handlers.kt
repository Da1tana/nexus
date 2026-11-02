package com.nexus.core.engine.parser

import com.nexus.core.model.MessageDirection
import com.nexus.core.model.ParsedConversation
import com.nexus.core.model.ParsedMessage
import com.nexus.core.model.RawNotification

/**
 * MessagingStyle (Telegram, WhatsApp, Viber, SMS и др.).
 * Содержит несколько сообщений с собственными временными метками — самый надёжный источник.
 */
class MessagingStyleHandler : NotificationHandler {
    override fun parse(raw: RawNotification): ParsedPayload? {
        if (raw.styleMessages.isEmpty()) return null

        val incomingSenders = raw.styleMessages
            .filter { !it.isFromSelf }
            .mapNotNull { it.senderName.clean() }
            .distinct()
        val conversationTitle = raw.conversationTitle.clean()
        val title = conversationTitle
            ?: incomingSenders.singleOrNull()
            ?: raw.title.clean()
            ?: raw.packageName
        val isGroup = raw.isGroupConversation || (conversationTitle != null && incomingSenders.size > 1)
        val key = conversationKey(raw, title)

        val messages = raw.styleMessages.mapNotNull { message ->
            val text = message.text.clean()
                ?: message.dataMimeType?.let { attachmentPlaceholder(it) }
                ?: return@mapNotNull null
            val senderName = if (message.isFromSelf) null else message.senderName.clean() ?: title
            val senderKey = if (message.isFromSelf) null else message.senderKey.clean() ?: senderName
            ParsedMessage(
                dedupKey = DedupKeys.of(
                    raw.packageName, key,
                    if (message.isFromSelf) "@self" else senderKey,
                    message.timestamp, text,
                ),
                senderName = senderName,
                senderKey = senderKey,
                text = text,
                timestamp = message.timestamp,
                direction = if (message.isFromSelf) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                textHidden = HiddenContentDetector.isHidden(text),
                attachmentMimeType = message.dataMimeType,
            )
        }
        return ParsedPayload(ParsedConversation(key, title, isGroup), messages)
    }
}

/**
 * InboxStyle: несколько строк без собственных меток времени.
 * Ключ дубликата строится без времени, иначе при каждом обновлении старые строки
 * сохранялись бы заново. Ограничение: две одинаковые строки в одной беседе склеятся.
 */
class InboxStyleHandler : NotificationHandler {
    override fun parse(raw: RawNotification): ParsedPayload? {
        val lines = raw.textLines.mapNotNull { it.clean() }
        if (lines.isEmpty()) return null
        val title = raw.conversationTitle.clean() ?: raw.title.clean() ?: return null
        val key = conversationKey(raw, title)
        val messages = lines.map { line ->
            ParsedMessage(
                dedupKey = DedupKeys.of(raw.packageName, key, "inbox", line),
                senderName = title,
                senderKey = title,
                text = line,
                timestamp = raw.whenTime,
                direction = MessageDirection.INCOMING,
                textHidden = HiddenContentDetector.isHidden(line),
                attachmentMimeType = null,
            )
        }
        return ParsedPayload(ParsedConversation(key, title, isGroup = false), messages)
    }
}

/**
 * Обычное уведомление: заголовок = отправитель, текст (или bigText) = сообщение.
 * Время события берём из Notification.when: повторная публикация того же события
 * не создаёт дубликат, а новое событие с тем же текстом — сохраняется.
 */
class SimpleTextHandler : NotificationHandler {
    override fun parse(raw: RawNotification): ParsedPayload? {
        val text = raw.bigText.clean() ?: raw.text.clean() ?: return null
        val title = raw.title.clean() ?: return null
        val key = conversationKey(raw, title)
        val message = ParsedMessage(
            dedupKey = DedupKeys.of(raw.packageName, key, "simple", raw.whenTime, text),
            senderName = title,
            senderKey = title,
            text = text,
            timestamp = raw.whenTime.takeIf { it > 0 } ?: raw.postTime,
            direction = MessageDirection.INCOMING,
            textHidden = HiddenContentDetector.isHidden(text),
            attachmentMimeType = null,
        )
        return ParsedPayload(ParsedConversation(key, title, isGroup = false), listOf(message))
    }
}

internal fun attachmentPlaceholder(mimeType: String): String = when {
    mimeType.startsWith("image/") -> "[Изображение]"
    mimeType.startsWith("video/") -> "[Видео]"
    mimeType.startsWith("audio/") -> "[Аудио]"
    else -> "[Вложение]"
}
