package com.nexus.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nexus.core.model.ActionKind
import com.nexus.core.model.MessageDirection
import com.nexus.core.model.MessageStatus
import com.nexus.core.model.ReplyCapability

/** Приложение-источник (Telegram, WhatsApp, любое другое приложение с уведомлениями). */
@Entity(tableName = "app_sources")
data class AppSourceEntity(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val isKnownMessenger: Boolean,
    /** Сохранять ли сообщения из этого источника (действующее значение). */
    val enabled: Boolean,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    /** Явный выбор пользователя; null — решение принимает SourcePolicy. С версии БД 2. */
    val userDecision: Boolean? = null,
)

/** Человек. Может быть связан с несколькими аккаунтами в разных источниках. */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val createdAt: Long,
)

/**
 * Аккаунт отправителя внутри конкретного источника.
 * Контакты не объединяются автоматически по совпадению имени — только вручную.
 */
@Entity(
    tableName = "contact_accounts",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AppSourceEntity::class,
            parentColumns = ["packageName"],
            childColumns = ["sourcePackage"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourcePackage", "externalKey"], unique = true),
        Index(value = ["contactId"]),
    ],
)
data class ContactAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: Long,
    val sourcePackage: String,
    val externalKey: String,
    val displayName: String,
)

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = AppSourceEntity::class,
            parentColumns = ["packageName"],
            childColumns = ["sourcePackage"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourcePackage", "externalKey"], unique = true),
        Index(value = ["lastMessageAt"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePackage: String,
    val externalKey: String,
    val title: String,
    val isGroup: Boolean,
    val lastMessageAt: Long,
    val lastMessagePreview: String,
    /** Ключ последнего уведомления беседы — для ответа и перехода в приложение. */
    val lastNotificationKey: String?,
    val replyCapability: ReplyCapability,
    /** Файл аватара беседы из уведомления (во внутренней папке приложения). С версии БД 2. */
    val avatarPath: String? = null,
)

/**
 * Сообщение. Локальный id отделён от идентификатора в исходном сервисе (externalId)
 * и от ключа дубликата (dedupKey).
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AppSourceEntity::class,
            parentColumns = ["packageName"],
            childColumns = ["sourcePackage"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["senderAccountId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["dedupKey"], unique = true),
        Index(value = ["conversationId", "timestamp"]),
        Index(value = ["sourcePackage", "timestamp"]),
        Index(value = ["timestamp"]),
        Index(value = ["status"]),
        Index(value = ["senderAccountId"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePackage: String,
    val conversationId: Long,
    val senderAccountId: Long?,
    val senderName: String?,
    val body: String,
    val timestamp: Long,
    val direction: MessageDirection,
    val status: MessageStatus,
    val replyCapability: ReplyCapability,
    val notificationKey: String?,
    /** Идентификатор сообщения в исходном сервисе, если он известен (только для API-интеграций). */
    val externalId: String?,
    val dedupKey: String,
    val textHidden: Boolean,
    val attachmentMimeType: String?,
    val failureReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Полнотекстовый индекс по сообщениям. unicode61 корректно приводит регистр кириллицы. */
@Fts4(contentEntity = MessageEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "senderName") val senderName: String?,
)

/** Метаданные вложения. Само содержимое не копируется. */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId"])],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val mimeType: String,
)

/** Действие, найденное в последнем уведомлении беседы. Активно, пока уведомление не удалено. */
@Entity(
    tableName = "message_actions",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversationId", "label"], unique = true),
        Index(value = ["notificationKey"]),
    ],
)
data class MessageActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val notificationKey: String,
    val label: String,
    val kind: ActionKind,
    val isActive: Boolean,
    val updatedAt: Long,
)

/**
 * Аккаунт официальной интеграции (уровень C). Токены хранятся не здесь,
 * а в зашифрованном хранилище под ключом Android Keystore; здесь — только псевдоним.
 */
@Entity(tableName = "connector_accounts", indices = [Index(value = ["connectorId"])])
data class ConnectorAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val connectorId: String,
    val displayName: String,
    val tokenAlias: String,
    val createdAt: Long,
)

/** Статистика обработки уведомлений по источнику — для экрана диагностики. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val sourcePackage: String,
    val lastEventAt: Long,
    val notificationsSeen: Long,
    val messagesStored: Long,
    val duplicatesSkipped: Long,
    val lastIgnoreReason: String?,
)
