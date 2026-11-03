package com.nexus.core.engine

import com.nexus.core.engine.parser.NotificationParser
import com.nexus.core.model.ActionKind
import com.nexus.core.model.IgnoreReason
import com.nexus.core.model.MessageDirection
import com.nexus.core.model.ParseResult
import com.nexus.core.model.RawAction
import com.nexus.core.model.RawNotification
import com.nexus.core.model.RawStyleMessage
import com.nexus.core.model.ReplyCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParserTest {

    private val parser = NotificationParser(ownPackageName = "com.nexus.messenger")

    private fun raw(
        packageName: String = "org.telegram.messenger",
        key: String = "0|org.telegram.messenger|1|null|10001",
        title: String? = "Анна",
        text: String? = "Привет",
        whenTime: Long = 1_000L,
        styleMessages: List<RawStyleMessage> = emptyList(),
        conversationTitle: String? = null,
        isGroupConversation: Boolean = false,
        textLines: List<String> = emptyList(),
        category: String? = "msg",
        isGroupSummary: Boolean = false,
        isOngoing: Boolean = false,
        bigText: String? = null,
        shortcutId: String? = null,
        actions: List<RawAction> = emptyList(),
    ) = RawNotification(
        key = key,
        packageName = packageName,
        postTime = whenTime + 5,
        whenTime = whenTime,
        category = category,
        isGroupSummary = isGroupSummary,
        isOngoing = isOngoing,
        title = title,
        text = text,
        bigText = bigText,
        textLines = textLines,
        conversationTitle = conversationTitle,
        isGroupConversation = isGroupConversation,
        styleMessages = styleMessages,
        shortcutId = shortcutId,
        actions = actions,
    )

    private fun messages(result: ParseResult): ParseResult.Messages {
        assertTrue("Ожидались сообщения, получено $result", result is ParseResult.Messages)
        return result as ParseResult.Messages
    }

    @Test
    fun simpleNotification_becomesOneIncomingMessage() {
        val result = messages(parser.parse(raw()))
        assertEquals(1, result.messages.size)
        val message = result.messages.single()
        assertEquals("Привет", message.text)
        assertEquals("Анна", message.senderName)
        assertEquals(MessageDirection.INCOMING, message.direction)
        assertEquals("Анна", result.conversation.title)
        assertEquals(1_000L, message.timestamp)
    }

    @Test
    fun bigText_takesPriorityOverShortText() {
        val result = messages(parser.parse(raw(text = "Начало…", bigText = "Начало полного текста письма")))
        assertEquals("Начало полного текста письма", result.messages.single().text)
    }

    @Test
    fun messagingStyle_multipleMessagesInOneNotification() {
        val result = messages(
            parser.parse(
                raw(
                    styleMessages = listOf(
                        RawStyleMessage("Первое", 100, "Анна", null, isFromSelf = false),
                        RawStyleMessage("Второе", 200, "Анна", null, isFromSelf = false),
                        RawStyleMessage("Мой ответ", 300, null, null, isFromSelf = true),
                    ),
                ),
            ),
        )
        assertEquals(3, result.messages.size)
        assertEquals(listOf(100L, 200L, 300L), result.messages.map { it.timestamp })
        assertEquals(MessageDirection.OUTGOING, result.messages[2].direction)
        assertNull(result.messages[2].senderName)
        assertEquals(3, result.messages.map { it.dedupKey }.toSet().size)
    }

    @Test
    fun messagingStyle_groupChatUsesConversationTitle() {
        val result = messages(
            parser.parse(
                raw(
                    title = "Иван @ Семья",
                    conversationTitle = "Семья",
                    isGroupConversation = true,
                    styleMessages = listOf(
                        RawStyleMessage("Кто за хлебом?", 100, "Иван", null, false),
                        RawStyleMessage("Я", 200, "Мама", null, false),
                    ),
                ),
            ),
        )
        assertEquals("Семья", result.conversation.title)
        assertTrue(result.conversation.isGroup)
        assertEquals(listOf("Иван", "Мама"), result.messages.map { it.senderName })
    }

    @Test
    fun repeatedEvent_producesSameDedupKeys() {
        val notification = raw(
            styleMessages = listOf(RawStyleMessage("Привет", 100, "Анна", null, false)),
        )
        val first = messages(parser.parse(notification))
        val updated = messages(
            parser.parse(
                notification.copy(
                    styleMessages = notification.styleMessages +
                        RawStyleMessage("Как дела?", 200, "Анна", null, false),
                ),
            ),
        )
        assertEquals(first.messages[0].dedupKey, updated.messages[0].dedupKey)
        assertNotEquals(updated.messages[0].dedupKey, updated.messages[1].dedupKey)
    }

    @Test
    fun sameTextAtDifferentTime_isDifferentMessage() {
        val a = messages(parser.parse(raw(text = "Ок", whenTime = 1_000)))
        val b = messages(parser.parse(raw(text = "Ок", whenTime = 9_000)))
        assertNotEquals(a.messages.single().dedupKey, b.messages.single().dedupKey)
    }

    @Test
    fun differentSources_neverShareDedupKeys() {
        val a = messages(parser.parse(raw(packageName = "com.whatsapp")))
        val b = messages(parser.parse(raw(packageName = "com.viber.voip")))
        assertNotEquals(a.messages.single().dedupKey, b.messages.single().dedupKey)
    }

    @Test
    fun inboxStyle_linesWithoutTimeAreStable() {
        val first = messages(parser.parse(raw(text = null, textLines = listOf("Раз"))))
        val second = messages(parser.parse(raw(text = null, textLines = listOf("Раз", "Два"), whenTime = 5_000)))
        assertEquals(first.messages[0].dedupKey, second.messages[0].dedupKey)
        assertEquals(2, second.messages.size)
    }

    @Test
    fun groupSummary_isIgnored() {
        assertEquals(
            ParseResult.Ignored(IgnoreReason.GROUP_SUMMARY),
            parser.parse(raw(isGroupSummary = true)),
        )
    }

    @Test
    fun ongoingAndSystemNotifications_areIgnored() {
        assertEquals(ParseResult.Ignored(IgnoreReason.ONGOING), parser.parse(raw(isOngoing = true)))
        assertEquals(ParseResult.Ignored(IgnoreReason.NOT_A_MESSAGE), parser.parse(raw(category = "progress")))
        assertEquals(ParseResult.Ignored(IgnoreReason.NOT_A_MESSAGE), parser.parse(raw(category = "call")))
    }

    @Test
    fun emptyNotification_isIgnored() {
        assertEquals(ParseResult.Ignored(IgnoreReason.EMPTY), parser.parse(raw(text = null)))
        assertEquals(ParseResult.Ignored(IgnoreReason.EMPTY), parser.parse(raw(text = "   ")))
        assertEquals(ParseResult.Ignored(IgnoreReason.EMPTY), parser.parse(raw(title = null)))
    }

    @Test
    fun ownNotifications_areIgnored() {
        assertEquals(
            ParseResult.Ignored(IgnoreReason.OWN_PACKAGE),
            parser.parse(raw(packageName = "com.nexus.messenger")),
        )
    }

    @Test
    fun hiddenText_isFlagged() {
        val result = messages(parser.parse(raw(text = "Новое сообщение")))
        assertTrue(result.messages.single().textHidden)
        val normal = messages(parser.parse(raw(text = "Новое сообщение от бабушки пришло")))
        assertFalse(normal.messages.single().textHidden)
    }

    @Test
    fun messageWithoutSenderName_fallsBackToConversationTitle() {
        val result = messages(
            parser.parse(
                raw(
                    title = "Пётр",
                    styleMessages = listOf(RawStyleMessage("Текст", 100, null, null, isFromSelf = false)),
                ),
            ),
        )
        assertEquals("Пётр", result.messages.single().senderName)
    }

    @Test
    fun attachmentWithoutText_getsPlaceholder() {
        val result = messages(
            parser.parse(
                raw(styleMessages = listOf(RawStyleMessage(null, 100, "Анна", null, false, "image/jpeg"))),
            ),
        )
        assertEquals("[Изображение]", result.messages.single().text)
        assertEquals("image/jpeg", result.messages.single().attachmentMimeType)
    }

    @Test
    fun replyAction_detectedAsRemoteInputCapability() {
        val result = messages(
            parser.parse(
                raw(
                    actions = listOf(
                        RawAction("Ответить", hasRemoteInput = true, semanticAction = 1),
                        RawAction("Прочитано", hasRemoteInput = false, semanticAction = 2),
                    ),
                ),
            ),
        )
        assertEquals(ReplyCapability.NOTIFICATION_REMOTE_INPUT, result.replyCapability)
        assertEquals(listOf(ActionKind.REPLY, ActionKind.MARK_AS_READ), result.actions.map { it.kind })
    }

    @Test
    fun noReplyAction_meansNoReplyCapability() {
        assertEquals(ReplyCapability.NONE, messages(parser.parse(raw())).replyCapability)
    }

    @Test
    fun appLevelCounters_areIgnored_butPerChatHiddenMessageKept() {
        assertEquals(
            ParseResult.Ignored(IgnoreReason.GROUP_SUMMARY),
            parser.parse(raw(packageName = "com.whatsapp", title = "WhatsApp", text = "5 новых сообщений")),
        )
        assertEquals(
            ParseResult.Ignored(IgnoreReason.GROUP_SUMMARY),
            parser.parse(raw(packageName = "com.whatsapp", title = "Иван", text = "12 сообщений из 3 чатов")),
        )
        val perChat = messages(parser.parse(raw(packageName = "com.viber.voip", title = "Иван", text = "2 новых сообщения")))
        assertTrue(perChat.messages.single().textHidden)
    }

    @Test
    fun shortcutId_definesConversationKey() {
        val a = messages(parser.parse(raw(title = "Анна", shortcutId = "chat-42")))
        val b = messages(parser.parse(raw(title = "Анна Иванова", shortcutId = "chat-42")))
        assertEquals(a.conversation.externalKey, b.conversation.externalKey)
    }
}
