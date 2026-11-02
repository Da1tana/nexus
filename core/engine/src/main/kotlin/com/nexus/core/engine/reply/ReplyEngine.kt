package com.nexus.core.engine.reply

import com.nexus.core.model.ReplyMethod

/** Результат попытки отправки через конкретный канал. */
sealed interface SendResult {
    /** Сервис подтвердил доставку на свою сторону (официальный API). */
    data object Confirmed : SendResult

    /**
     * Текст передан исходному приложению (например, через RemoteInput),
     * но подтверждения доставки у NEXUS нет.
     */
    data object HandedOff : SendResult

    data class Failed(val reason: String) : SendResult

    /** Канал не умеет отправлять в эту беседу. */
    data class Unsupported(val reason: String) : SendResult
}

/** Адресат ответа: источник и ключ беседы внутри него. */
data class ReplyTarget(
    val conversationId: Long,
    val sourcePackage: String,
    val conversationExternalKey: String,
)

/** Канал отправки. Реализации живут на платформенной стороне (RemoteInput, API сервиса). */
interface ReplyChannel {
    val method: ReplyMethod
    suspend fun isAvailable(target: ReplyTarget): Boolean
    suspend fun send(target: ReplyTarget, text: String): SendResult
}

data class ReplyOutcome(
    val method: ReplyMethod?,
    val result: SendResult,
)

/**
 * Выбирает лучший доступный способ отправки по приоритету:
 * официальный API → RemoteInput → (переход в исходное приложение, выполняется UI).
 * Никогда не имитирует успех: если ни один канал недоступен, возвращает Unsupported.
 */
class ReplyEngine(channels: List<ReplyChannel>) {

    private val ordered: List<ReplyChannel> = channels
        .filter { it.method != ReplyMethod.OPEN_ORIGINAL_APP }
        .sortedBy { it.method.ordinal }

    suspend fun availableMethods(target: ReplyTarget): List<ReplyMethod> =
        ordered.filter { it.isAvailable(target) }.map { it.method } + ReplyMethod.OPEN_ORIGINAL_APP

    suspend fun send(target: ReplyTarget, text: String): ReplyOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ReplyOutcome(null, SendResult.Failed("Пустое сообщение"))

        val channel = ordered.firstOrNull { it.isAvailable(target) }
            ?: return ReplyOutcome(
                ReplyMethod.OPEN_ORIGINAL_APP,
                SendResult.Unsupported(
                    "Быстрый ответ недоступен: уведомление этой беседы закрыто или приложение " +
                        "не поддерживает ответ из уведомления. Откройте исходное приложение.",
                ),
            )
        val result = try {
            channel.send(target, trimmed)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.Failed(e.message ?: e::class.java.simpleName)
        }
        return ReplyOutcome(channel.method, result)
    }
}
