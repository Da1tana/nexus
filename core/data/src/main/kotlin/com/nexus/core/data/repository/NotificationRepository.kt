package com.nexus.core.data.repository

import androidx.room.withTransaction
import com.nexus.core.data.db.AppSourceEntity
import com.nexus.core.data.db.AttachmentEntity
import com.nexus.core.data.db.ContactAccountEntity
import com.nexus.core.data.db.ContactEntity
import com.nexus.core.data.db.ConversationEntity
import com.nexus.core.data.db.MessageActionEntity
import com.nexus.core.data.db.MessageEntity
import com.nexus.core.data.db.NexusDatabase
import com.nexus.core.data.db.SyncStateEntity
import com.nexus.core.engine.parser.NotificationParser
import com.nexus.core.engine.reply.OutgoingConfirmation
import com.nexus.core.engine.source.SourceCatalog
import com.nexus.core.model.IgnoreReason
import com.nexus.core.model.MessageDirection
import com.nexus.core.model.MessageStatus
import com.nexus.core.model.ParseResult
import com.nexus.core.model.ParsedMessage
import com.nexus.core.model.RawNotification

enum class IngestOutcome {
    /** Уведомление разобрано, новые сообщения сохранены (или все были дубликатами). */
    PROCESSED,
    /** Уведомление не является сообщением. */
    IGNORED,
    /** Источник выключен пользователем — ничего не сохранено. */
    SOURCE_DISABLED,
}

data class IngestReport(
    val outcome: IngestOutcome,
    val conversationId: Long? = null,
    val conversationExternalKey: String? = null,
    val inserted: Int = 0,
    val duplicates: Int = 0,
    val confirmedOutgoing: Int = 0,
    val ignoreReason: IgnoreReason? = null,
)

/**
 * Приём уведомлений: разбор → источник → беседа → контакт → сообщения → действия.
 * Всё выполняется в одной транзакции. Защита от дубликатов — уникальный индекс по dedupKey.
 * Удаление уведомления не трогает историю — только помечает действия неактивными.
 */
class NotificationRepository(
    private val db: NexusDatabase,
    private val parser: NotificationParser,
    private val appLabelResolver: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sourceDao = db.sourceDao()
    private val conversationDao = db.conversationDao()
    private val messageDao = db.messageDao()
    private val contactDao = db.contactDao()
    private val actionDao = db.actionDao()
    private val attachmentDao = db.attachmentDao()
    private val syncStateDao = db.syncStateDao()

    suspend fun ingest(raw: RawNotification): IngestReport {
        val parsed = parser.parse(raw)
        if (parsed is ParseResult.Ignored) {
            if (sourceDao.get(raw.packageName) != null) {
                recordStats(raw.packageName, seen = 1, ignoreReason = parsed.reason.name)
            }
            return IngestReport(IngestOutcome.IGNORED, ignoreReason = parsed.reason)
        }
        val result = parsed as ParseResult.Messages
        val now = clock()

        return db.withTransaction {
            val source = ensureSource(raw.packageName, now)
            if (!source.enabled) {
                recordStats(raw.packageName, seen = 1, ignoreReason = "SOURCE_DISABLED")
                return@withTransaction IngestReport(IngestOutcome.SOURCE_DISABLED)
            }

            val conversation = upsertConversation(raw, result, now)
            var inserted = 0
            var duplicates = 0
            var confirmed = 0
            var newest: ParsedMessage? = null

            for (message in result.messages) {
                if (message.direction == MessageDirection.OUTGOING && confirmPendingOutgoing(conversation.id, message, now)) {
                    confirmed++
                    continue
                }
                val accountId = if (message.direction == MessageDirection.INCOMING && message.senderKey != null) {
                    ensureAccount(raw.packageName, message.senderKey!!, message.senderName ?: message.senderKey!!, now)
                } else {
                    null
                }
                val rowId = messageDao.insert(
                    MessageEntity(
                        sourcePackage = raw.packageName,
                        conversationId = conversation.id,
                        senderAccountId = accountId,
                        senderName = message.senderName,
                        body = message.text,
                        timestamp = message.timestamp,
                        direction = message.direction,
                        // Исходящее, которое показало само приложение, отправлено им самим.
                        status = if (message.direction == MessageDirection.OUTGOING) MessageStatus.SENT else MessageStatus.RECEIVED,
                        replyCapability = result.replyCapability,
                        notificationKey = raw.key,
                        externalId = null,
                        dedupKey = message.dedupKey,
                        textHidden = message.textHidden,
                        attachmentMimeType = message.attachmentMimeType,
                        failureReason = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                if (rowId == -1L) {
                    duplicates++
                } else {
                    inserted++
                    message.attachmentMimeType?.let { attachmentDao.insert(AttachmentEntity(messageId = rowId, mimeType = it)) }
                    if (newest == null || message.timestamp >= newest.timestamp) newest = message
                }
            }

            val latest = newest
            if (latest != null && latest.timestamp >= conversation.lastMessageAt) {
                conversationDao.update(
                    conversation.copy(lastMessageAt = latest.timestamp, lastMessagePreview = preview(latest)),
                )
            }

            actionDao.deleteForConversation(conversation.id)
            if (result.actions.isNotEmpty()) {
                actionDao.insertAll(
                    result.actions.distinctBy { it.label }.map {
                        MessageActionEntity(
                            conversationId = conversation.id,
                            notificationKey = raw.key,
                            label = it.label,
                            kind = it.kind,
                            isActive = true,
                            updatedAt = now,
                        )
                    },
                )
            }

            recordStats(raw.packageName, seen = 1, stored = inserted.toLong(), duplicates = duplicates.toLong(), ignoreReason = null)
            IngestReport(
                outcome = IngestOutcome.PROCESSED,
                conversationId = conversation.id,
                conversationExternalKey = conversation.externalKey,
                inserted = inserted,
                duplicates = duplicates,
                confirmedOutgoing = confirmed,
            )
        }
    }

    /** Уведомление удалено (смахнуто, прочитано в приложении) — история сохраняется. */
    suspend fun onNotificationRemoved(notificationKey: String) {
        actionDao.deactivateByNotificationKey(notificationKey, clock())
    }

    /**
     * После перезапуска процесса PendingIntent'ы из прошлых уведомлений недоступны.
     * Сбрасываем флаги активности; сервис заново зарегистрирует действия активных уведомлений.
     */
    suspend fun resetActiveActions() {
        actionDao.deactivateAll(clock())
    }

    private suspend fun ensureSource(packageName: String, now: Long): AppSourceEntity {
        val existing = sourceDao.get(packageName)
        if (existing != null) {
            sourceDao.touch(packageName, now)
            return existing
        }
        val known = SourceCatalog.find(packageName)
        val created = AppSourceEntity(
            packageName = packageName,
            displayName = known?.displayName ?: appLabelResolver(packageName),
            isKnownMessenger = known != null,
            // Известные мессенджеры включены сразу, прочие приложения — только после явного включения.
            enabled = known != null,
            firstSeenAt = now,
            lastSeenAt = now,
        )
        sourceDao.insert(created)
        return created
    }

    private suspend fun upsertConversation(raw: RawNotification, result: ParseResult.Messages, now: Long): ConversationEntity {
        val existing = conversationDao.find(raw.packageName, result.conversation.externalKey)
        if (existing != null) {
            val updated = existing.copy(
                title = result.conversation.title,
                isGroup = existing.isGroup || result.conversation.isGroup,
                lastNotificationKey = raw.key,
                replyCapability = result.replyCapability,
            )
            if (updated != existing) conversationDao.update(updated)
            return updated
        }
        val entity = ConversationEntity(
            sourcePackage = raw.packageName,
            externalKey = result.conversation.externalKey,
            title = result.conversation.title,
            isGroup = result.conversation.isGroup,
            lastMessageAt = 0,
            lastMessagePreview = "",
            lastNotificationKey = raw.key,
            replyCapability = result.replyCapability,
        )
        val id = conversationDao.insert(entity)
        return entity.copy(id = id)
    }

    private suspend fun ensureAccount(sourcePackage: String, externalKey: String, displayName: String, now: Long): Long {
        val existing = contactDao.findAccount(sourcePackage, externalKey)
        if (existing != null) {
            if (existing.displayName != displayName) contactDao.renameAccount(existing.id, displayName)
            return existing.id
        }
        // Новый аккаунт — новый контакт. Объединение с другими контактами только вручную.
        val contactId = contactDao.insertContact(ContactEntity(displayName = displayName, createdAt = now))
        return contactDao.insertAccount(
            ContactAccountEntity(
                contactId = contactId,
                sourcePackage = sourcePackage,
                externalKey = externalKey,
                displayName = displayName,
            ),
        )
    }

    /** Сопоставляет исходящее из уведомления с сообщением, отправленным из NEXUS. */
    private suspend fun confirmPendingOutgoing(conversationId: Long, message: ParsedMessage, now: Long): Boolean {
        if (messageDao.countByDedupKey(message.dedupKey) > 0) return false
        val candidates = messageDao.findUnconfirmedOutgoing(conversationId, now - OutgoingConfirmation.WINDOW_MS)
        val match = candidates.firstOrNull {
            OutgoingConfirmation.matches(it.body, it.createdAt, message.text, message.timestamp)
        } ?: return false
        messageDao.confirmOutgoing(match.id, message.dedupKey, now)
        return true
    }

    private suspend fun recordStats(
        sourcePackage: String,
        seen: Long,
        stored: Long = 0,
        duplicates: Long = 0,
        ignoreReason: String?,
    ) {
        val current = syncStateDao.get(sourcePackage)
        syncStateDao.upsert(
            SyncStateEntity(
                sourcePackage = sourcePackage,
                lastEventAt = clock(),
                notificationsSeen = (current?.notificationsSeen ?: 0) + seen,
                messagesStored = (current?.messagesStored ?: 0) + stored,
                duplicatesSkipped = (current?.duplicatesSkipped ?: 0) + duplicates,
                lastIgnoreReason = ignoreReason,
            ),
        )
    }

    private fun preview(message: ParsedMessage): String {
        val prefix = if (message.direction == MessageDirection.OUTGOING) "Вы: " else ""
        return (prefix + message.text).take(PREVIEW_LENGTH)
    }

    private companion object {
        const val PREVIEW_LENGTH = 160
    }
}
