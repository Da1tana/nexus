package com.nexus.core.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.nexus.core.data.db.AppSourceEntity
import com.nexus.core.data.db.ContactAccountItem
import com.nexus.core.data.db.ContactEntity
import com.nexus.core.data.db.ContactListItem
import com.nexus.core.data.db.ConversationEntity
import com.nexus.core.data.db.ConversationListItem
import com.nexus.core.data.db.MessageEntity
import com.nexus.core.data.db.MessageListItem
import com.nexus.core.data.db.NexusDatabase
import com.nexus.core.data.db.SourceListItem
import com.nexus.core.data.db.SyncStateEntity
import com.nexus.core.engine.RetentionPolicy
import com.nexus.core.engine.reply.ReplyEngine
import com.nexus.core.engine.reply.ReplyTarget
import com.nexus.core.engine.reply.SendResult
import com.nexus.core.engine.search.FtsQueryBuilder
import com.nexus.core.engine.source.SourceCatalog
import com.nexus.core.model.MessageDirection
import com.nexus.core.model.MessageStatus
import com.nexus.core.model.ReplyCapability
import com.nexus.core.model.ReplyMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.UUID

private val pagingConfig = PagingConfig(pageSize = 40, prefetchDistance = 20, enablePlaceholders = false)

class ConversationRepository(private val db: NexusDatabase) {
    private val dao = db.conversationDao()

    fun conversations(sourcePackage: String?, unreadOnly: Boolean = false): Flow<PagingData<ConversationListItem>> =
        Pager(pagingConfig) { dao.pagingSource(sourcePackage, unreadOnly) }.flow

    /** Удаляет диалог и его сообщения из NEXUS. Возвращает путь аватара для удаления файла. */
    suspend fun delete(id: Long): String? = db.withTransaction {
        val avatar = dao.get(id)?.avatarPath
        dao.delete(id)
        db.contactDao().deleteOrphans()
        avatar
    }

    fun observe(id: Long): Flow<ConversationListItem?> = dao.observe(id)

    suspend fun get(id: Long): ConversationEntity? = dao.get(id)
}

/** Результат отправки для UI. */
sealed interface SendOutcome {
    /** Сообщение сохранено со статусом; status отражает честный результат. */
    data class Stored(val messageId: Long, val status: MessageStatus, val method: ReplyMethod?, val reason: String?) : SendOutcome

    /** Ни один способ отправки недоступен — сообщение не сохранялось. */
    data class NotAvailable(val reason: String) : SendOutcome
}

class MessageRepository(
    private val db: NexusDatabase,
    private val replyEngine: ReplyEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.messageDao()
    private val conversationDao = db.conversationDao()

    fun inbox(sourcePackage: String?, unreadOnly: Boolean): Flow<PagingData<MessageListItem>> =
        Pager(pagingConfig) { dao.inboxPagingSource(sourcePackage, unreadOnly) }.flow

    fun conversationMessages(conversationId: Long): Flow<PagingData<MessageEntity>> =
        Pager(pagingConfig) { dao.conversationPagingSource(conversationId) }.flow

    fun unreadCount(): Flow<Int> = dao.observeUnreadCount()

    suspend fun markConversationRead(conversationId: Long) {
        dao.markConversationRead(conversationId, clock())
    }

    suspend fun markAllRead() {
        dao.markAllRead(clock())
    }

    suspend fun availableMethods(conversationId: Long): List<ReplyMethod> {
        val conversation = conversationDao.get(conversationId) ?: return emptyList()
        return replyEngine.availableMethods(conversation.toTarget())
    }

    /**
     * Отправка: сначала проверяем, есть ли рабочий канал; если нет — ничего не сохраняем.
     * Иначе сохраняем исходящее как SEND_PENDING и обновляем по фактическому результату:
     * Confirmed → SENT, HandedOff → UNKNOWN (передано приложению, ждём подтверждения
     * из обновлённого уведомления), Failed → FAILED с причиной.
     */
    suspend fun send(conversationId: Long, text: String): SendOutcome {
        val conversation = conversationDao.get(conversationId)
            ?: return SendOutcome.NotAvailable("Беседа не найдена")
        val target = conversation.toTarget()
        val methods = replyEngine.availableMethods(target)
        if (methods.none { it != ReplyMethod.OPEN_ORIGINAL_APP }) {
            return SendOutcome.NotAvailable(
                "Быстрый ответ сейчас недоступен: уведомление этой беседы закрыто или приложение " +
                    "не даёт отвечать из уведомлений. Откройте исходное приложение.",
            )
        }
        val body = text.trim()
        if (body.isEmpty()) return SendOutcome.NotAvailable("Пустое сообщение")

        val now = clock()
        val messageId = db.withTransaction {
            val id = dao.insert(
                MessageEntity(
                    sourcePackage = conversation.sourcePackage,
                    conversationId = conversation.id,
                    senderAccountId = null,
                    senderName = null,
                    body = body,
                    timestamp = now,
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.SEND_PENDING,
                    replyCapability = conversation.replyCapability,
                    notificationKey = conversation.lastNotificationKey,
                    externalId = null,
                    dedupKey = "local:" + UUID.randomUUID().toString(),
                    textHidden = false,
                    attachmentMimeType = null,
                    failureReason = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            conversationDao.update(conversation.copy(lastMessageAt = now, lastMessagePreview = "Вы: $body".take(160)))
            id
        }

        val outcome = replyEngine.send(target, body)
        val (status, reason) = when (val result = outcome.result) {
            SendResult.Confirmed -> MessageStatus.SENT to null
            SendResult.HandedOff -> MessageStatus.UNKNOWN to null
            is SendResult.Failed -> MessageStatus.FAILED to result.reason
            is SendResult.Unsupported -> MessageStatus.FAILED to result.reason
        }
        dao.updateStatus(messageId, status, reason, clock())
        return SendOutcome.Stored(messageId, status, outcome.method, reason)
    }

    private fun ConversationEntity.toTarget() = ReplyTarget(id, sourcePackage, externalKey)
}

data class SearchFilters(
    val query: String = "",
    val sourcePackage: String? = null,
    val sender: String? = null,
    val fromTime: Long? = null,
    val toTime: Long? = null,
)

class SearchRepository(db: NexusDatabase) {
    private val dao = db.messageDao()

    fun search(filters: SearchFilters): Flow<PagingData<MessageListItem>> {
        val match = FtsQueryBuilder.build(filters.query)
        val sender = filters.sender?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            match != null -> Pager(pagingConfig) {
                dao.searchPagingSource(match, filters.sourcePackage, sender, filters.fromTime, filters.toTime)
            }.flow
            sender != null -> Pager(pagingConfig) {
                dao.senderPagingSource(sender, filters.sourcePackage, filters.fromTime, filters.toTime)
            }.flow
            else -> flowOf(PagingData.empty())
        }
    }
}

class ContactRepository(private val db: NexusDatabase) {
    private val dao = db.contactDao()

    fun contacts(query: String?): Flow<List<ContactListItem>> =
        dao.observeContacts(query?.trim()?.takeIf { it.isNotEmpty() })

    fun contact(id: Long): Flow<ContactEntity?> = dao.observeContact(id)

    fun accounts(contactId: Long): Flow<List<ContactAccountItem>> = dao.observeAccounts(contactId)

    /** Ручное объединение: все аккаунты переносятся в целевой контакт, остальные контакты удаляются. */
    suspend fun merge(targetContactId: Long, otherContactIds: Collection<Long>) {
        val others = otherContactIds.filter { it != targetContactId }.distinct()
        if (others.isEmpty()) return
        db.withTransaction {
            dao.moveAccounts(targetContactId, others)
            dao.deleteContacts(others)
        }
    }

    suspend fun rename(contactId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) dao.renameContact(contactId, trimmed)
    }
}

class SourceRepository(private val db: NexusDatabase) {
    private val dao = db.sourceDao()

    fun sources(): Flow<List<SourceListItem>> = dao.observeAll()

    fun sourcesWithMessages(): Flow<List<AppSourceEntity>> = dao.observeWithMessages()

    /**
     * Явный выбор пользователя. Приложение, от которого ещё не было уведомлений,
     * добавляется в список источников заранее.
     */
    suspend fun setEnabled(packageName: String, displayName: String, enabled: Boolean, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            if (dao.get(packageName) == null) {
                dao.insert(
                    AppSourceEntity(
                        packageName = packageName,
                        displayName = SourceCatalog.find(packageName)?.displayName ?: displayName,
                        isKnownMessenger = SourceCatalog.isKnownMessenger(packageName),
                        enabled = enabled,
                        firstSeenAt = now,
                        lastSeenAt = 0,
                        userDecision = enabled,
                    ),
                )
            }
            dao.setDecision(packageName, enabled)
        }
    }

    /** Включает все найденные, но выключенные источники. */
    suspend fun enableAll(): Int = dao.enableAll()

    /** Удаляет историю источника (беседы, сообщения, действия), сам источник и его настройка остаются. */
    suspend fun clearHistory(packageName: String) {
        db.withTransaction {
            db.conversationDao().deleteBySource(packageName)
            db.syncStateDao().delete(packageName)
            db.contactDao().deleteAccountsBySource(packageName)
            db.contactDao().deleteOrphans()
        }
    }

    /** Полная очистка всех данных NEXUS. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        db.clearAllTables()
    }
}

class MaintenanceRepository(
    private val db: NexusDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Удаляет сообщения старше срока хранения. Возвращает число удалённых сообщений. */
    suspend fun applyRetention(retentionDays: Int): Int {
        val cutoff = RetentionPolicy.cutoff(clock(), retentionDays) ?: return 0
        return db.withTransaction {
            val removed = db.messageDao().deleteOlderThan(cutoff)
            db.conversationDao().deleteEmpty()
            removed
        }
    }
}

data class DatabaseStats(
    val messages: Int,
    val conversations: Int,
    val contacts: Int,
    val enabledSources: Int,
    val activeReplyConversations: Int,
    val connectorAccounts: Int,
)

class DiagnosticsRepository(private val db: NexusDatabase) {

    fun stats(): Flow<DatabaseStats> {
        val counts = combine(
            db.messageDao().observeCount(),
            db.conversationDao().observeCount(),
            db.contactDao().observeCount(),
        ) { messages, conversations, contacts -> Triple(messages, conversations, contacts) }
        return combine(
            counts,
            db.sourceDao().observeEnabledCount(),
            db.actionDao().observeActiveReplyConversations(),
            db.connectorAccountDao().observeCount(),
        ) { triple, sources, replies, accounts ->
            DatabaseStats(triple.first, triple.second, triple.third, sources, replies, accounts)
        }
    }

    fun syncStates(): Flow<List<SyncStateEntity>> = db.syncStateDao().observeAll()

    suspend fun checkIntegrity(): String = withContext(Dispatchers.IO) {
        try {
            db.query("PRAGMA quick_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "нет ответа"
            }
        } catch (e: Exception) {
            "ошибка: ${e.message}"
        }
    }
}

/** Используется в UI для подписей способа ответа. */
fun ReplyCapability.isReplyable(): Boolean = this != ReplyCapability.NONE
