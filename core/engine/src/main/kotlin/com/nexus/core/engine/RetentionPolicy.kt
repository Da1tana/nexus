package com.nexus.core.engine

/** Политика хранения истории. 0 дней — хранить без ограничения. */
object RetentionPolicy {
    val options: List<Int> = listOf(0, 7, 30, 90, 365)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Граница времени: сообщения старше неё удаляются. Null — ничего не удалять. */
    fun cutoff(nowMillis: Long, retentionDays: Int): Long? =
        if (retentionDays <= 0) null else nowMillis - retentionDays * DAY_MS

    fun label(days: Int): String = when (days) {
        0 -> "Без ограничения"
        7 -> "7 дней"
        30 -> "30 дней"
        90 -> "90 дней"
        365 -> "1 год"
        else -> "$days дн."
    }
}
