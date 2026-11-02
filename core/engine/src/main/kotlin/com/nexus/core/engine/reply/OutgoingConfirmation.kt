package com.nexus.core.engine.reply

/**
 * Подтверждение отправки по уведомлению.
 * Многие мессенджеры после ответа через RemoteInput обновляют уведомление
 * и добавляют наше сообщение в MessagingStyle как исходящее. Если такое сообщение
 * совпадает по тексту с недавно переданным из NEXUS, отправку можно считать подтверждённой.
 */
object OutgoingConfirmation {
    const val WINDOW_MS: Long = 10 * 60 * 1000

    fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    fun matches(sentText: String, sentAt: Long, observedText: String, observedAt: Long): Boolean =
        normalize(sentText) == normalize(observedText) &&
            observedAt >= sentAt - 60_000 &&
            observedAt - sentAt <= WINDOW_MS
}
