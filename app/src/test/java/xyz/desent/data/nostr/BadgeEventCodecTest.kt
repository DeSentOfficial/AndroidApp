package xyz.desent.data.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgeEventCodecTest {

    // ---------------- Definition (kind 30009) ----------------

    @Test
    fun parseDefinition_imageModeWithUploadUrls() {
        val parsed = BadgeEventCodec.parseDefinition(
            tags = listOf(
                listOf("d", "early-adopter"),
                listOf("name", "Early Adopter"),
                listOf("description", "Joined DeSent before 2026-06-01"),
                listOf("image", "https://desent.xyz/api/badges/assets/abc"),
                listOf("thumb", "https://desent.xyz/api/badges/assets/abc")
            ),
            content = "Early Adopter"
        )!!

        assertEquals("early-adopter", parsed.slug)
        assertEquals("Early Adopter", parsed.name)
        assertEquals("Joined DeSent before 2026-06-01", parsed.description)
        assertEquals("https://desent.xyz/api/badges/assets/abc", parsed.imageUrl)
        assertEquals("https://desent.xyz/api/badges/assets/abc", parsed.thumbUrl)
        assertNull(parsed.iconName)
        assertNull(parsed.color)
    }

    @Test
    fun parseDefinition_iconModeDeSentExtension() {
        val parsed = BadgeEventCodec.parseDefinition(
            tags = listOf(
                listOf("d", "paid-supporter"),
                listOf("name", "Paid Supporter"),
                listOf("icon", "workspace_premium"),
                listOf("color", "#ffB300")
            ),
            content = "Paid Supporter"
        )!!

        assertEquals("workspace_premium", parsed.iconName)
        assertEquals("#ffB300", parsed.color)
        assertNull(parsed.imageUrl)
    }

    @Test
    fun parseDefinition_fallsBackToContentAsName() {
        val parsed = BadgeEventCodec.parseDefinition(
            tags = listOf(listOf("d", "tenure-1y")),
            content = "1 Year on DeSent"
        )!!

        assertEquals("1 Year on DeSent", parsed.name)
    }

    @Test
    fun parseDefinition_rejectsMissingOrInvalidSlug() {
        assertNull(BadgeEventCodec.parseDefinition(tags = emptyList(), content = "x"))
        assertNull(BadgeEventCodec.parseDefinition(tags = listOf(listOf("d", "")), content = "x"))
        // Slugs are lowercase [a-z0-9][a-z0-9_-]{0,49}
        assertNull(BadgeEventCodec.parseDefinition(tags = listOf(listOf("d", "Bad Slug")), content = "x"))
    }

    @Test
    fun parseSlug_worksForTombstonesWithoutNameOrContent() {
        // Retirement tombstone shape: just the d tag, empty content.
        assertEquals("paid-supporter", BadgeEventCodec.parseSlug(listOf(listOf("d", "paid-supporter"))))
        assertNull(BadgeEventCodec.parseSlug(listOf(listOf("d", "Bad Slug"))))
        assertNull(BadgeEventCodec.parseSlug(emptyList()))
    }

    @Test
    fun tombstoneDetection() {
        assertTrue(BadgeEventCodec.isTombstone(null))
        assertTrue(BadgeEventCodec.isTombstone(""))
        assertTrue(BadgeEventCodec.isTombstone("   "))
        assertEquals(false, BadgeEventCodec.isTombstone("badges"))
    }

    // ---------------- Award (kind 8) ----------------

    @Test
    fun parseAward_extractsAwardeeAndSlug() {
        val parsed = BadgeEventCodec.parseAward(
            listOf(
                listOf("p", "aabb7b42d2a08f384bbc03ee7c1864881ccc1381c81dbbd1bd22877ec86b39e3"),
                listOf("a", "30009:aabb7b42d2a08f384bbc03ee7c1864881ccc1381c81dbbd1bd22877ec86b39e3:early-adopter")
            )
        )!!

        assertEquals("aabb7b42d2a08f384bbc03ee7c1864881ccc1381c81dbbd1bd22877ec86b39e3", parsed.awardeePubkeyHex)
        assertEquals("early-adopter", parsed.slug)
        assertEquals(
            "30009:aabb7b42d2a08f384bbc03ee7c1864881ccc1381c81dbbd1bd22877ec86b39e3:early-adopter",
            parsed.definitionAddress
        )
    }

    @Test
    fun parseAward_rejectsNonBadgeAddress() {
        assertNull(BadgeEventCodec.parseAward(listOf(listOf("p", "abc"), listOf("a", "30008:x:y"))))
        assertNull(BadgeEventCodec.parseAward(listOf(listOf("a", "30009:x:y")))) // no p
        assertNull(BadgeEventCodec.parseAward(listOf(listOf("p", "abc")))) // no a
    }

    // ---------------- Pin list (kind 30008) ----------------

    @Test
    fun parsePinPairs_interleavedOrderPreserved() {
        val pairs = BadgeEventCodec.parsePinPairs(
            listOf(
                listOf("d", "profile_badges"),
                listOf("a", "30009:r:early-adopter"),
                listOf("e", "award1"),
                listOf("a", "30009:r:verified"),
                listOf("e", "award2")
            )
        )

        assertEquals(2, pairs.size)
        assertEquals("award1", pairs[0].awardEventId)
        assertEquals("30009:r:early-adopter", pairs[0].definitionAddress)
        assertEquals("award2", pairs[1].awardEventId)
    }

    @Test
    fun parsePinPairs_dropsUnpairedTags() {
        val pairs = BadgeEventCodec.parsePinPairs(
            listOf(
                listOf("d", "profile_badges"),
                listOf("a", "30009:r:orphan"),
                listOf("e", "award1"),
                listOf("e", "award2") // no pending a → dropped
            )
        )

        assertEquals(1, pairs.size)
        assertEquals("award1", pairs[0].awardEventId)
    }

    @Test
    fun buildPinTags_dTagPlusOrderedPairs() {
        val tags = BadgeEventCodec.buildPinTags(
            listOf(
                BadgeEventCodec.PinPair("30009:r:a", "e1"),
                BadgeEventCodec.PinPair("30009:r:b", "e2")
            )
        )

        assertEquals(
            listOf(
                listOf("d", "profile_badges"),
                listOf("a", "30009:r:a"),
                listOf("e", "e1"),
                listOf("a", "30009:r:b"),
                listOf("e", "e2")
            ),
            tags
        )
    }

    @Test
    fun buildPinTags_capsAtTwentyPairs() {
        val many = (1..30).map { BadgeEventCodec.PinPair("30009:r:b$it", "e$it") }
        val tags = BadgeEventCodec.buildPinTags(many)

        // 1 d tag + 20 pairs × 2 tags
        assertEquals(41, tags.size)
        assertTrue(tags.none { it == listOf("e", "e21") })
    }

    @Test
    fun buildPinTags_emptyListIsUnpinAllTombstoneShape() {
        assertEquals(listOf(listOf("d", "profile_badges")), BadgeEventCodec.buildPinTags(emptyList()))
    }
}
