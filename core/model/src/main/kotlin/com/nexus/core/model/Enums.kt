package com.nexus.core.model

/** Направление сообщения относительно владельца устройства. */
enum class MessageDirection { INCOMING, OUTGOING }

/**
 * Честные статусы сообщения.
 * SENT ставится только когда отправка подтверждена доступным механизмом
 * (например, исходное приложение показало наше сообщение в обновлённом уведомлении).
 */
enum class MessageStatus {
    RECEIVED,
    READ_IN_NEXUS,
    SEND_PENDING,
    SENT,
    FAILED,
    UNKNOWN,
}

/** Какой способ ответа был доступен в момент получения сообщения. */
enum class ReplyCapability {
    NONE,
    NOTIFICATION_REMOTE_INPUT,
    OFFICIAL_API,
}

/** Уровень интеграции источника (см. INTEGRATIONS.md). */
enum class IntegrationLevel {
    /** Уровень A: только чтение уведомлений. */
    NOTIFICATIONS,
    /** Уровень B: уведомления + ответ через RemoteInput. */
    NOTIFICATION_REPLY,
    /** Уровень C: официальный API сервиса. */
    FULL_API,
}

/** Способы отправки в порядке приоритета Reply Engine. */
enum class ReplyMethod {
    OFFICIAL_API,
    NOTIFICATION_REMOTE_INPUT,
    OPEN_ORIGINAL_APP,
}

/** Тип действия, найденного в уведомлении. */
enum class ActionKind { REPLY, MARK_AS_READ, OTHER }
