package xyz.desent.data.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.crypto.Bech32Utils
import xyz.desent.domain.model.AnniversaryKind
import xyz.desent.domain.model.ContactAnniversary
import xyz.desent.domain.model.ContactEmailAddress
import xyz.desent.domain.model.ContactPhone
import xyz.desent.domain.model.ContactWallet
import xyz.desent.domain.model.PrivateContact

class VCardCodecTest {

    // Deterministic test key (any valid secp-ish 32 bytes works for bech32).
    private val hexKey = "39c83a20032ee04e641cf48996988c6c34c8d0bd60f86dc3c63866dabf5d431e"

    @Test
    fun `import maps spec properties`() {
        val vcf = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Ann Lee
            N:Lee;Ann;;;
            EMAIL;TYPE=work:ann@corp.example
            EMAIL;TYPE=home:ann@home.example
            TEL;TYPE=cell:+1 555 010 9999
            BDAY:1990-05-12
            X-NPUB:${Bech32Utils.hexToNpub(hexKey)}
            X-LIGHTNING:ann@strike.army
            NOTE:Met at the Oslo Nostr meetup
            URL:https://ann.example
            END:VCARD
        """.trimIndent()

        val contacts = VCardCodec.import(vcf)
        assertEquals(1, contacts.size)
        val c = contacts[0]
        assertEquals("Ann Lee", c.name)
        assertEquals(2, c.emails.size)
        assertEquals(ContactEmailAddress("work", "ann@corp.example"), c.emails[0])
        assertEquals(ContactEmailAddress("home", "ann@home.example"), c.emails[1])
        assertEquals(listOf(ContactPhone("cell", "+1 555 010 9999")), c.phones)
        assertEquals(listOf(ContactAnniversary("Birthday", "1990-05-12")), c.anniversaries)
        assertEquals(hexKey, c.pubkey)
        assertEquals(listOf(ContactWallet("", "ann@strike.army", "lightning")), c.wallets)
        assertTrue(c.notes!!.contains("Met at the Oslo Nostr meetup"))
        assertTrue(c.notes!!.contains("https://ann.example"))
        assertEquals("corp.example", c.domain)
    }

    @Test
    fun `import handles folded lines and multiple blocks`() {
        val vcf = """
            BEGIN:VCARD
            FN:Folded
             Name
            EMAIL:a@x.com
            END:VCARD
            BEGIN:VCARD
            FN:Second
            EMAIL:b@y.com
            END:VCARD
        """.trimIndent()
        val contacts = VCardCodec.import(vcf)
        assertEquals(2, contacts.size)
        // RFC 2426 §2.6: unfolding removes the line break AND the single
        // leading white space (the fold marker).
        assertEquals("FoldedName", contacts[0].name)
        assertEquals("b@y.com", contacts[1].primaryEmail)
    }

    @Test
    fun `import maps anniversary properties and wallet networks`() {
        val vcf = """
            BEGIN:VCARD
            FN:C
            ANNIVERSARY:2015-06-01
            X-BITCOIN:bc1qxyz
            X-ETHEREUM:0xabc
            X-MONERO-WALLET:whatever
            END:VCARD
        """.trimIndent()
        val c = VCardCodec.import(vcf).single()
        assertEquals(listOf(ContactAnniversary("Anniversary", "2015-06-01")), c.anniversaries)
        assertEquals(3, c.wallets.size)
        assertEquals("bitcoin", c.wallets[0].network)
        assertEquals("ethereum", c.wallets[1].network)
        assertEquals("other", c.wallets[2].network)
    }

    @Test
    fun `export emits spec shape and escapes text`() {
        val contact = PrivateContact(
            name = "Ann; Lee",
            emails = listOf(ContactEmailAddress("work", "ann@corp.example")),
            phones = listOf(ContactPhone("mobile", "+1 555")),
            wallets = listOf(ContactWallet("", "ann@strike.army", "lightning")),
            anniversaries = listOf(
                ContactAnniversary("Birthday", "1990-05-12"),
                ContactAnniversary("Wedding", "2015-06-01")
            ),
            pubkey = hexKey,
            domain = "corp.example",
            notes = "line1\nline2"
        )
        val vcf = VCardCodec.export(contact)
        assertTrue(vcf.contains("VERSION:3.0"))
        assertTrue(vcf.contains("FN:Ann\\; Lee"))
        assertTrue(vcf.contains("EMAIL;TYPE=work:ann@corp.example"))
        assertTrue(vcf.contains("TEL;TYPE=mobile:+1 555"))
        assertTrue(vcf.contains("X-LIGHTNING:ann@strike.army"))
        assertTrue(vcf.contains("BDAY:1990-05-12"))
        assertTrue(vcf.contains("X-ANNIVERSARY;X-LABEL=WEDDING:2015-06-01"))
        assertTrue(vcf.contains("X-NPUB:${Bech32Utils.hexToNpub(hexKey)}"))
        assertTrue(vcf.contains("NOTE:line1\\nline2"))
    }

    @Test
    fun `round trip preserves multi-emails labels tel bday npub lightning`() {
        val contact = PrivateContact(
            name = "Round Trip",
            emails = listOf(
                ContactEmailAddress("work", "rt@corp.example"),
                ContactEmailAddress("", "rt@home.example")
            ),
            phones = listOf(ContactPhone("mobile", "+1 555 010 9999")),
            wallets = listOf(ContactWallet("", "rt@strike.army", "lightning")),
            anniversaries = listOf(ContactAnniversary("Birthday", "1990-05-12")),
            pubkey = hexKey,
            domain = "corp.example",
            notes = "note"
        )
        val restored = VCardCodec.import(VCardCodec.export(contact)).single()
        assertEquals(contact.name, restored.name)
        assertEquals(contact.emails, restored.emails)
        assertEquals(contact.phones, restored.phones)
        assertEquals(contact.wallets, restored.wallets)
        assertEquals(contact.anniversaries, restored.anniversaries)
        assertEquals(contact.pubkey, restored.pubkey)
        assertEquals(contact.notes, restored.notes)
    }

    @Test
    fun `bech32 nprofile parses to hex and npub round trips`() {
        // nprofile1qq... carries the same key under TLV type 0.
        val npub = Bech32Utils.hexToNpub(hexKey)
        assertEquals(hexKey, Bech32Utils.npubToHex(npub))
        // Build a minimal nprofile via the codec's parser: use a known-good
        // vector built from the same key (relay list empty).
        val nprofile = buildNprofile(hexKey)
        assertEquals(hexKey, Bech32Utils.nprofileToHex(nprofile))
    }

    @Test
    fun `normalizePubkeyInput accepts npub nprofile hex and rejects garbage`() {
        val npub = Bech32Utils.hexToNpub(hexKey)
        assertEquals(hexKey, xyz.desent.domain.model.PrivateContactSerializer.normalizePubkeyInput(npub))
        assertEquals(hexKey, xyz.desent.domain.model.PrivateContactSerializer.normalizePubkeyInput(hexKey.uppercase()))
        assertEquals(hexKey, xyz.desent.domain.model.PrivateContactSerializer.normalizePubkeyInput(buildNprofile(hexKey)))
        assertNull(xyz.desent.domain.model.PrivateContactSerializer.normalizePubkeyInput(null))
        assertNull(xyz.desent.domain.model.PrivateContactSerializer.normalizePubkeyInput(""))
        assertNull(xyz.desent.domain.model.PrivateContactSerializer.normalizePubkeyInput("npub1invalid"))
        assertNull(xyz.desent.domain.model.PrivateContactSerializer.normalizePubkeyInput("zzz"))
    }

    @Test
    fun `anniversary helpers compute kind and next occurrence`() {
        val birthday = ContactAnniversary("Birthday", "1990-05-12")
        assertEquals(AnniversaryKind.BIRTHDAY, birthday.kind)
        val contact = PrivateContact(name = "A", anniversaries = listOf(birthday))
        assertNotNull(contact.nextAnniversary(java.time.LocalDate.now()))
        assertEquals(null, PrivateContact(name = "A").nextAnniversary())
    }
}

/** Minimal nprofile encoder for test vectors: TLV type 0x00 + 32-byte key. */
private fun buildNprofile(hex: String): String {
    val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val tlv = byteArrayOf(0x00, 32) + bytes
    // 8→5 bit conversion
    var acc = 0
    var bits = 0
    val out = mutableListOf<Int>()
    for (b in tlv) {
        acc = (acc shl 8) or (b.toInt() and 0xFF)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            out.add((acc shr bits) and 31)
        }
    }
    if (bits > 0) out.add((acc shl (5 - bits)) and 31)

    // bech32 checksum
    val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    fun polymod(values: List<Int>): Int {
        var chk = 1
        for (v in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) {
                if ((top shr i) and 1 == 1) chk = chk xor generator[i]
            }
        }
        return chk
    }
    val hrp = "nprofile"
    val hrpExpanded = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }
    val values = out.toMutableList()
    val check = (0..5).map { (polymod(hrpExpanded + values + List(6) { 0 }) xor 1) shr (5 * (5 - it)) and 31 }
    values.addAll(check)
    return hrp + "1" + values.joinToString("") { charset[it].toString() }
}
