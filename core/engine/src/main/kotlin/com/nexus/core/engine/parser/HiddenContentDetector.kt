package com.nexus.core.engine.parser

/**
 * Распознаёт уведомления, в которых приложение скрыло содержимое
 * (настройки приватности мессенджера или блокировка экрана), и сводки-счётчики.
 */
object HiddenContentDetector {

    private val hiddenPhrases = setOf(
        "новое сообщение",
        "содержимое скрыто",
        "сообщение скрыто",
        "new message",
        "contents hidden",
        "content hidden",
        "message hidden",
    )

    /** «3 новых сообщения», «1 новое сообщение», «2 new messages». */
    private val countOnly = Regex("""^\d+\s+(новых|новое|новые|new)\s+\S+$""", RegexOption.IGNORE_CASE)

    /** «5 сообщений из 3 чатов», «12 messages from 4 chats». */
    private val multiChatSummary = Regex(
        """^\d+\s+\S+\s+(из|from)\s+\d+\s+\S+$""",
        RegexOption.IGNORE_CASE,
    )

    private fun normalize(text: String) = text.trim().trimEnd('.', '!').lowercase()

    fun isHidden(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        return isCountSummary(text) || normalized in hiddenPhrases
    }

    /** Текст — только счётчик сообщений, без содержания. */
    fun isCountSummary(text: String): Boolean {
        val normalized = normalize(text)
        return countOnly.matches(normalized) || multiChatSummary.matches(normalized)
    }
}
