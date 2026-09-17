package xyz.desent.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import xyz.desent.crypto.Bech32Utils

/**
 * One entry in the user's encrypted email address book
 * (`d = "desent:contacts"`), synced via NIP-78 kind 30078. The whole list is a
 * single replaceable event; this class is one row inside its decrypted JSON.
 *
 * Schema v2 (refs/FROM_email.desent.xyz/PRIVATE_STORAGE_PROTOCOL.md §Contacts,
 * deployed 2026-08-25): labeled `emails`/`phones`/`wallets` arrays,
 * `anniversaries`, an optional `pubkey` (64-char lowercase hex) driving live
 * kind-0 profile enrichment, and the **round-trip rule** — unknown keys in an
 * entry are preserved verbatim in [extras] and re-emitted on publish, so
 * other clients' fields never get stripped.
 *
 * Legacy v1 entries (flat `email` scalar + optional `thread_token`) are folded
 * into v2 on ingest by [PrivateContactSerializer]; migration is lazy and
 * in-memory — the v2 shape is written back on the next save.
 */
data class PrivateContact(
    val name: String = "",
    val emails: List<ContactEmailAddress> = emptyList(),
    val phones: List<ContactPhone> = emptyList(),
    val wallets: List<ContactWallet> = emptyList(),
    val anniversaries: List<ContactAnniversary> = emptyList(),
    /** Linked nostr identity as 64-char lowercase hex; null when absent. */
    val pubkey: String? = null,
    /** Denormalised domain of the primary email (display grouping). */
    val domain: String = "",
    val notes: String? = null,
    /** Unknown/extra keys preserved verbatim for the round-trip rule. */
    val extras: Map<String, JsonElement> = emptyMap()
) {
    /** First email slot — the "primary" address (compose, dedup, domain). */
    val primaryEmail: String get() = emails.firstOrNull { it.value.isNotBlank() }?.value?.trim().orEmpty()

    /** Every non-blank email slot, trimmed (contacts trust matches all slots). */
    fun allEmails(): List<String> = emails.map { it.value.trim() }.filter { it.isNotEmpty() }

    /**
     * Save-time validation (spec §2 rule 4): at least one of a non-empty
     * name, one non-empty email value, or a valid pubkey.
     */
    val isSaveValid: Boolean
        get() = name.isNotBlank() || emails.any { it.value.isNotBlank() } || pubkey != null

    /** Next upcoming anniversary (this year or next), or null when none. */
    fun nextAnniversary(today: java.time.LocalDate = java.time.LocalDate.now()): ContactAnniversary? =
        anniversaries
            .mapNotNull { ann ->
                val date = ann.parsedDate() ?: return@mapNotNull null
                if (date.year > today.year) return@mapNotNull null
                var next = date.withYear(today.year)
                if (next.isBefore(today)) next = date.withYear(today.year + 1)
                ann to next
            }
            .minByOrNull { it.second }
            ?.first

    /** npub form of [pubkey] for display, or null when absent/unformattable. */
    fun npubOrNull(): String? = pubkey?.let {
        runCatching { Bech32Utils.hexToNpub(it) }.getOrNull()
    }
}

/** Labeled email slot (`{label, value}`), first entry is the primary. */
data class ContactEmailAddress(
    val label: String = "",
    val value: String = ""
)

/** Labeled phone slot (`{label, value}`). */
data class ContactPhone(
    val label: String = "",
    val value: String = ""
)

/** Wallet address slot (`{label, value, network}`). [network] is a free-form token. */
data class ContactWallet(
    val label: String = "",
    val value: String = "",
    val network: String = ""
)

/** Anniversary/birthday slot (`{label, date}` with ISO `YYYY-MM-DD`). */
data class ContactAnniversary(
    val label: String = "",
    val date: String = ""
) {
    /** Parsed `YYYY-MM-DD`, or null when the stored date doesn't match. */
    fun parsedDate(): java.time.LocalDate? = runCatching {
        java.time.LocalDate.parse(date)
    }.getOrNull()

    /** Glyph/type hint keyed off the label substrings (spec: birth/annivers). */
    val kind: AnniversaryKind
        get() = when {
            label.contains("birth", ignoreCase = true) -> AnniversaryKind.BIRTHDAY
            label.contains("annivers", ignoreCase = true) -> AnniversaryKind.ANNIVERSARY
            else -> AnniversaryKind.OTHER
        }
}

enum class AnniversaryKind { BIRTHDAY, ANNIVERSARY, OTHER }

/**
 * Kind-0 profile enrichment data for a contact's linked nostr identity.
 * Fetched live (never persisted into the contacts payload) and cached
 * in-memory with a ~10 min TTL including negative entries.
 */
data class ContactProfile(
    val name: String? = null,
    val displayName: String? = null,
    val picture: String? = null,
    val banner: String? = null,
    val about: String? = null,
    val nip05: String? = null,
    val website: String? = null
) {
    /** Merge semantics: prefer non-empty values here, [fallback] fills gaps. */
    fun mergedWith(fallback: ContactProfile): ContactProfile = ContactProfile(
        name = name?.takeIf { it.isNotBlank() } ?: fallback.name,
        displayName = displayName?.takeIf { it.isNotBlank() } ?: fallback.displayName,
        picture = picture?.takeIf { it.isNotBlank() } ?: fallback.picture,
        banner = banner?.takeIf { it.isNotBlank() } ?: fallback.banner,
        about = about?.takeIf { it.isNotBlank() } ?: fallback.about,
        nip05 = nip05?.takeIf { it.isNotBlank() } ?: fallback.nip05,
        website = website?.takeIf { it.isNotBlank() } ?: fallback.website
    )
}

/**
 * Custom (de)serializer implementing the v2 wire contract plus ingest
 * normalization and the v1 → v2 migration:
 *
 *  - v1 flat `email` folds into `emails: [{label:"", value}]`;
 *  - `thread_token` is dropped (deprecated);
 *  - `pubkey` accepts npub/nprofile/64-hex input and stores lowercase hex;
 *  - entries in `emails`/`phones`/`wallets` without a `value` and
 *    `anniversaries` without a `date` are dropped; values are coerced to
 *    strings; non-array array-fields are treated as empty;
 *  - `domain` is derived from the primary email when missing;
 *  - unknown keys land in [PrivateContact.extras] and are re-emitted on
 *    encode (round-trip rule).
 */
object PrivateContactSerializer : KSerializer<PrivateContact> {
    private val KNOWN_KEYS = setOf(
        "name", "emails", "phones", "wallets", "anniversaries", "pubkey", "domain", "notes"
    )
    private val V1_CONSUMED_KEYS = setOf("email", "thread_token")

    override val descriptor: SerialDescriptor =
        kotlinx.serialization.descriptors.buildClassSerialDescriptor("PrivateContact")

    override fun deserialize(decoder: Decoder): PrivateContact {
        val obj = decoder.decodeSerializableValue(JsonObject.serializer()).jsonObject

        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        // Array fields: non-arrays are dropped entirely (ingest normalization).
        fun labeledValues(key: String): List<Pair<String, String>> =
            (obj[key] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonObject)?.let { entry ->
                    val value = (entry["value"] as? JsonPrimitive)?.content?.trim().orEmpty()
                    if (value.isEmpty()) null
                    else (entry["label"] as? JsonPrimitive)?.content.orEmpty() to value
                }
            } ?: emptyList()

        val emails = labeledValues("emails").map { ContactEmailAddress(it.first, it.second) }
        val migratedEmails = if (emails.isEmpty()) {
            str("email")?.trim()?.takeIf { it.isNotBlank() }
                ?.let { listOf(ContactEmailAddress("", it)) } ?: emptyList()
        } else {
            emails
        }
        val phones = labeledValues("phones").map { ContactPhone(it.first, it.second) }
        val wallets = (obj["wallets"] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { entry ->
                val value = (entry["value"] as? JsonPrimitive)?.content?.trim().orEmpty()
                if (value.isEmpty()) null
                else ContactWallet(
                    label = (entry["label"] as? JsonPrimitive)?.content.orEmpty(),
                    value = value,
                    network = (entry["network"] as? JsonPrimitive)?.content.orEmpty()
                )
            }
        } ?: emptyList()
        val anniversaries = (obj["anniversaries"] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { entry ->
                val date = (entry["date"] as? JsonPrimitive)?.content?.trim().orEmpty()
                if (date.isEmpty()) null
                else ContactAnniversary(
                    label = (entry["label"] as? JsonPrimitive)?.content.orEmpty(),
                    date = date
                )
            }
        } ?: emptyList()

        val pubkey = normalizePubkeyInput(str("pubkey")?.trim())

        val domain = str("domain")?.takeIf { it.isNotBlank() }
            ?: migratedEmails.firstOrNull()?.value?.substringAfterLast("@", "")?.takeIf { it.isNotEmpty() }
            ?: ""

        val extras = obj.entries.associate { (k, v) ->
            k to v
        }.filterKeys { it !in KNOWN_KEYS && it !in V1_CONSUMED_KEYS }

        return PrivateContact(
            name = str("name").orEmpty(),
            emails = migratedEmails,
            phones = phones,
            wallets = wallets,
            anniversaries = anniversaries,
            pubkey = pubkey,
            domain = domain,
            notes = str("notes"),
            extras = extras
        )
    }

    override fun serialize(encoder: Encoder, value: PrivateContact) {
        val obj = buildMap {
            put("name", JsonPrimitive(value.name))
            put("emails", JsonArray(value.emails.map { entry ->
                JsonObject(buildMap {
                    put("label", JsonPrimitive(entry.label))
                    put("value", JsonPrimitive(entry.value))
                })
            }))
            put("phones", JsonArray(value.phones.map { entry ->
                JsonObject(buildMap {
                    put("label", JsonPrimitive(entry.label))
                    put("value", JsonPrimitive(entry.value))
                })
            }))
            put("wallets", JsonArray(value.wallets.map { entry ->
                JsonObject(buildMap {
                    put("label", JsonPrimitive(entry.label))
                    put("value", JsonPrimitive(entry.value))
                    put("network", JsonPrimitive(entry.network))
                })
            }))
            put("anniversaries", JsonArray(value.anniversaries.map { entry ->
                JsonObject(buildMap {
                    put("label", JsonPrimitive(entry.label))
                    put("date", JsonPrimitive(entry.date))
                })
            }))
            value.pubkey?.let { put("pubkey", JsonPrimitive(it)) }
            if (value.domain.isNotBlank()) put("domain", JsonPrimitive(value.domain))
            value.notes?.let { put("notes", JsonPrimitive(it)) }
            value.extras.forEach { (k, v) -> put(k, v) }
        }
        encoder.encodeSerializableValue(JsonObject.serializer(), JsonObject(obj))
    }

    /**
     * Accept `npub…`, `nprofile…`, or 64-char hex; return lowercase hex, or
     * null for blank/garbage input. (UI performs live validation before this;
     * ingest-time normalization drops what can't parse.)
     */
    fun normalizePubkeyInput(input: String?): String? {
        if (input.isNullOrBlank()) return null
        return when {
            input.startsWith("npub1") ->
                runCatching { Bech32Utils.npubToHex(input.lowercase()) }.getOrNull()
            input.startsWith("nprofile1") ->
                runCatching { Bech32Utils.nprofileToHex(input.lowercase()) }.getOrNull()
            input.matches(Regex("^[0-9a-fA-F]{64}$")) -> input.lowercase()
            else -> null
        }
    }
}

/** Serializer handle for [List] serialization in the mapper. */
val PrivateContactListSerializer: KSerializer<List<PrivateContact>>
    get() = ListSerializer(PrivateContactSerializer)
