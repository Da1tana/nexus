package com.nexus.core.engine

import com.nexus.core.engine.search.FtsQueryBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryBuilderTest {

    @Test
    fun cyrillicAndLatin_becomePrefixTerms() {
        assertEquals("привет* hello*", FtsQueryBuilder.build("Привет, Hello!"))
    }

    @Test
    fun ftsOperators_areNeutralized() {
        assertEquals("a* or* b*", FtsQueryBuilder.build("a OR \"b\""))
        assertEquals("near* test*", FtsQueryBuilder.build("NEAR(test)"))
        assertEquals("x* y*", FtsQueryBuilder.build("-x* y:"))
    }

    @Test
    fun emptyOrSymbolsOnly_returnsNull() {
        assertNull(FtsQueryBuilder.build(""))
        assertNull(FtsQueryBuilder.build("  *** --- "))
    }

    @Test
    fun duplicatesRemoved_andTermsLimited() {
        assertEquals("да*", FtsQueryBuilder.build("да ДА да"))
        assertEquals(8, FtsQueryBuilder.tokenize("a b c d e f g h i j").size)
    }

    @Test
    fun yoIsKeptAsIs() {
        assertEquals(listOf("ёлка"), FtsQueryBuilder.tokenize("Ёлка"))
    }
}
