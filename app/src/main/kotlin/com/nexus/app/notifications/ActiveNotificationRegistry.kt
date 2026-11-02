package com.nexus.app.notifications

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Живые PendingIntent'ы активных уведомлений.
 * Их нельзя сохранить в базу: после удаления уведомления или перезапуска процесса они недействительны.
 * Поэтому возможность ответа всегда проверяется здесь, а не по сохранённому флагу.
 */
@Singleton
class ActiveNotificationRegistry @Inject constructor() {

    data class Entry(
        val notificationKey: String,
        val packageName: String,
        val replyAction: Notification.Action?,
        val contentIntent: PendingIntent?,
        val postedAt: Long,
    )

    private val byConversation = ConcurrentHashMap<String, Entry>()

    fun register(packageName: String, conversationExternalKey: String, sbn: StatusBarNotification) {
        byConversation[key(packageName, conversationExternalKey)] = Entry(
            notificationKey = sbn.key,
            packageName = packageName,
            replyAction = NotificationExtractor.findReplyAction(sbn.notification),
            contentIntent = sbn.notification.contentIntent,
            postedAt = sbn.postTime,
        )
    }

    fun find(packageName: String, conversationExternalKey: String): Entry? =
        byConversation[key(packageName, conversationExternalKey)]

    fun removeByNotificationKey(notificationKey: String) {
        byConversation.entries.removeAll { it.value.notificationKey == notificationKey }
    }

    fun invalidate(packageName: String, conversationExternalKey: String) {
        byConversation.remove(key(packageName, conversationExternalKey))
    }

    fun clear() = byConversation.clear()

    fun replyableCount(): Int = byConversation.values.count { it.replyAction != null }

    private fun key(packageName: String, conversationExternalKey: String) = "$packageName\u001F$conversationExternalKey"
}
