package com.nexus.core.engine.parser

/**
 * Распознаёт уведомления, в которых приложение скрыло содержимое
 * (настройки приватности мессенджера или блокировка экрана).
 */
object HiddenContentDetector {

    private val hiddenPhrases = listOf(
        "новое сообщение",
        "новых сообщений",
        "новых сообщения",
        "содержимое скрыто",
        "сообщение скрыто",
        "new message",
        "new messages",
        "contents hidden",
        "content hidden",
        "message hidden",
    )

    /** «3 новых сообщения», «1 новое сообщение», «2 new messages». */
    private val countOnly = Regex("""^\d+\s+(новых|новое|новые|new)\s+\S+$""", RegexOption.IGNORE_CASE)

    fun isHidden(text: String): Boolean {
        val normalized = text.trim().trimEnd('.', '!').lowercase()
        if (normalized.isEmpty()) return false
        if (countOnly.matches(normalized)) return true
        return normalized in hiddenPhrases
    }
}
