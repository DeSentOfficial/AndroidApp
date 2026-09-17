package xyz.desent.data.contacts

import xyz.desent.crypto.Bech32Utils
import xyz.desent.domain.model.ContactAnniversary
import xyz.desent.domain.model.ContactEmailAddress
import xyz.desent.domain.model.ContactPhone
import xyz.desent.domain.model.ContactWallet
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.model.PrivateContactSerializer

/**
 * vCard 3.0 import/export for the contacts address book
 * (ANDROID_CONTACTS.md §6). A permissive reader (folded lines, multiple
 * BEGIN:VCARD blocks per file) and an escaping-correct writer (RFC 2426).
 */
object VCardCodec {

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /**
     * Parse a `.vcf` file into contact entries. Malformed blocks are
     * skipped rather than failing the whole import. `threadToken`-era v1
     * shapes are irrelevant here — output is v2 model entries directly.
     */
    fun import(text: String): List<PrivateContact> {
        val blocks = unfold(text)
            .split("BEGIN:VCARD")
            .drop(1) // preamble before the first block (if any)
            .mapNotNull { block ->
                val end = block.indexOf("END:VCARD")
                if (end < 0) return@mapNotNull null
                block.substring(0, end).trim()
            }
        return blocks.mapNotNull { block -> parseBlock(block) }
    }

    /** Unfold continuation lines (CRLF + space/tab, per RFC 2426 §2.6). */
    private fun unfold(text: String): String =
        text.replace("\r\n", "\n").replace("\r", "\n")
            .replace(Regex("\n[ \t]"), "")

    private fun parseBlock(block: String): PrivateContact? {
        val props = block.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null
                else {
                    val head = line.substring(0, idx)
                    val value = line.substring(idx + 1)
                    val parts = head.split(";")
                    Property(
                        name = parts.first().trim().uppercase(),
                        params = parts.drop(1),
                        value = value
                    )
                }
            }

        val fn = props.firstOrNull { it.name == "FN" }?.value?.unescape().orEmpty()
        val n = props.firstOrNull { it.name == "N" }?.value?.unescape().orEmpty()
        val nameFromN = n.split(";").filter { it.isNotBlank() }.reversed().joinToString(" ").trim()
        val name = fn.ifBlank { nameFromN }

        val emails = props.filter { it.name == "EMAIL" }.mapNotNull { p ->
            val value = p.value.trim().unescape()
            if (value.isBlank()) null
            else ContactEmailAddress(
                label = p.labelParam().let { normalizeTypeLabel(it) },
                value = value
            )
        }

        val phones = props.filter { it.name == "TEL" }.mapNotNull { p ->
            val value = p.value.trim().unescape()
            if (value.isBlank()) null
            else ContactPhone(
                label = p.labelParam().let { normalizeTypeLabel(it) },
                value = value
            )
        }

        val wallets = mutableListOf<ContactWallet>()
        props.filter { it.name.startsWith("X-") }.forEach { p ->
            val network = walletNetworkFor(p.name)
            val value = p.value.trim().unescape()
            if (network != null && value.isNotBlank()) {
                wallets += ContactWallet(label = p.labelParam(), value = value, network = network)
            }
        }

        val anniversaries = mutableListOf<ContactAnniversary>()
        props.forEach { p ->
            val date = p.value.trim().unescape()
            if (!date.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) return@forEach
            when (p.name) {
                "BDAY" -> anniversaries += ContactAnniversary(label = "Birthday", date = date)
                "ANNIVERSARY", "X-ANNIVERSARY" ->
                    anniversaries += ContactAnniversary(label = "Anniversary", date = date)
            }
        }

        val pubkey = props.firstNotNullOfOrNull { p ->
            when (p.name) {
                "X-NPUB", "X-NOSTR" -> PrivateContactSerializer.normalizePubkeyInput(p.value.trim().unescape())
                else -> null
            }
        }

        val noteParts = mutableListOf<String>()
        props.firstOrNull { it.name == "NOTE" }?.value?.unescape()?.takeIf { it.isNotBlank() }?.let { noteParts += it }
        props.firstOrNull { it.name == "URL" }?.value?.unescape()?.takeIf { it.isNotBlank() }?.let { noteParts += it }

        val contact = PrivateContact(
            name = name,
            emails = emails,
            phones = phones,
            wallets = wallets,
            anniversaries = anniversaries.distinctBy { it.date to it.label },
            pubkey = pubkey,
            domain = emails.firstOrNull()?.value?.substringAfterLast("@", "")?.takeIf { it.isNotEmpty() } ?: "",
            notes = noteParts.takeIf { it.isNotEmpty() }?.joinToString("\n")
        )
        return contact.takeIf { it.isSaveValid }
    }

    /** `TYPE=work` / bare `work` param → label token (first found). */
    private fun Property.labelParam(): String {
        params.forEach { param ->
            val token = when {
                param.startsWith("TYPE=", ignoreCase = true) -> param.substring(5)
                param.contains('=') -> null // other param kinds (ENCODING, PREF, …)
                else -> param
            } ?: return@forEach
            return token.trim().lowercase().takeIf { it.isNotBlank() } ?: ""
        }
        return ""
    }

    /**
     * vCard default TYPEs are not user labels: `internet` (EMAIL) and
     * `voice` (TEL) map back to the empty label so exports round-trip.
     */
    private fun normalizeTypeLabel(label: String): String =
        if (label.equals("internet", ignoreCase = true) || label.equals("voice", ignoreCase = true)) "" else label

    private fun walletNetworkFor(propName: String): String? {
        val upper = propName.uppercase()
        return when {
            upper == "X-BITCOIN" || upper.endsWith("BITCOIN-WALLET") -> "bitcoin"
            upper == "X-LIGHTNING" || upper.endsWith("LIGHTNING-WALLET") -> "lightning"
            upper == "X-ETHEREUM" || upper == "X-ETH" || upper.endsWith("ETHEREUM-WALLET") -> "ethereum"
            upper.endsWith("WALLET") -> "other" // any other X-*…WALLET* form
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    fun export(contact: PrivateContact): String = buildString {
        appendLine("BEGIN:VCARD")
        appendLine("VERSION:3.0")
        val name = contact.name.ifBlank {
            contact.npubOrNull()?.let { "npub ${it.take(10)}…" } ?: contact.primaryEmail.substringBefore("@")
        }
        appendLine("FN:${name.escape()}")
        appendLine("N:${name.escape()};;;;")
        contact.emails.forEach { email ->
            val type = email.label.takeIf { it.isNotBlank() } ?: "internet"
            appendLine("EMAIL;TYPE=${type.escape()}:${email.value.escape()}")
        }
        contact.phones.forEach { phone ->
            val type = phone.label.takeIf { it.isNotBlank() } ?: "voice"
            appendLine("TEL;TYPE=${type.escape()}:${phone.value.escape()}")
        }
        contact.wallets.forEach { wallet ->
            val prop = when (wallet.network.lowercase()) {
                "bitcoin" -> "X-BITCOIN"
                "lightning" -> "X-LIGHTNING"
                "ethereum", "eth" -> "X-ETHEREUM"
                else -> "X-${wallet.network.uppercase().replace(Regex("[^A-Z0-9]"), "").ifBlank { "OTHER" }}-WALLET"
            }
            appendLine("$prop:${wallet.value.escape()}")
        }
        contact.anniversaries.forEach { ann ->
            if (!ann.date.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) return@forEach
            if (ann.kind == xyz.desent.domain.model.AnniversaryKind.BIRTHDAY) {
                appendLine("BDAY:${ann.date}")
            } else {
                val labelToken = ann.label.trim()
                    .uppercase().replace(Regex("[^A-Z0-9]"), "").take(16).ifBlank { "DATE" }
                appendLine("X-ANNIVERSARY;X-LABEL=$labelToken:${ann.date}")
            }
        }
        contact.pubkey?.let { hex ->
            runCatching { Bech32Utils.hexToNpub(hex) }.getOrNull()?.let { npub ->
                appendLine("X-NPUB:$npub")
            }
        }
        contact.notes?.takeIf { it.isNotBlank() }?.let { appendLine("NOTE:${it.escape()}") }
        appendLine("END:VCARD")
    }

    fun exportAll(contacts: List<PrivateContact>): String =
        contacts.joinToString("") { export(it) }

    /** RFC 2426 §2.4.2 text escaping, applied to values (not labels). */
    private fun String.escape(): String =
        replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace(",", "\\,")
            .replace(";", "\\;")

    private fun String.unescape(): String =
        replace("\\n", "\n")
            .replace("\\N", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")

    private data class Property(val name: String, val params: List<String>, val value: String)
}
