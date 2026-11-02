package com.nexus.core.engine

import com.nexus.core.engine.parser.DedupKeys
import com.nexus.core.engine.parser.HiddenContentDetector
import com.nexus.core.engine.source.SourceCatalog
import com.nexus.core.model.IntegrationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscEngineTest {

    @Test
    fun dedupKeys_areDeterministicAndSeparated() {
        assertEquals(DedupKeys.of("a", 1, null), DedupKeys.of("a", 1, null))
        // Разделитель не даёт склеить «ab|c» и «a|bc».
        assertNotEquals(DedupKeys.of("ab", "c"), DedupKeys.of("a", "bc"))
        assertNotEquals(DedupKeys.of(null), DedupKeys.of(""))
    }

    @Test
    fun hiddenContent_detection() {
        assertTrue(HiddenContentDetector.isHidden("Новое сообщение"))
        assertTrue(HiddenContentDetector.isHidden("3 новых сообщения"))
        assertTrue(HiddenContentDetector.isHidden("2 new messages"))
        assertTrue(HiddenContentDetector.isHidden("Contents hidden"))
        assertFalse(HiddenContentDetector.isHidden("Привет, как дела?"))
        assertFalse(HiddenContentDetector.isHidden(""))
    }

    @Test
    fun retention_cutoff() {
        assertNull(RetentionPolicy.cutoff(1_000_000_000, 0))
        assertEquals(1_000_000_000L - 7L * 86_400_000, RetentionPolicy.cutoff(1_000_000_000, 7))
    }

    @Test
    fun catalog_knownAndUnknownSources() {
        assertTrue(SourceCatalog.isKnownMessenger("org.telegram.messenger"))
        assertEquals(IntegrationLevel.NOTIFICATIONS, SourceCatalog.capabilitiesFor("com.google.android.gm").level)
        assertFalse(SourceCatalog.isKnownMessenger("com.example.shop"))
        assertFalse(SourceCatalog.capabilitiesFor("com.example.shop").canReadHistory)
        // Ни один источник в v1 не заявляет чтение истории или официальную отправку.
        assertTrue(SourceCatalog.known.none { it.capabilities.canReadHistory || it.capabilities.level == IntegrationLevel.FULL_API })
    }
}
