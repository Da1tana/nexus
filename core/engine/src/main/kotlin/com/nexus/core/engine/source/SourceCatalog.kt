package com.nexus.core.engine.source

import com.nexus.core.model.IntegrationLevel
import com.nexus.core.model.RawNotification

/**
 * Фактические возможности источника в текущей версии NEXUS.
 * Таблица синхронизирована с INTEGRATIONS.md — меняйте их вместе.
 */
data class SourceCapabilities(
    val level: IntegrationLevel,
    val canReadHistory: Boolean,
    val canSendWithoutNotification: Boolean,
    /** Короткое честное описание для экрана «Приложения». */
    val note: String,
)

enum class SourceKind { MESSENGER, SOCIAL, EMAIL, SMS, WORK }

data class KnownSource(
    val packageName: String,
    val displayName: String,
    val service: String,
    val kind: SourceKind,
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

    private fun m(pkg: String, name: String, service: String, kind: SourceKind = SourceKind.MESSENGER, caps: SourceCapabilities = notificationReply) =
        KnownSource(pkg, name, service, kind, caps)

    val known: List<KnownSource> = listOf(
        // Мессенджеры
        m("org.telegram.messenger", "Telegram", "telegram"),
        m("org.telegram.messenger.web", "Telegram", "telegram"),
        m("org.telegram.plus", "Plus Messenger", "telegram"),
        m("org.thunderdog.challegram", "Telegram X", "telegram"),
        m("tw.nekomimi.nekogram", "Nekogram", "telegram"),
        m("com.whatsapp", "WhatsApp", "whatsapp"),
        m("com.whatsapp.w4b", "WhatsApp Business", "whatsapp"),
        m("com.viber.voip", "Viber", "viber"),
        m("ru.oneme.app", "MAX", "max"),
        m("ru.ok.messages", "ТамТам", "tamtam"),
        m("com.vkontakte.android", "VK", "vk", SourceKind.SOCIAL),
        m("com.vk.im", "VK Мессенджер", "vk"),
        m("com.perm.kate_new_6", "Kate Mobile", "vk", SourceKind.SOCIAL),
        m("ru.ok.android", "Одноклассники", "ok", SourceKind.SOCIAL),
        m("com.facebook.orca", "Messenger", "facebook"),
        m("com.facebook.mlite", "Messenger Lite", "facebook"),
        m("com.instagram.android", "Instagram", "instagram", SourceKind.SOCIAL),
        m("org.thoughtcrime.securesms", "Signal", "signal"),
        m("com.discord", "Discord", "discord"),
        m("im.vector.app", "Element", "matrix"),
        m("ch.threema.app", "Threema", "threema"),
        m("com.skype.raider", "Skype", "skype"),
        m("jp.naver.line.android", "LINE", "line"),
        m("com.tencent.mm", "WeChat", "wechat"),
        m("com.snapchat.android", "Snapchat", "snapchat", SourceKind.SOCIAL),
        m("ru.yandex.messenger", "Яндекс Мессенджер", "yandex"),
        m("com.huawei.meetime", "MeeTime", "huawei"),
        m("com.twitter.android", "X (Twitter)", "twitter", SourceKind.SOCIAL),
        // Рабочие
        m("com.Slack", "Slack", "slack", SourceKind.WORK),
        m("com.microsoft.teams", "Microsoft Teams", "teams", SourceKind.WORK),
        m("com.google.android.apps.dynamite", "Google Chat", "googlechat", SourceKind.WORK),
        m("com.mattermost.rn", "Mattermost", "mattermost", SourceKind.WORK),
        // Почта
        m("com.google.android.gm", "Gmail", "gmail", SourceKind.EMAIL, notificationOnly),
        m("com.microsoft.office.outlook", "Outlook", "outlook", SourceKind.EMAIL),
        m("com.huawei.email", "Почта Huawei", "email", SourceKind.EMAIL),
        m("ru.yandex.mail", "Яндекс Почта", "yandexmail", SourceKind.EMAIL),
        m("ru.mail.mailapp", "Почта Mail.ru", "mailru", SourceKind.EMAIL),
        m("com.samsung.android.email.provider", "Почта Samsung", "email", SourceKind.EMAIL),
        // SMS
        m("com.google.android.apps.messaging", "Сообщения (SMS)", "sms", SourceKind.SMS),
        m("com.android.mms", "SMS", "sms", SourceKind.SMS),
        m("com.huawei.message", "Сообщения Huawei", "sms", SourceKind.SMS),
        m("com.samsung.android.messaging", "Сообщения Samsung", "sms", SourceKind.SMS),
    )

    private val byPackage: Map<String, KnownSource> = known.associateBy { it.packageName }

    fun find(packageName: String): KnownSource? = byPackage[packageName]

    fun isKnownMessenger(packageName: String): Boolean = packageName in byPackage

    /** Возможности для приложения вне каталога. */
    val genericCapabilities: SourceCapabilities = SourceCapabilities(
        level = IntegrationLevel.NOTIFICATIONS,
        canReadHistory = false,
        canSendWithoutNotification = false,
        note = "Сохраняются тексты уведомлений. Ответ — если приложение даёт кнопку «Ответить» в уведомлении.",
    )

    fun capabilitiesFor(packageName: String): SourceCapabilities =
        find(packageName)?.capabilities ?: genericCapabilities
}

/**
 * Решение, сохранять ли сообщения источника, пока пользователь сам не выбрал.
 * Известные мессенджеры — да. Остальные приложения — да, если уведомление выглядит как сообщение
 * (категория «сообщение/почта/соцсеть», MessagingStyle или кнопка ответа). Так в NEXUS попадают
 * мессенджеры вне каталога, а уведомления магазинов, банков и системы — нет, пока их не включат вручную.
 */
object SourcePolicy {

    private val messagingCategories = setOf("msg", "email", "social")

    fun looksLikeMessaging(raw: RawNotification): Boolean =
        raw.category in messagingCategories ||
            raw.styleMessages.isNotEmpty() ||
            raw.actions.any { it.hasRemoteInput }

    fun autoEnable(raw: RawNotification): Boolean =
        SourceCatalog.isKnownMessenger(raw.packageName) || looksLikeMessaging(raw)
}
