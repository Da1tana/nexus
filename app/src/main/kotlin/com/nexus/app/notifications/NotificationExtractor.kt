package com.nexus.app.notifications

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.nexus.core.model.RawAction
import com.nexus.core.model.RawNotification
import com.nexus.core.model.RawStyleMessage

/**
 * Переводит StatusBarNotification в платформенно-независимый RawNotification.
 * Здесь только извлечение полей; вся логика разбора — в NotificationParser (core:engine).
 */
object NotificationExtractor {

    fun extract(sbn: StatusBarNotification): RawNotification {
        val notification = sbn.notification
        val extras = notification.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val selfKey = style?.user?.key

        val styleMessages = style?.messages.orEmpty().map { message ->
            val person = message.person
            RawStyleMessage(
                text = message.text?.toString(),
                timestamp = message.timestamp,
                senderName = person?.name?.toString(),
                senderKey = person?.key ?: person?.uri,
                isFromSelf = person == null || (selfKey != null && person.key == selfKey),
                dataMimeType = message.dataMimeType,
            )
        }

        val whenTime = notification.`when`.takeIf { it > 0 } ?: sbn.postTime

        return RawNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            postTime = sbn.postTime,
            whenTime = whenTime,
            category = notification.category,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = sbn.isOngoing,
            title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString() }
                .orEmpty(),
            conversationTitle = style?.conversationTitle?.toString(),
            isGroupConversation = style?.isGroupConversation ?: false,
            selfDisplayName = style?.user?.name?.toString(),
            styleMessages = styleMessages,
            shortcutId = notification.shortcutId,
            actions = allActions(notification).map { action ->
                RawAction(
                    title = action.title?.toString().orEmpty(),
                    hasRemoteInput = action.hasFreeFormInput(),
                    semanticAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) action.semanticAction else 0,
                )
            },
            hasContentIntent = notification.contentIntent != null,
        )
    }

    /** Действие ответа: обычные действия, затем действия для носимых устройств (их использует часть мессенджеров). */
    fun findReplyAction(notification: Notification): Notification.Action? =
        notification.actions.orEmpty().firstOrNull { it.hasFreeFormInput() }
            ?: wearableActions(notification).firstOrNull { it.hasFreeFormInput() }

    private fun allActions(notification: Notification): List<Notification.Action> {
        val regular = notification.actions.orEmpty().toList()
        val hasReply = regular.any { it.hasFreeFormInput() }
        return if (hasReply) regular else regular + wearableActions(notification).filter { it.hasFreeFormInput() }
    }

    @Suppress("DEPRECATION")
    private fun wearableActions(notification: Notification): List<Notification.Action> =
        try {
            Notification.WearableExtender(notification).actions
        } catch (e: RuntimeException) {
            emptyList()
        }

    private fun Notification.Action.hasFreeFormInput(): Boolean =
        remoteInputs?.any { it.allowFreeFormInput } == true
}
