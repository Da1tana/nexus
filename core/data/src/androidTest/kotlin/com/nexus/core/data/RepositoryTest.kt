package com.nexus.core.data

import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexus.core.data.db.NexusDatabase
import com.nexus.core.data.repository.ContactRepository
import com.nexus.core.data.repository.IngestOutcome
import com.nexus.core.data.repository.MaintenanceRepository
import com.nexus.core.data.repository.MessageRepository
import com.nexus.core.data.repository.NotificationRepository
import com.nexus.core.data.repository.SendOutcome
import com.nexus.core.data.repository.SourceRepository
import com.nexus.core.engine.parser.NotificationParser
import com.nexus.core.engine.reply.ReplyChannel
import com.nexus.core.engine.reply.ReplyEngine
import com.nexus.core.engine.reply.ReplyTarget
import com.nexus.core.engine.reply.SendResult
import com.nexus.core.engine.search.FtsQueryBuilder
import com.nexus.core.model.MessageStatus
import com.nexus.core.model.RawAction
import com.nexus.core.model.RawNotification
import com.nexus.core.model.RawStyleMessage
import com.nexus.core.model.ReplyMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTest {

    private lateinit var db: NexusDatabase
    private var now = 1_700_000_000_000L
    private lateinit var notifications: NotificationRepository

    private class FakeRemoteInput(var available: Boolean, var result: SendResult) : ReplyChannel {
        override val method = ReplyMethod.NOTIFICATION_REMOTE_INPUT
        override suspend fun isAvailable(target: ReplyTarget) = available
        override suspend fun send(target: ReplyTarget, text: String) = result
    }

    private val channel = FakeRemoteInput(available = true, result = SendResult.HandedOff)

    @Before
    fun setUp() {
        db = NexusDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        notifications = NotificationRepository(db, NotificationParser("com.nexus.messenger"), { it }, { now })
    }

    @After
    fun tearDown() = db.close()

    private fun telegram(vararg messages: RawStyleMessage, key: String = "k1", withReply: Boolean = true) = RawNotification(
        key = key,
        packageName = "org.telegram.messenger",
        postTime = now,
        whenTime = now,
        category = "msg",
        title = "Анна",
        styleMessages = messages.toList(),
        actions = if (withReply) listOf(RawAction("Ответить", true, 1)) else emptyList(),
    )

    private suspend fun <T : Any> load(source: PagingSource<Int, T>): List<T> {
        val result = source.load(PagingSource.LoadParams.Refresh(null, 100, false))
        return (result as PagingSource.LoadResult.Page).data
    }

    @Test
    fun ingest_savesMessage_andRepeatedEventIsNotDuplicated() = runTest {
        val n = telegram(RawStyleMessage("Привет", 100, "Анна", null, false))
        val first = notifications.ingest(n)
        val second = notifications.ingest(n)
        assertEquals(IngestOutcome.PROCESSED, first.outcome)
        assertEquals(1, first.inserted)
        assertEquals(0, second.inserted)
        assertEquals(1, second.duplicates)
        assertEquals(1, db.messageDao().observeCount().first())
    }

    @Test
    fun messagesFromSameChat_groupIntoOneConversation() = runTest {
        notifications.ingest(telegram(RawStyleMessage("Раз", 100, "Анна", null, false)))
        notifications.ingest(
            telegram(
                RawStyleMessage("Раз", 100, "Анна", null, false),
                RawStyleMessage("Два", 200, "Анна", null, false),
            ),
        )
        assertEquals(1, db.conversationDao().observeCount().first())
        val conversation = db.conversationDao().find("org.telegram.messenger", "title:Анна")!!
        assertEquals("Два", conversation.lastMessagePreview)
        assertEquals(2, load(db.messageDao().conversationPagingSource(conversation.id)).size)
    }

    @Test
    fun removingNotification_keepsHistory_butDisablesReply() = runTest {
        val report = notifications.ingest(telegram(RawStyleMessage("Привет", 100, "Анна", null, false)))
        val conversationId = report.conversationId!!
        assertTrue(db.conversationDao().observe(conversationId).first()!!.hasActiveReply)
        notifications.onNotificationRemoved("k1")
        assertEquals(1, db.messageDao().observeCount().first())
        assertEquals(false, db.conversationDao().observe(conversationId).first()!!.hasActiveReply)
    }

    @Test
    fun ftsSearch_findsCyrillicByPrefix_caseInsensitive() = runTest {
        notifications.ingest(telegram(RawStyleMessage("Встречаемся у Метро в семь", 100, "Анна", null, false)))
        notifications.ingest(telegram(RawStyleMessage("Hello World", 200, "Анна", null, false), key = "k2"))
        val match = FtsQueryBuilder.build("метр")!!
        val results = load(db.messageDao().searchPagingSource(match, null, null, null, null))
        assertEquals(listOf("Встречаемся у Метро в семь"), results.map { it.message.body })
        assertEquals(1, db.messageDao().countMatches(FtsQueryBuilder.build("WORLD")!!))
    }

    @Test
    fun sourceFilter_limitsInbox() = runTest {
        notifications.ingest(telegram(RawStyleMessage("TG", 100, "Анна", null, false)))
        notifications.ingest(
            RawNotification(key = "w1", packageName = "com.whatsapp", postTime = now, whenTime = now, title = "Борис", text = "WA"),
        )
        assertEquals(2, load(db.messageDao().inboxPagingSource(null, false)).size)
        assertEquals(listOf("WA"), load(db.messageDao().inboxPagingSource("com.whatsapp", false)).map { it.message.body })
    }

    @Test
    fun unknownApp_isDiscoveredButDisabled() = runTest {
        val report = notifications.ingest(
            RawNotification(key = "x", packageName = "com.example.shop", postTime = now, whenTime = now, title = "Магазин", text = "Скидка"),
        )
        assertEquals(IngestOutcome.SOURCE_DISABLED, report.outcome)
        assertEquals(0, db.messageDao().observeCount().first())
        SourceRepository(db).setEnabled("com.example.shop", true)
        assertEquals(1, notifications.ingest(
            RawNotification(key = "x", packageName = "com.example.shop", postTime = now, whenTime = now, title = "Магазин", text = "Скидка"),
        ).inserted)
    }

    @Test
    fun send_handedOff_isUnknownUntilAppConfirms() = runTest {
        val report = notifications.ingest(telegram(RawStyleMessage("Привет", now, "Анна", null, false)))
        val messages = MessageRepository(db, ReplyEngine(listOf(channel)), { now })
        val outcome = messages.send(report.conversationId!!, "Как дела?") as SendOutcome.Stored
        assertEquals(MessageStatus.UNKNOWN, outcome.status)

        // Приложение обновило уведомление и показало наш ответ как исходящий.
        now += 2_000
        val update = notifications.ingest(
            telegram(
                RawStyleMessage("Привет", now - 2_000, "Анна", null, false),
                RawStyleMessage("Как дела?", now, null, null, true),
            ),
        )
        assertEquals(1, update.confirmedOutgoing)
        assertEquals(MessageStatus.SENT, db.messageDao().get(outcome.messageId)!!.status)
        assertEquals(2, db.messageDao().observeCount().first())
    }

    @Test
    fun send_failure_isStoredWithReason() = runTest {
        val report = notifications.ingest(telegram(RawStyleMessage("Привет", 100, "Анна", null, false)))
        channel.result = SendResult.Failed("PendingIntent отменён")
        val outcome = MessageRepository(db, ReplyEngine(listOf(channel)), { now })
            .send(report.conversationId!!, "Ответ") as SendOutcome.Stored
        assertEquals(MessageStatus.FAILED, outcome.status)
        assertEquals("PendingIntent отменён", db.messageDao().get(outcome.messageId)!!.failureReason)
    }

    @Test
    fun send_withoutChannel_storesNothing() = runTest {
        val report = notifications.ingest(telegram(RawStyleMessage("Привет", 100, "Анна", null, false)))
        channel.available = false
        val outcome = MessageRepository(db, ReplyEngine(listOf(channel)), { now }).send(report.conversationId!!, "Ответ")
        assertTrue(outcome is SendOutcome.NotAvailable)
        assertEquals(1, db.messageDao().observeCount().first())
    }

    @Test
    fun contacts_notMergedByName_butMergeableManually() = runTest {
        notifications.ingest(telegram(RawStyleMessage("TG", 100, "Анна", null, false)))
        notifications.ingest(
            RawNotification(key = "w1", packageName = "com.whatsapp", postTime = now, whenTime = now, title = "Анна", text = "WA"),
        )
        val contacts = ContactRepository(db)
        val list = contacts.contacts(null).first()
        assertEquals(2, list.size)
        contacts.merge(list[0].contact.id, list.map { it.contact.id })
        val merged = contacts.contacts(null).first()
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().accountCount)
    }

    @Test
    fun retention_removesOldMessagesAndEmptyConversations() = runTest {
        notifications.ingest(telegram(RawStyleMessage("Старое", now - 40L * 86_400_000, "Анна", null, false)))
        notifications.ingest(
            RawNotification(key = "w1", packageName = "com.whatsapp", postTime = now, whenTime = now, title = "Борис", text = "Новое"),
        )
        val removed = MaintenanceRepository(db) { now }.applyRetention(30)
        assertEquals(1, removed)
        assertEquals(1, db.conversationDao().observeCount().first())
    }

    @Test
    fun clearSourceHistory_keepsOtherSources() = runTest {
        notifications.ingest(telegram(RawStyleMessage("TG", 100, "Анна", null, false)))
        notifications.ingest(
            RawNotification(key = "w1", packageName = "com.whatsapp", postTime = now, whenTime = now, title = "Борис", text = "WA"),
        )
        SourceRepository(db).clearHistory("org.telegram.messenger")
        assertEquals(listOf("WA"), load(db.messageDao().inboxPagingSource(null, false)).map { it.message.body })
        assertEquals(1, db.contactDao().observeCount().first())
    }
}
