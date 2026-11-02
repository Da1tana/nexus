package com.nexus.core.engine.source

import com.nexus.core.model.IntegrationLevel

/**
 * Фактические возможности источника в текущей версии NEXUS.
 * Таблица синхронизирована с INTEGRATIONS.md — меняйте их вместе.
 */
data class SourceCapabilities(
    val level: IntegrationLevel,
    val canReadHistory: Boolean,
    val canSendWithoutNotification: Boolean,
    /** Короткое честное описание для экрана «Подключённые приложения». */
    val note: String,
)

data class KnownSource(
    val packageName: String,
    val displayName: String,
    val service: String,
    val capabilities: SourceCapabilities,
)

object SourceCatalog {

    private val notificationReply = SourceCapabilities(
        level = IntegrationLevel.NOTIFICATION_REPLY,
        canReadHistory = false,
        canSendWithoutNotification = false,
        note = "Сообщения из уведомлений. Ответ — пока уведомление активно.",
    )

    private val notificationOnly = SourceCapabilities(
        level = IntegrationLevel.NOTIFICATIONS,
        canReadHistory = false,
        canSendWithoutNotification = false,
        note = "Только чтение уведомлений. Ответ — в исходном приложении.",
    )

    val known: List<KnownSource> = listOf(
        KnownSource("org.telegram.messenger", "Telegram", "telegram", notificationReply),
        KnownSource("org.telegram.messenger.web", "Telegram", "telegram", notificationReply),
        KnownSource("org.thunderdog.challegram", "Telegram X", "telegram", notificationReply),
        KnownSource("com.whatsapp", "WhatsApp", "whatsapp", notificationReply),
        KnownSource("com.whatsapp.w4b", "WhatsApp Business", "whatsapp", notificationReply),
        KnownSource("com.viber.voip", "Viber", "viber", notificationReply),
        KnownSource("com.vkontakte.android", "VK", "vk", notificationReply),
        KnownSource("com.vk.im", "VK Мессенджер", "vk", notificationReply),
        KnownSource("com.facebook.orca", "Messenger", "facebook", notificationReply),
        KnownSource("com.google.android.gm", "Gmail", "gmail", notificationOnly),
        KnownSource("com.google.android.apps.messaging", "Сообщения (SMS)", "sms", notificationReply),
        KnownSource("com.android.mms", "SMS", "sms", notificationReply),
        KnownSource("com.huawei.message", "Сообщения Huawei", "sms", notificationReply),
        KnownSource("com.samsung.android.messaging", "Сообщения Samsung", "sms", notificationReply),
    )

    private val byPackage: Map<String, KnownSource> = known.associateBy { it.packageName }

    fun find(packageName: String): KnownSource? = byPackage[packageName]

    fun isKnownMessenger(packageName: String): Boolean = packageName in byPackage

    /** Возможности для неизвестного приложения, которое пользователь включил вручную. */
    val genericCapabilities: SourceCapabilities = SourceCapabilities(
        level = IntegrationLevel.NOTIFICATIONS,
        canReadHistory = false,
        canSendWithoutNotification = false,
        note = "Произвольное приложение: сохраняются тексты уведомлений. Ответ — если приложение предоставляет RemoteInput.",
    )

    fun capabilitiesFor(packageName: String): SourceCapabilities =
        find(packageName)?.capabilities ?: genericCapabilities
}
