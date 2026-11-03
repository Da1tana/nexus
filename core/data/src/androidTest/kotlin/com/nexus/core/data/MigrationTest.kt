package com.nexus.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexus.core.data.db.NexusDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NexusDatabase::class.java)

    @Test
    fun migrate1To2_keepsDataAndAddsColumns() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO app_sources (packageName, displayName, isKnownMessenger, enabled, firstSeenAt, lastSeenAt) " +
                    "VALUES ('org.telegram.messenger', 'Telegram', 1, 0, 1, 1), ('com.shop', 'Shop', 0, 0, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO conversations (id, sourcePackage, externalKey, title, isGroup, lastMessageAt, lastMessagePreview, lastNotificationKey, replyCapability) " +
                    "VALUES (1, 'org.telegram.messenger', 'title:Анна', 'Анна', 0, 100, 'Привет', 'k', 'NONE')",
            )
            db.execSQL(
                "INSERT INTO messages (id, sourcePackage, conversationId, senderAccountId, senderName, body, timestamp, direction, status, " +
                    "replyCapability, notificationKey, externalId, dedupKey, textHidden, attachmentMimeType, failureReason, createdAt, updatedAt) " +
                    "VALUES (1, 'org.telegram.messenger', 1, NULL, 'Анна', 'Привет', 100, 'INCOMING', 'RECEIVED', 'NONE', 'k', NULL, 'd1', 0, NULL, NULL, 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(dbName, 2, true, NexusDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT body FROM messages").use {
                assertTrue(it.moveToFirst())
                assertEquals("Привет", it.getString(0))
            }
            db.query("SELECT avatarPath FROM conversations WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
            }
            db.query("SELECT packageName, userDecision FROM app_sources ORDER BY packageName").use {
                assertTrue(it.moveToFirst())
                assertEquals("com.shop", it.getString(0))
                assertTrue("Неизвестное приложение остаётся на автоматическом решении", it.isNull(1))
                assertTrue(it.moveToNext())
                assertEquals(0, it.getInt(1))
            }
            // Полнотекстовый индекс пережил миграцию.
            db.query("SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH 'привет*'").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        }
    }
}
