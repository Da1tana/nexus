package com.nexus.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nexus.core.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM app_sources WHERE packageName = :packageName")
    suspend fun get(packageName: String): AppSourceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(source: AppSourceEntity): Long

    @Query("UPDATE app_sources SET lastSeenAt = :time WHERE packageName = :packageName")
    suspend fun touch(packageName: String, time: Long)

    @Query("UPDATE app_sources SET enabled = :enabled WHERE packageName = :packageName")
    suspend fun setEnabled(packageName: String, enabled: Boolean)

    @Query(
        """
        SELECT s.*,
          (SELECT COUNT(*) FROM messages m WHERE m.sourcePackage = s.packageName) AS messageCount,
          (SELECT COUNT(*) FROM conversations c WHERE c.sourcePackage = s.packageName) AS conversationCount
        FROM app_sources s
        ORDER BY s.isKnownMessenger DESC, s.displayName COLLATE NOCASE
        """,
    )
    fun observeAll(): Flow<List<SourceListItem>>

    /** Источники, из которых уже есть сообщения, — для фильтров ленты и поиска. */
    @Query(
        """
        SELECT * FROM app_sources s
        WHERE EXISTS (SELECT 1 FROM messages m WHERE m.sourcePackage = s.packageName)
        ORDER BY s.displayName COLLATE NOCASE
        """,
    )
    fun observeWithMessages(): Flow<List<AppSourceEntity>>

    @Query("SELECT COUNT(*) FROM app_sources WHERE enabled = 1")
    fun observeEnabledCount(): Flow<Int>
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE sourcePackage = :sourcePackage AND externalKey = :externalKey")
    suspend fun find(sourcePackage: String, externalKey: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query(
        """
        SELECT c.*, s.displayName AS sourceName,
          (SELECT COUNT(*) FROM messages m WHERE m.conversationId = c.id AND m.status = 'RECEIVED') AS unreadCount,
          EXISTS (SELECT 1 FROM message_actions a
                  WHERE a.conversationId = c.id AND a.isActive = 1 AND a.kind = 'REPLY') AS hasActiveReply
        FROM conversations c
        JOIN app_sources s ON s.packageName = c.sourcePackage
        WHERE (:sourcePackage IS NULL OR c.sourcePackage = :sourcePackage)
        ORDER BY c.lastMessageAt DESC
        """,
    )
    fun pagingSource(sourcePackage: String?): PagingSource<Int, ConversationListItem>

    @Query(
        """
        SELECT c.*, s.displayName AS sourceName,
          (SELECT COUNT(*) FROM messages m WHERE m.conversationId = c.id AND m.status = 'RECEIVED') AS unreadCount,
          EXISTS (SELECT 1 FROM message_actions a
                  WHERE a.conversationId = c.id AND a.isActive = 1 AND a.kind = 'REPLY') AS hasActiveReply
        FROM conversations c
        JOIN app_sources s ON s.packageName = c.sourcePackage
        WHERE c.id = :id
        """,
    )
    fun observe(id: Long): Flow<ConversationListItem?>

    @Query("DELETE FROM conversations WHERE sourcePackage = :sourcePackage")
    suspend fun deleteBySource(sourcePackage: String): Int

    @Query("DELETE FROM conversations WHERE NOT EXISTS (SELECT 1 FROM messages m WHERE m.conversationId = conversations.id)")
    suspend fun deleteEmpty(): Int

    @Query("SELECT COUNT(*) FROM conversations")
    fun observeCount(): Flow<Int>
}

@Dao
interface MessageDao {
    /** Возвращает -1, если сообщение с таким dedupKey уже есть. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Query(
        """
        SELECT m.*, c.title AS conversationTitle, c.isGroup AS conversationIsGroup, s.displayName AS sourceName
        FROM messages m
        JOIN conversations c ON c.id = m.conversationId
        JOIN app_sources s ON s.packageName = m.sourcePackage
        WHERE (:sourcePackage IS NULL OR m.sourcePackage = :sourcePackage)
          AND (:unreadOnly = 0 OR m.status = 'RECEIVED')
        ORDER BY m.timestamp DESC, m.id DESC
        """,
    )
    fun inboxPagingSource(sourcePackage: String?, unreadOnly: Boolean): PagingSource<Int, MessageListItem>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC, id DESC")
    fun conversationPagingSource(conversationId: Long): PagingSource<Int, MessageEntity>

    @Query(
        """
        SELECT m.*, c.title AS conversationTitle, c.isGroup AS conversationIsGroup, s.displayName AS sourceName
        FROM messages m
        JOIN messages_fts ON messages_fts.rowid = m.id
        JOIN conversations c ON c.id = m.conversationId
        JOIN app_sources s ON s.packageName = m.sourcePackage
        WHERE messages_fts MATCH :match
          AND (:sourcePackage IS NULL OR m.sourcePackage = :sourcePackage)
          AND (:sender IS NULL OR m.senderName LIKE '%' || :sender || '%' OR c.title LIKE '%' || :sender || '%')
          AND (:fromTime IS NULL OR m.timestamp >= :fromTime)
          AND (:toTime IS NULL OR m.timestamp <= :toTime)
        ORDER BY m.timestamp DESC, m.id DESC
        """,
    )
    fun searchPagingSource(
        match: String,
        sourcePackage: String?,
        sender: String?,
        fromTime: Long?,
        toTime: Long?,
    ): PagingSource<Int, MessageListItem>

    /** Поиск только по отправителю — когда текст запроса пуст. */
    @Query(
        """
        SELECT m.*, c.title AS conversationTitle, c.isGroup AS conversationIsGroup, s.displayName AS sourceName
        FROM messages m
        JOIN conversations c ON c.id = m.conversationId
        JOIN app_sources s ON s.packageName = m.sourcePackage
        WHERE (m.senderName LIKE '%' || :sender || '%' OR c.title LIKE '%' || :sender || '%')
          AND (:sourcePackage IS NULL OR m.sourcePackage = :sourcePackage)
          AND (:fromTime IS NULL OR m.timestamp >= :fromTime)
          AND (:toTime IS NULL OR m.timestamp <= :toTime)
        ORDER BY m.timestamp DESC, m.id DESC
        """,
    )
    fun senderPagingSource(
        sender: String,
        sourcePackage: String?,
        fromTime: Long?,
        toTime: Long?,
    ): PagingSource<Int, MessageListItem>

    @Query(
        """
        SELECT COUNT(*) FROM messages m
        JOIN messages_fts ON messages_fts.rowid = m.id
        WHERE messages_fts MATCH :match
        """,
    )
    suspend fun countMatches(match: String): Int

    @Query("UPDATE messages SET status = 'READ_IN_NEXUS', updatedAt = :now WHERE conversationId = :conversationId AND status = 'RECEIVED'")
    suspend fun markConversationRead(conversationId: Long, now: Long): Int

    @Query("UPDATE messages SET status = 'READ_IN_NEXUS', updatedAt = :now WHERE status = 'RECEIVED'")
    suspend fun markAllRead(now: Long): Int

    @Query("SELECT COUNT(*) FROM messages WHERE status = 'RECEIVED'")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages")
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND direction = 'OUTGOING'
          AND status IN ('SEND_PENDING', 'UNKNOWN') AND createdAt >= :since
        ORDER BY createdAt ASC
        """,
    )
    suspend fun findUnconfirmedOutgoing(conversationId: Long, since: Long): List<MessageEntity>

    @Query("UPDATE messages SET status = 'SENT', dedupKey = :dedupKey, failureReason = NULL, updatedAt = :now WHERE id = :id")
    suspend fun confirmOutgoing(id: Long, dedupKey: String, now: Long)

    @Query("UPDATE messages SET status = :status, failureReason = :reason, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: MessageStatus, reason: String?, now: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE dedupKey = :dedupKey")
    suspend fun countByDedupKey(dedupKey: String): Int

    @Query("DELETE FROM messages WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun latestInConversation(conversationId: Long): MessageEntity?
}

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(attachment: AttachmentEntity): Long
}

@Dao
interface ContactDao {
    @Insert
    suspend fun insertContact(contact: ContactEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccount(account: ContactAccountEntity): Long

    @Query("SELECT * FROM contact_accounts WHERE sourcePackage = :sourcePackage AND externalKey = :externalKey")
    suspend fun findAccount(sourcePackage: String, externalKey: String): ContactAccountEntity?

    @Query("UPDATE contact_accounts SET displayName = :name WHERE id = :id")
    suspend fun renameAccount(id: Long, name: String)

    @Query(
        """
        SELECT ct.*,
          (SELECT COUNT(*) FROM contact_accounts a WHERE a.contactId = ct.id) AS accountCount,
          (SELECT GROUP_CONCAT(DISTINCT s.displayName) FROM contact_accounts a
             JOIN app_sources s ON s.packageName = a.sourcePackage WHERE a.contactId = ct.id) AS sourceNames,
          (SELECT MAX(m.timestamp) FROM messages m
             JOIN contact_accounts a ON a.id = m.senderAccountId WHERE a.contactId = ct.id) AS lastMessageAt
        FROM contacts ct
        WHERE (:query IS NULL OR ct.displayName LIKE '%' || :query || '%')
        ORDER BY lastMessageAt DESC, ct.displayName COLLATE NOCASE
        """,
    )
    fun observeContacts(query: String?): Flow<List<ContactListItem>>

    @Query("SELECT * FROM contacts WHERE id = :id")
    fun observeContact(id: Long): Flow<ContactEntity?>

    @Query(
        """
        SELECT a.*, s.displayName AS sourceName,
          (SELECT COUNT(*) FROM messages m WHERE m.senderAccountId = a.id) AS messageCount
        FROM contact_accounts a
        JOIN app_sources s ON s.packageName = a.sourcePackage
        WHERE a.contactId = :contactId
        ORDER BY s.displayName COLLATE NOCASE
        """,
    )
    fun observeAccounts(contactId: Long): Flow<List<ContactAccountItem>>

    @Query("UPDATE contact_accounts SET contactId = :targetContactId WHERE contactId IN (:sourceContactIds)")
    suspend fun moveAccounts(targetContactId: Long, sourceContactIds: List<Long>)

    @Query("DELETE FROM contacts WHERE id IN (:ids)")
    suspend fun deleteContacts(ids: List<Long>)

    @Query("UPDATE contacts SET displayName = :name WHERE id = :id")
    suspend fun renameContact(id: Long, name: String)

    @Query("DELETE FROM contact_accounts WHERE sourcePackage = :sourcePackage")
    suspend fun deleteAccountsBySource(sourcePackage: String): Int

    @Query("DELETE FROM contacts WHERE NOT EXISTS (SELECT 1 FROM contact_accounts a WHERE a.contactId = contacts.id)")
    suspend fun deleteOrphans(): Int

    @Query("SELECT COUNT(*) FROM contacts")
    fun observeCount(): Flow<Int>
}

@Dao
interface ActionDao {
    @Query("DELETE FROM message_actions WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(actions: List<MessageActionEntity>)

    @Query("UPDATE message_actions SET isActive = 0, updatedAt = :now WHERE notificationKey = :notificationKey")
    suspend fun deactivateByNotificationKey(notificationKey: String, now: Long): Int

    @Query("UPDATE message_actions SET isActive = 0, updatedAt = :now")
    suspend fun deactivateAll(now: Long)

    @Query("SELECT COUNT(DISTINCT conversationId) FROM message_actions WHERE isActive = 1 AND kind = 'REPLY'")
    fun observeActiveReplyConversations(): Flow<Int>
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE sourcePackage = :sourcePackage")
    suspend fun get(sourcePackage: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state ORDER BY lastEventAt DESC")
    fun observeAll(): Flow<List<SyncStateEntity>>

    @Query("DELETE FROM sync_state WHERE sourcePackage = :sourcePackage")
    suspend fun delete(sourcePackage: String)
}

@Dao
interface ConnectorAccountDao {
    @Query("SELECT COUNT(*) FROM connector_accounts")
    fun observeCount(): Flow<Int>
}
