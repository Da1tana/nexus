package com.nexus.core.engine

import com.nexus.core.engine.reply.OutgoingConfirmation
import com.nexus.core.engine.reply.ReplyChannel
import com.nexus.core.engine.reply.ReplyEngine
import com.nexus.core.engine.reply.ReplyTarget
import com.nexus.core.engine.reply.SendResult
import com.nexus.core.model.ReplyMethod
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyEngineTest {

    private val target = ReplyTarget(1, "org.telegram.messenger", "title:Анна")

    private class FakeChannel(
        override val method: ReplyMethod,
        var available: Boolean,
        val result: () -> SendResult,
    ) : ReplyChannel {
        val sent = mutableListOf<String>()
        override suspend fun isAvailable(target: ReplyTarget) = available
        override suspend fun send(target: ReplyTarget, text: String): SendResult {
            sent += text
            return result()
        }
    }

    @Test
    fun officialApi_hasPriorityOverRemoteInput() = runTest {
        val remote = FakeChannel(ReplyMethod.NOTIFICATION_REMOTE_INPUT, true) { SendResult.HandedOff }
        val api = FakeChannel(ReplyMethod.OFFICIAL_API, true) { SendResult.Confirmed }
        val outcome = ReplyEngine(listOf(remote, api)).send(target, "Привет")
        assertEquals(ReplyMethod.OFFICIAL_API, outcome.method)
        assertEquals(SendResult.Confirmed, outcome.result)
        assertTrue(remote.sent.isEmpty())
    }

    @Test
    fun unavailableChannel_isSkipped() = runTest {
        val api = FakeChannel(ReplyMethod.OFFICIAL_API, false) { SendResult.Confirmed }
        val remote = FakeChannel(ReplyMethod.NOTIFICATION_REMOTE_INPUT, true) { SendResult.HandedOff }
        val outcome = ReplyEngine(listOf(api, remote)).send(target, "  текст  ")
        assertEquals(ReplyMethod.NOTIFICATION_REMOTE_INPUT, outcome.method)
        assertEquals(listOf("текст"), remote.sent)
    }

    @Test
    fun noChannels_returnsUnsupported_neverFakeSuccess() = runTest {
        val remote = FakeChannel(ReplyMethod.NOTIFICATION_REMOTE_INPUT, false) { SendResult.HandedOff }
        val outcome = ReplyEngine(listOf(remote)).send(target, "Привет")
        assertTrue(outcome.result is SendResult.Unsupported)
        assertEquals(ReplyMethod.OPEN_ORIGINAL_APP, outcome.method)
    }

    @Test
    fun channelException_becomesFailedWithReason() = runTest {
        val remote = FakeChannel(ReplyMethod.NOTIFICATION_REMOTE_INPUT, true) {
            throw IllegalStateException("PendingIntent отменён")
        }
        val outcome = ReplyEngine(listOf(remote)).send(target, "Привет")
        assertEquals(SendResult.Failed("PendingIntent отменён"), outcome.result)
    }

    @Test
    fun emptyText_isRejected() = runTest {
        val remote = FakeChannel(ReplyMethod.NOTIFICATION_REMOTE_INPUT, true) { SendResult.HandedOff }
        val outcome = ReplyEngine(listOf(remote)).send(target, "   ")
        assertTrue(outcome.result is SendResult.Failed)
        assertTrue(remote.sent.isEmpty())
    }

    @Test
    fun availableMethods_alwaysEndWithOpenApp() = runTest {
        val remote = FakeChannel(ReplyMethod.NOTIFICATION_REMOTE_INPUT, true) { SendResult.HandedOff }
        assertEquals(
            listOf(ReplyMethod.NOTIFICATION_REMOTE_INPUT, ReplyMethod.OPEN_ORIGINAL_APP),
            ReplyEngine(listOf(remote)).availableMethods(target),
        )
    }

    @Test
    fun outgoingConfirmation_matchesWithinWindowOnly() {
        assertTrue(OutgoingConfirmation.matches("Привет  мир", 1_000, " Привет мир", 5_000))
        assertFalse(OutgoingConfirmation.matches("Привет", 1_000, "Пока", 5_000))
        assertFalse(OutgoingConfirmation.matches("Привет", 1_000, "Привет", 1_000 + OutgoingConfirmation.WINDOW_MS + 1))
    }
}
