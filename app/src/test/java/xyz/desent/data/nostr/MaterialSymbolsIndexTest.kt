package xyz.desent.data.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialSymbolsIndexTest {

    @Test
    fun parse_parsesNameAndHexCodepoint() {
        val index = MaterialSymbolsIndex.parse(
            listOf("workspace_premium e7af", "verified ef76")
        )

        assertEquals(0xE7AF, index["workspace_premium"])
        assertEquals(0xEF76, index["verified"])
    }

    @Test
    fun parse_skipsBlankAndMalformedLines() {
        val index = MaterialSymbolsIndex.parse(
            listOf(
                "",               // blank
                "   ",            // whitespace-only
                "nothex",         // missing codepoint
                "bad zz11",       // non-hex codepoint
                "two words e000", // name containing a space
                "ok e001"
            )
        )

        assertEquals(mapOf("ok" to 0xE001), index)
    }

    @Test
    fun parse_firstNameWinsOnDuplicate() {
        val index = MaterialSymbolsIndex.parse(
            listOf("star e8d0", "star f000")
        )

        assertEquals(0xE8D0, index["star"])
    }

    @Test
    fun parse_rejectsInvalidCodepoints() {
        val index = MaterialSymbolsIndex.parse(
            listOf("toobig 110000") // beyond Unicode range
        )

        assertTrue(index.isEmpty())
        assertNull(index["toobig"])
    }

    @Test
    fun parse_handlesRealCodepointsFileVocabulary() {
        // Values copied from assets/material_symbols_rounded.codepoints —
        // the badge doc's example glyphs must resolve.
        val index = MaterialSymbolsIndex.parse(
            listOf(
                "verified ef76",
                "workspace_premium e7af",
                "military_tech ea3f",
                "10k e951"
            )
        )

        assertEquals(4, index.size)
        assertEquals(0xE951, index["10k"]) // names may start with a digit
    }
}
