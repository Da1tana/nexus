package com.nexus.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

@Database(
    entities = [
        AppSourceEntity::class,
        ContactEntity::class,
        ContactAccountEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        MessageFtsEntity::class,
        AttachmentEntity::class,
        MessageActionEntity::class,
        ConnectorAccountEntity::class,
        SyncStateEntity::class,
    ],
    version = NexusDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NexusDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun contactDao(): ContactDao
    abstract fun actionDao(): ActionDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun connectorAccountDao(): ConnectorAccountDao

    companion object {
        const val VERSION = 1
        const val NAME = "nexus.db"

        /**
         * Явные миграции. При изменении схемы: увеличить VERSION, добавить Migration сюда
         * и тест в NexusMigrationTest. Разрушающая миграция намеренно не включена —
         * история пользователя не должна теряться молча.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun create(context: Context, inMemory: Boolean = false): NexusDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, NexusDatabase::class.java)
            } else {
                Room.databaseBuilder(context, NexusDatabase::class.java, NAME)
            }
            return builder
                .addMigrations(*MIGRATIONS)
                .build()
        }
    }
}
