package com.nexus.core.engine.search

/**
 * Преобразует пользовательский ввод в безопасное выражение FTS4 MATCH.
 *
 * - Каждое слово превращается в префиксный терм `слово*` (частичное совпадение).
 * - Слова объединяются через пробел (логическое И).
 * - Спецсимволы FTS (кавычки, `*`, `-`, `:`, скобки) отбрасываются, поэтому ввод
 *   пользователя не может сломать синтаксис запроса.
 * - Регистр не важен: токенайзер unicode61 сам приводит кириллицу и латиницу к нижнему регистру.
 *
 * Возвращает null, если в запросе нет ни одного слова.
 */
object FtsQueryBuilder {

    private const val MAX_TERMS = 8

    fun build(input: String): String? {
        val terms = tokenize(input)
        if (terms.isEmpty()) return null
        // Термы состоят только из букв и цифр в нижнем регистре, поэтому кавычки не нужны,
        // а операторы FTS (AND/OR/NOT/NEAR — только в верхнем регистре) не могут возникнуть.
        return terms.joinToString(" ") { "$it*" }
    }

    fun tokenize(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in input) {
            if (ch.isLetterOrDigit()) {
                current.append(ch.lowercaseChar())
            } else if (current.isNotEmpty()) {
                result += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result.distinct().take(MAX_TERMS)
    }
}
