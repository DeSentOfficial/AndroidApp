package xyz.desent.data.nostr

/**
 * Pure parse/build helpers for the NIP-58 badge wire format
 * (refs/FROM_email.desent.xyz/BADGES_PROTOCOL.md). Operates on plain tag
 * lists (`[["d","…"], ["a","…"]]`) so it is unit-testable without Android
 * or event signing; [xyz.desent.data.repository.BadgeRepositoryImpl]
 * converts `GenericEvent` tags to this shape before calling in.
 */
object BadgeEventCodec {

    /** Kind 30008 `d` tag value (the convention on this relay). */
    const val PROFILE_BADGES_D_TAG = "profile_badges"

    /**
     * Non-empty kind 30008 content (the relay rejects empty content when
     * badge pairs are present; empty content + `d` tag = unpin-all tombstone).
     */
    const val PIN_CONTENT = "badges"

    /** Relay-enforced pin list ceiling (BADGES_PROTOCOL.md §30008). */
    const val MAX_PIN_PAIRS = 20

    /** Parsed kind 30009 definition. `null` name means unusable. */
    data class DefinitionParse(
        val slug: String,
        val name: String,
        val description: String? = null,
        val imageUrl: String? = null,
        val thumbUrl: String? = null,
        val iconName: String? = null,
        val color: String? = null
    )

    /** Parsed kind 8 award. */
    data class AwardParse(
        val awardeePubkeyHex: String,
        val definitionAddress: String,
        val slug: String
    )

    /** One ordered `a`+`e` pair of a kind 30008 pin list. */
    data class PinPair(val definitionAddress: String, val awardEventId: String)

    private fun tagValue(tags: List<List<String>>, name: String): String? =
        tags.firstOrNull { it.isNotEmpty() && it[0] == name }?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private val SLUG_REGEX = Regex("[a-z0-9][a-z0-9_-]{0,49}")

    /**
     * The `d` slug of a definition event, validated. Works for tombstones
     * (empty content, no name) as well as full definitions.
     */
    fun parseSlug(tags: List<List<String>>): String? =
        tagValue(tags, "d")?.takeIf { it.matches(SLUG_REGEX) }

    /**
     * Parse a kind 30009 definition from its tags + content. Returns null
     * when the event carries no usable `d` slug or no resolvable name
     * (tombstones carry no name — use [parseSlug] + [isTombstone] for those).
     */
    fun parseDefinition(tags: List<List<String>>, content: String): DefinitionParse? {
        val slug = parseSlug(tags) ?: return null
        val name = tagValue(tags, "name")?.takeIf { it.isNotBlank() }
            ?: content.trim().takeIf { it.isNotBlank() }
            ?: return null
        return DefinitionParse(
            slug = slug,
            name = name,
            description = tagValue(tags, "description"),
            imageUrl = tagValue(tags, "image"),
            thumbUrl = tagValue(tags, "thumb"),
            iconName = tagValue(tags, "icon"),
            color = tagValue(tags, "color")
        )
    }

    /**
     * On this relay, an empty-content publish of a parameterized-replaceable
     * kind is a tombstone (hard delete) — that is how badge retirement works.
     */
    fun isTombstone(content: String?): Boolean = content.isNullOrBlank()

    /**
     * Parse a kind 8 award. Requires a `p` (awardee) and an `a` tag
     * addressing a kind 30009 definition (`30009:<pubkey>:<slug>`).
     */
    fun parseAward(tags: List<List<String>>): AwardParse? {
        val awardee = tagValue(tags, "p") ?: return null
        val address = tagValue(tags, "a") ?: return null
        val slug = definitionSlug(address) ?: return null
        return AwardParse(awardeePubkeyHex = awardee, definitionAddress = address, slug = slug)
    }

    /** Extract the slug from a `30009:<relay npub>:<slug>` address. */
    fun definitionSlug(address: String): String? {
        val parts = address.split(":")
        if (parts.size != 3 || parts[0] != "30009") return null
        return parts[2].takeIf { it.isNotBlank() }
    }

    /**
     * Parse the ordered `a`+`e` pairs of a kind 30008 pin list. An `a` is
     * paired with the next `e` that follows it; unpaired tags are dropped.
     */
    fun parsePinPairs(tags: List<List<String>>): List<PinPair> {
        val pairs = mutableListOf<PinPair>()
        var pendingAddress: String? = null
        for (tag in tags) {
            if (tag.size < 2) continue
            when (tag[0]) {
                "a" -> pendingAddress = tag[1]
                "e" -> {
                    val address = pendingAddress
                    if (address != null) {
                        pairs.add(PinPair(definitionAddress = address, awardEventId = tag[1]))
                        pendingAddress = null
                    }
                }
            }
        }
        return pairs
    }

    /**
     * Build the full tag set for a kind 30008 publish: the `d` tag plus the
     * ordered `a`+`e` pairs. Pairs beyond [MAX_PIN_PAIRS] are dropped (the
     * relay rejects >20 anyway).
     */
    fun buildPinTags(pairs: List<PinPair>): List<List<String>> = buildList {
        add(listOf("d", PROFILE_BADGES_D_TAG))
        pairs.take(MAX_PIN_PAIRS).forEach { pair ->
            add(listOf("a", pair.definitionAddress))
            add(listOf("e", pair.awardEventId))
        }
    }
}
