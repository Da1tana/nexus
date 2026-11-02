package com.nexus.core.engine.parser

import java.security.MessageDigest

/**
 * Построение ключей защиты от дубликатов.
 * Ключ детерминирован: повторный приход того же события (обновление уведомления,
 * переподключение сервиса, getActiveNotifications) даёт тот же ключ, и база отбрасывает вставку.
 */
object DedupKeys {

    fun of(vararg parts: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            digest.update((part?.toString() ?: "\u0000").toByteArray(Charsets.UTF_8))
            digest.update(SEPARATOR)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val SEPARATOR = byteArrayOf(0x1F)
}
