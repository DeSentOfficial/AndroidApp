package xyz.desent.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import nostr.event.impl.GenericEvent
import nostr.id.Identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.OpenPgpCrypto
import xyz.desent.crypto.GiftWrapEncryptionService
import xyz.desent.crypto.Nip44Encryption
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.RelayConfig
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.local.database.dao.EmailOutboxDao
import xyz.desent.data.local.database.dao.EmailForwardLedgerDao
import xyz.desent.data.local.database.entity.AccountEntity
import xyz.desent.data.local.database.entity.EmailEntity
import xyz.desent.data.local.database.entity.EmailOutboxEntity
import xyz.desent.data.local.database.entity.EmailForwardLedgerEntity
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.message.MessageClient
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.domain.model.EmailDirection
import xyz.desent.domain.repository.RegistrationRepository
import xyz.desent.domain.repository.RelayRepository

/**
 * Unified NIP-EMAIL outbound path (kind 1010). See
 * refs/FromServer/EMAIL_NIP_ANDROID_MIGRATION.md §6 (one constructor for
 * reply + cold send) and §8 (legacy anchors stay replyable).
 */
class EmailRepositoryImplTest {

    private val privHex = "d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa"
    private val identity = Identity.create(privHex)
    private val userNpub = Bech32Utils.hexToNpub(identity.publicKey.toHexString())

    // The migration target: a second real keypair.
    private val targetPrivHex = "11".repeat(32)
    private val targetNpub = Bech32Utils.hexToNpub(Identity.create(targetPrivHex).publicKey.toHexString())

    private lateinit var emailDao: EmailDao
    private lateinit var outboxDao: EmailOutboxDao
    private lateinit var ledgerDao: EmailForwardLedgerDao
    private lateinit var accountDao: AccountDao
    private lateinit var relayRepo: RelayRepository
    private lateinit var secureKeyManager: SecureKeyManager
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var registrationRepo: RegistrationRepository
    private lateinit var giftWrap: GiftWrapEncryptionService
    private lateinit var messageClient: MessageClient
    private lateinit var repo: EmailRepositoryImpl
    private lateinit var wrapEvent: GenericEvent

    private val kindSlot = slot<Int>()
    private val extraTagsSlot = slot<List<List<String>>>()
    private val rowSlot = slot<EmailEntity>()
    private val outboxSlot = slot<EmailOutboxEntity>()
    private val ledgerSlot = slot<EmailForwardLedgerEntity>()

    @Before
    fun setUp() {
        emailDao = mockk(relaxed = true)
        outboxDao = mockk(relaxed = true)
        ledgerDao = mockk(relaxed = true)
        accountDao = mockk(relaxed = true)
        relayRepo = mockk(relaxed = true)
        secureKeyManager = mockk()
        preferencesManager = mockk()
        registrationRepo = mockk()
        giftWrap = mockk()
        messageClient = mockk(relaxed = true)

        coEvery { secureKeyManager.getIdentityFromStoredNSEC() } returns Result.success(identity)
        every { preferencesManager.npubKey } returns flowOf(userNpub)
        coEvery { accountDao.getAccount(userNpub) } returns AccountEntity(
            npub = userNpub, displayName = null, picture = null, nip05 = "alice@desent.xyz"
        )
        // A real signed event standing in for the ephemeral wrap wrapGift() builds.
        wrapEvent = GenericEvent.builder()
            .pubKey(identity.publicKey)
            .kind(NostrKinds.GIFT_WRAP)
            .createdAt(1_000L)
            .content("wrapped")
            .tags(emptyList())
            .build()
        identity.sign(wrapEvent)
        coEvery {
            giftWrap.wrapGift(any(), RelayConfig.RELAY_PUBKEY_NPUB, userNpub, capture(kindSlot), capture(extraTagsSlot))
        } returns Result.success(wrapEvent)
        // Forwarding wraps to an arbitrary recipient npub (NIP-EMAIL forwarding).
        coEvery {
            giftWrap.wrapGift(any(), targetNpub, userNpub, capture(kindSlot), capture(extraTagsSlot))
        } returns Result.success(wrapEvent)
        coEvery { outboxDao.insert(capture(outboxSlot)) } returns Unit
        coEvery { emailDao.insertEmail(capture(rowSlot)) } returns Unit
        coEvery { ledgerDao.upsert(capture(ledgerSlot)) } returns Unit

        repo = EmailRepositoryImpl(
            emailDao = emailDao,
            emailOutboxDao = outboxDao,
            emailForwardLedgerDao = ledgerDao,
            accountDao = accountDao,
            emailMapper = xyz.desent.data.mapper.EmailMapper(),
            relayRepository = relayRepo,
            secureKeyManager = secureKeyManager,
            preferencesManager = preferencesManager,
            registrationRepository = registrationRepo,
            messageClient = messageClient,
            giftWrapEncryptionService = giftWrap
        )
    }

    private fun tag(name: String): String? =
        extraTagsSlot.captured.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)

    /** Every value of a multi-valued tag (to/cc/bcc: one per mailbox, END-01 §3.4). */
    private fun tags(name: String): List<List<String>> =
        extraTagsSlot.captured.filter { it.firstOrNull() == name }

    // ---------------------------------------------------------------
    // Replies
    // ---------------------------------------------------------------

    @Test
    fun reply_toLegacyKind14Anchor_mapsSenderToThreadContinuity() = runBlocking {
        val anchor = EmailEntity(
            id = "giftwrap-legacy-1",
            recipientNpub = userNpub,
            senderEmail = "bob@example.com",
            senderDomain = "example.com",
            subject = "Hello",
            content = "hi",
            dkimStatus = "PASS",
            threadToken = "NBRIDGE:v1:old-thread",
            emailType = "OTHER",
            bridge = "email",
            messageId = "orig@example.com",
            createdAt = 1_000L
        )
        coEvery { emailDao.getEmailById(anchor.id) } returns anchor

        val result = repo.sendReply(anchor.id, "thanks!", xyz.desent.domain.model.EmailBodyFormat.HTML)
        assertTrue(result.isSuccess)

        // Kind 1010 rumor, gift-wrapped to the relay npub — now carrying the
        // quoted anchor below the reply (legacy row: bodyFormat null → HTML).
        coVerify {
            giftWrap.wrapGift(
                match {
                    it.startsWith("thanks!<br><br><div class=\"gmail_quote\">On ") &&
                        it.endsWith(", bob@example.com wrote:<blockquote>hi</blockquote></div>")
                },
                RelayConfig.RELAY_PUBKEY_NPUB, userNpub, NostrKinds.EMAIL_MESSAGE, any()
            )
        }
        assertEquals(NostrKinds.EMAIL_MESSAGE, kindSlot.captured)

        // Unified tag vocabulary.
        assertEquals("alice@desent.xyz", tag("from"))
        assertEquals("bob@example.com", tag("to"))
        assertEquals("Re: Hello", tag("subject"))
        assertEquals("outbound", tag("direction"))
        assertEquals("html", tag("format"))
        // Threading ids go out angle-bracketed (the relay copies them verbatim
        // into SMTP In-Reply-To / References headers).
        assertEquals("<orig@example.com>", tag("in_reply_to"))
        // RFC 5322 References: the anchor's own message-id.
        assertEquals("<orig@example.com>", tag("references"))

        // The outbound row joins the LEGACY thread via its server token and
        // remembers the body format it was sent in. The quoted history rides
        // in every downstream copy (row + outbox = the retry source).
        assertEquals(EmailDirection.OUTBOUND.name, rowSlot.captured.direction)
        assertEquals("NBRIDGE:v1:old-thread", rowSlot.captured.threadRoot)
        assertEquals("bob@example.com", rowSlot.captured.toEmail)
        assertEquals("HTML", rowSlot.captured.bodyFormat)
        assertTrue(rowSlot.captured.content.contains("<blockquote>hi</blockquote>"))

        // Pending outbox entry for receipt correlation.
        assertEquals("bob@example.com", outboxSlot.captured.toEmail)
        assertEquals("Re: Hello", outboxSlot.captured.subject)
        assertEquals("NBRIDGE:v1:old-thread", outboxSlot.captured.threadKey)
        assertTrue(outboxSlot.captured.body.contains("<blockquote>hi</blockquote>"))
    }

    @Test
    fun reply_toPgpEncryptedAnchor_sendsWithoutQuotedHistory() = runBlocking {
        // PGP anchors are armored ciphertext — quoting them is meaningless.
        val anchor = EmailEntity(
            id = "gw-pgp", recipientNpub = userNpub,
            senderEmail = "bob@example.com", senderDomain = "example.com",
            subject = "Re: secret", content = "-----BEGIN PGP MESSAGE-----",
            dkimStatus = "NONE",
            threadToken = null, emailType = "OTHER", bridge = "email",
            messageId = "mpgp@x", threadRoot = "root-key",
            createdAt = 1_000L, isPgpEncrypted = true
        )
        coEvery { emailDao.getEmailById("gw-pgp") } returns anchor

        assertTrue(repo.sendReply("gw-pgp", "thanks!").isSuccess)
        coVerify { giftWrap.wrapGift("thanks!", RelayConfig.RELAY_PUBKEY_NPUB, userNpub, NostrKinds.EMAIL_MESSAGE, any()) }
    }

    @Test
    fun reply_stampsRowMonotonicallyAfterThreadMaxSortKey() = runBlocking {
        // The thread's max effective sort key sits in the FUTURE (sender/relay
        // clocks ahead of this device): the fresh send must still sort below
        // every stored message, so its row is clamped to maxKey + 1.
        val futureMax = System.currentTimeMillis() + 3_600_000L
        coEvery { emailDao.getThreadMaxSortKey(userNpub, "root-key") } returns futureMax

        val anchor = EmailEntity(
            id = "gw-clock", recipientNpub = userNpub,
            senderEmail = "bob@example.com", senderDomain = "example.com",
            subject = "Re: order", content = "x",
            dkimStatus = "NONE",
            threadToken = null, emailType = "OTHER", bridge = "email",
            messageId = "m@x", threadRoot = "root-key",
            createdAt = 1_000L
        )
        coEvery { emailDao.getEmailById("gw-clock") } returns anchor

        assertTrue(repo.sendReply("gw-clock", "here").isSuccess)
        assertEquals(futureMax + 1, rowSlot.captured.senderDate)
        assertEquals(futureMax + 1, rowSlot.captured.createdAt)
    }

    @Test
    fun reply_honorsReplyToHeaderOverSender() = runBlocking {
        val anchor = EmailEntity(
            id = "gw2", recipientNpub = userNpub,
            senderEmail = "noreply@ml.example.com", senderDomain = "example.com",
            replyTo = "human@example.com",
            subject = "Re: already prefixed", content = "x",
            dkimStatus = "NONE",
            threadToken = null, emailType = "OTHER", bridge = "email",
            messageId = "m2@x", threadRoot = "root-key",
            createdAt = 1_000L
        )
        coEvery { emailDao.getEmailById("gw2") } returns anchor

        assertTrue(repo.sendReply("gw2", "ok").isSuccess)
        assertEquals("human@example.com", tag("to"))
        // Subject already carries Re: — not double-prefixed.
        assertEquals("Re: already prefixed", tag("subject"))
        assertEquals("root-key", rowSlot.captured.threadRoot)
    }

    @Test
    fun reply_toOwnOutboundAnchor_sendsBackToSameAddress() = runBlocking {
        val anchor = EmailEntity(
            id = "gw3", recipientNpub = userNpub,
            senderEmail = "alice@desent.xyz", senderDomain = "desent.xyz",
            subject = "Re: Hello", content = "previous",
            dkimStatus = "NONE",
            threadToken = null, emailType = "OTHER", bridge = "email",
            messageId = "mine@desent.xyz", threadRoot = "root-key",
            toEmail = "bob@example.com", direction = EmailDirection.OUTBOUND.name,
            createdAt = 1_000L
        )
        coEvery { emailDao.getEmailById("gw3") } returns anchor

        assertTrue(repo.sendReply("gw3", "again").isSuccess)
        assertEquals("bob@example.com", tag("to"))
        assertEquals("<mine@desent.xyz>", tag("in_reply_to"))
    }

    @Test
    fun reply_failsWithoutOwnedAddress() = runBlocking {
        coEvery { accountDao.getAccount(userNpub) } returns null
        coEvery { registrationRepo.getAccount() } returns Result.success(null)

        val anchor = EmailEntity(
            id = "gw4", recipientNpub = userNpub,
            senderEmail = "bob@example.com", senderDomain = null,
            subject = "S", content = "x",
            dkimStatus = "NONE",
            threadToken = null, emailType = "OTHER", bridge = "email",
            messageId = "m@x", threadRoot = "k",
            createdAt = 1_000L
        )
        coEvery { emailDao.getEmailById("gw4") } returns anchor

        val result = repo.sendReply("gw4", "body")
        assertTrue(result.isFailure)
        coVerify(atLeast = 0) { giftWrap.wrapGift(any(), any(), any(), any(), any()) }
    }

    @Test
    fun reply_rejectsOversizedBody() = runBlocking {
        val anchor = EmailEntity(
            id = "gw5", recipientNpub = userNpub,
            senderEmail = "bob@example.com", senderDomain = null,
            subject = "S", content = "x",
            dkimStatus = "NONE",
            threadToken = null, emailType = "OTHER", bridge = "email",
            messageId = "m@x", threadRoot = "k",
            createdAt = 1_000L
        )
        coEvery { emailDao.getEmailById("gw5") } returns anchor

        val big = "a".repeat(10_241)
        assertTrue(repo.sendReply("gw5", big).isFailure)
    }

    // ---------------------------------------------------------------
    // Cold sends
    // ---------------------------------------------------------------

    @Test
    fun coldSend_sameConstructorWithoutInReplyTo_ownThreadRoot() = runBlocking {
        val result = repo.sendColdEmail(
            listOf(xyz.desent.domain.model.EmailRecipient("carol@foo.io")), "First contact", "hello",
            xyz.desent.domain.model.EmailBodyFormat.HTML
        )
        assertTrue(result.isSuccess)

        assertEquals(NostrKinds.EMAIL_MESSAGE, kindSlot.captured)
        assertEquals("alice@desent.xyz", tag("from"))
        assertEquals("carol@foo.io", tag("to"))
        assertEquals("First contact", tag("subject"))
        assertEquals("outbound", tag("direction"))
        assertEquals("html", tag("format"))
        assertTrue(tag("message_id")!!.endsWith("@desent.xyz"))
        assertEquals(null, tag("in_reply_to"))
        assertEquals(null, tag("references"))

        // A cold send keys its own thread by its fresh Message-ID.
        assertEquals(tag("message_id"), rowSlot.captured.threadRoot)
        assertEquals("HTML", rowSlot.captured.bodyFormat)
        assertEquals(tag("message_id"), outboxSlot.captured.messageId)
        assertEquals("carol@foo.io", outboxSlot.captured.toEmail)
    }

    @Test
    fun coldSend_defaultsToPlainFormat() = runBlocking {
        assertTrue(repo.sendColdEmail(listOf(xyz.desent.domain.model.EmailRecipient("carol@foo.io")), "S", "b").isSuccess)
        assertEquals("plain", tag("format"))
        assertEquals("PLAIN", rowSlot.captured.bodyFormat)
    }

    @Test
    fun coldSend_requiresRecipientAndSubject() = runBlocking {
        assertTrue(repo.sendColdEmail(emptyList(), "S", "b").isFailure)
        assertTrue(repo.sendColdEmail(listOf(xyz.desent.domain.model.EmailRecipient("a@b.c")), " ", "b").isFailure)
    }

    // ---------------------------------------------------------------
    // Recipient lists (END-01 §3.4, END-03 §6; ANDROID_EMAIL_MIGRATION §7)
    // ---------------------------------------------------------------

    private val bobTo = xyz.desent.domain.model.EmailRecipient("bob@example.com", "Bob Example")
    private val carolTo = xyz.desent.domain.model.EmailRecipient("carol@foo.io")
    private val daveCc = xyz.desent.domain.model.EmailRecipient("dave@baz.net", "Dave")
    private val secretBcc = xyz.desent.domain.model.EmailRecipient("secret@hidden.io")

    @Test
    fun coldSend_multiRecipient_emitsOneTagPerMailbox_andStoresLists() = runBlocking {
        val result = repo.sendColdEmail(
            to = listOf(bobTo, carolTo),
            cc = listOf(daveCc),
            bcc = listOf(secretBcc),
            subject = "Plans",
            body = "body",
            format = xyz.desent.domain.model.EmailBodyFormat.PLAIN
        )
        assertTrue(result.isSuccess)

        // One tag per mailbox in END-01 §4 order (to*→cc*→bcc*), display
        // name in the optional third slot, Bcc bare.
        assertEquals(
            listOf(
                listOf("to", "bob@example.com", "Bob Example"),
                listOf("to", "carol@foo.io")
            ),
            tags("to")
        )
        assertEquals(listOf(listOf("cc", "dave@baz.net", "Dave")), tags("cc"))
        assertEquals(listOf(listOf("bcc", "secret@hidden.io")), tags("bcc"))

        // Rows keep the full lists (receipt matching / retry / display), with
        // the legacy single field holding the FIRST To addr-spec.
        assertEquals("bob@example.com", rowSlot.captured.toEmail)
        assertEquals("bob@example.com", outboxSlot.captured.toEmail)
        val mapper = xyz.desent.data.mapper.EmailMapper()
        assertEquals(listOf(bobTo, carolTo), mapper.decodeRecipients(rowSlot.captured.toRecipientsJson))
        assertEquals(listOf(daveCc), mapper.decodeRecipients(rowSlot.captured.ccRecipientsJson))
        assertEquals(listOf(secretBcc), mapper.decodeRecipients(rowSlot.captured.bccRecipientsJson))
        assertEquals(listOf(bobTo, carolTo), mapper.decodeRecipients(outboxSlot.captured.toRecipientsJson))
    }

    @Test
    fun coldSend_rejectsMoreThan20EnvelopeRecipients() = runBlocking {
        val crowd = (1..21).map { xyz.desent.domain.model.EmailRecipient("r$it@x.io") }
        val result = repo.sendColdEmail(to = crowd, subject = "S", body = "b")
        assertTrue(result.isFailure)
        coVerify(exactly = 0) { giftWrap.wrapGift(any(), any(), any(), any(), any()) }
    }

    @Test
    fun coldSend_rejectsInvalidAddrSpec_noPartialSend() = runBlocking {
        val result = repo.sendColdEmail(
            to = listOf(xyz.desent.domain.model.EmailRecipient("ok@x.io")),
            cc = listOf(xyz.desent.domain.model.EmailRecipient("not an address")),
            subject = "S",
            body = "b"
        )
        assertTrue(result.isFailure)
        coVerify(exactly = 0) { giftWrap.wrapGift(any(), any(), any(), any(), any()) }
    }

    @Test
    fun coldSend_dedupesCaseInsensitiveCopies_acrossToCcBcc() = runBlocking {
        val result = repo.sendColdEmail(
            to = listOf(bobTo, xyz.desent.domain.model.EmailRecipient("BOB@example.com")),
            cc = listOf(xyz.desent.domain.model.EmailRecipient("bob@Example.com")),
            subject = "S",
            body = "b"
        )
        assertTrue(result.isSuccess)
        assertEquals(1, tags("to").size)
        assertEquals(0, tags("cc").size)
    }

    @Test
    fun coldSend_pgpWithMultipleRecipients_refused() = runBlocking {
        val repoWithPgp = EmailRepositoryImpl(
            emailDao, outboxDao, ledgerDao, accountDao,
            xyz.desent.data.mapper.EmailMapper(), relayRepo, secureKeyManager,
            preferencesManager, registrationRepo, mockk(relaxed = true), giftWrap,
            spamFilterRepository = null,
            pgpKeyRepository = pgpRepoReturning("-----BEGIN PGP MESSAGE-----")
        )
        val result = repoWithPgp.sendColdEmail(
            to = listOf(bobTo, carolTo),
            subject = "S",
            body = "b",
            pgp = true
        )
        assertTrue(result.isFailure)
        coVerify(exactly = 0) { giftWrap.wrapGift(any(), any(), any(), any(), any()) }
    }

    // ---------------------------------------------------------------
    // Reply-all prefill (RFC 5322 §3.6.2; ANDROID_EMAIL_MIGRATION §7)
    // ---------------------------------------------------------------

    private fun multiRecipientAnchor() = EmailEntity(
        id = "gw-all",
        recipientNpub = userNpub,
        senderEmail = "bob@example.com",
        senderDomain = "example.com",
        replyTo = "human@example.com",
        subject = "Group plans",
        content = "x",
        dkimStatus = "NONE",
        threadToken = null, emailType = "OTHER", bridge = "email",
        messageId = "m-all@x", threadRoot = "root-all",
        toRecipientsJson = """[{"address":"alice@desent.xyz","displayName":"Alice"},{"address":"carol@foo.io"}]""",
        ccRecipientsJson = """[{"address":"dave@baz.net","displayName":"Dave"}]""",
        deliveredTo = "alice@desent.xyz",
        createdAt = 1_000L
    )

    @Test
    fun computeReplyRecipients_plainReply_routesToReplyToOrSender() = runBlocking {
        coEvery { emailDao.getEmailById("gw-all") } returns multiRecipientAnchor()
        val prefill = repo.computeReplyRecipients("gw-all", replyAll = false)!!
        assertEquals(
            listOf(xyz.desent.domain.model.EmailRecipient("human@example.com")),
            prefill.to
        )
        assertEquals(emptyList<xyz.desent.domain.model.EmailRecipient>(), prefill.cc)
    }

    @Test
    fun computeReplyRecipients_replyAll_ownsFilteredAndDeduped() = runBlocking {
        coEvery { emailDao.getEmailById("gw-all") } returns multiRecipientAnchor()
        // Owned: the account primary (alice@desent.xyz), the delivered_to
        // address (same here) and one active alias.
        val aliasRepo = mockk<xyz.desent.domain.repository.AliasRepository>()
        coEvery { aliasRepo.listAliases() } returns Result.success(
            listOf(
                xyz.desent.domain.model.Alias(
                    id = 1, email = "team@desent.xyz", localPart = "team",
                    label = null, isActive = true, createdAt = "2026-01-01"
                )
            ) to xyz.desent.domain.model.AliasTierInfo(
                tier = "free", cap = 5, used = 1, emailDomain = "desent.xyz"
            )
        )
        val repoWithAliases = EmailRepositoryImpl(
            emailDao, outboxDao, ledgerDao, accountDao,
            xyz.desent.data.mapper.EmailMapper(), relayRepo, secureKeyManager,
            preferencesManager, registrationRepo, mockk(relaxed = true), giftWrap,
            aliasRepository = aliasRepo
        )

        val prefill = repoWithAliases.computeReplyRecipients("gw-all", replyAll = true)!!

        // to = reply_to ∥ from (both external).
        assertEquals(
            listOf(
                xyz.desent.domain.model.EmailRecipient("human@example.com"),
                xyz.desent.domain.model.EmailRecipient("bob@example.com")
            ),
            prefill.to
        )
        // cc = original to ∪ cc MINUS own addresses (alice@desent.xyz dropped);
        // display names preserved from the anchor's parsed lists.
        assertEquals(
            listOf(
                xyz.desent.domain.model.EmailRecipient("carol@foo.io"),
                xyz.desent.domain.model.EmailRecipient("dave@baz.net", "Dave")
            ),
            prefill.cc
        )
    }

    @Test
    fun sendReply_withExplicitLists_usesThemVerbatim() = runBlocking {
        val anchor = multiRecipientAnchor()
        coEvery { emailDao.getEmailById("gw-all") } returns anchor

        val result = repo.sendReply(
            emailId = "gw-all",
            replyText = "done",
            format = xyz.desent.domain.model.EmailBodyFormat.PLAIN,
            to = listOf(xyz.desent.domain.model.EmailRecipient("human@example.com")),
            cc = listOf(xyz.desent.domain.model.EmailRecipient("carol@foo.io"))
        )
        assertTrue(result.isSuccess)
        assertEquals(
            listOf(listOf("to", "human@example.com")),
            tags("to")
        )
        assertEquals(
            listOf(listOf("cc", "carol@foo.io")),
            tags("cc")
        )
    }

    // ---------------------------------------------------------------
    // PGP-encrypted sends (ANDROID_PGP.md §4)
    // ---------------------------------------------------------------

    private fun pgpRepoReturning(armor: String?): xyz.desent.domain.repository.PgpKeyRepository =
        mockk<xyz.desent.domain.repository.PgpKeyRepository>().apply {
            coEvery { encryptForRecipient(any(), any()) } answers {
                armor?.let { Result.success(it) }
                    ?: Result.failure(Exception("No PGP key published for this recipient"))
            }
        }

    @Test
    fun coldSend_pgpEncryptsBeforeWrap_carriesPgpTagAndKeepsPlaintextDraft() = runBlocking {
        val armor = OpenPgpCrypto.generate("Alice <alice@desent.xyz>").let { key ->
            OpenPgpCrypto.encrypt("secret body", key.publicArmored).getOrThrow()
        }
        val pgpRepo = pgpRepoReturning(armor)
        val repoWithPgp = EmailRepositoryImpl(
            emailDao, outboxDao, ledgerDao, accountDao,
            xyz.desent.data.mapper.EmailMapper(), relayRepo, secureKeyManager,
            preferencesManager, registrationRepo, mockk(relaxed = true), giftWrap,
            spamFilterRepository = null,
            pgpKeyRepository = pgpRepo
        )

        val result = repoWithPgp.sendColdEmail(
            listOf(xyz.desent.domain.model.EmailRecipient("carol@foo.io")), "Secret", "secret body",
            xyz.desent.domain.model.EmailBodyFormat.PLAIN, pgp = true
        )
        assertTrue(result.isSuccess)

        // The WRAPPED content is the armor, not the plaintext.
        coVerify { giftWrap.wrapGift(armor, RelayConfig.RELAY_PUBKEY_NPUB, userNpub, NostrKinds.EMAIL_MESSAGE, any()) }
        // PGP tag vocabulary: no format tag, pgp:encrypted present.
        assertEquals(null, tag("format"))
        assertEquals("encrypted", tag("pgp"))
        // The local row + outbox keep the plaintext draft (retry source).
        assertEquals("secret body", rowSlot.captured.content)
        assertTrue(rowSlot.captured.isPgpEncrypted)
        assertEquals("secret body", outboxSlot.captured.body)
        assertTrue(outboxSlot.captured.isPgpEncrypted)
    }

    @Test
    fun coldSend_pgpWithoutDiscoverableKey_failsWithTypedMessage() = runBlocking {
        val repoWithPgp = EmailRepositoryImpl(
            emailDao, outboxDao, ledgerDao, accountDao,
            xyz.desent.data.mapper.EmailMapper(), relayRepo, secureKeyManager,
            preferencesManager, registrationRepo, mockk(relaxed = true), giftWrap,
            spamFilterRepository = null,
            pgpKeyRepository = pgpRepoReturning(null)
        )

        val result = repoWithPgp.sendColdEmail(
            listOf(xyz.desent.domain.model.EmailRecipient("carol@foo.io")), "S", "b",
            xyz.desent.domain.model.EmailBodyFormat.PLAIN, pgp = true
        )

        assertTrue(result.isFailure)
        assertEquals(
            "No PGP key published for this recipient",
            result.exceptionOrNull()?.message
        )
        coVerify(exactly = 0) { giftWrap.wrapGift(any(), any(), any(), any(), any()) }
    }

    @Test
    fun coldSend_pgpWithGarbageArmor_refusesToPublish() = runBlocking {
        val repoWithPgp = EmailRepositoryImpl(
            emailDao, outboxDao, ledgerDao, accountDao,
            xyz.desent.data.mapper.EmailMapper(), relayRepo, secureKeyManager,
            preferencesManager, registrationRepo, mockk(relaxed = true), giftWrap,
            spamFilterRepository = null,
            pgpKeyRepository = pgpRepoReturning("definitely not armor")
        )

        val result = repoWithPgp.sendColdEmail(
            listOf(xyz.desent.domain.model.EmailRecipient("carol@foo.io")), "S", "b",
            xyz.desent.domain.model.EmailBodyFormat.PLAIN, pgp = true
        )

        // The armor-prefix contract is enforced client-side too.
        assertTrue(result.isFailure)
        coVerify(exactly = 0) { giftWrap.wrapGift(any(), any(), any(), any(), any()) }
    }

    @Test
    fun retry_pgpSend_reEncryptsFromPlaintextDraft() = runBlocking {
        val armor = OpenPgpCrypto.generate("Alice <alice@desent.xyz>").let { key ->
            OpenPgpCrypto.encrypt("retry secret", key.publicArmored).getOrThrow()
        }
        val failed = EmailOutboxEntity(
            messageId = "old-pgp@desent.xyz", recipientNpub = userNpub, threadKey = "thread-pgp",
            fromAlias = "alice@desent.xyz", toEmail = "bob@example.com",
            subject = "Re: Secret", body = "retry me",
            sentAt = System.currentTimeMillis() - 60_000,
            status = EmailOutboxEntity.STATUS_FAILED,
            errorMessage = "❌ Send failed: boom",
            isPgpEncrypted = true
        )
        coEvery { outboxDao.getByMessageId("old-pgp@desent.xyz") } returns failed
        val repoWithPgp = EmailRepositoryImpl(
            emailDao, outboxDao, ledgerDao, accountDao,
            xyz.desent.data.mapper.EmailMapper(), relayRepo, secureKeyManager,
            preferencesManager, registrationRepo, mockk(relaxed = true), giftWrap,
            spamFilterRepository = null,
            pgpKeyRepository = pgpRepoReturning(armor)
        )

        val result = repoWithPgp.retrySend("old-pgp@desent.xyz")
        assertTrue(result.isSuccess)

        // Fresh armor (re-encrypted), PGP tags, plaintext preserved in the entry.
        coVerify { giftWrap.wrapGift(armor, RelayConfig.RELAY_PUBKEY_NPUB, userNpub, NostrKinds.EMAIL_MESSAGE, any()) }
        assertEquals("encrypted", tag("pgp"))
        assertEquals(null, tag("format"))
        assertEquals("retry me", outboxSlot.captured.body)
        assertTrue(outboxSlot.captured.isPgpEncrypted)
    }

    // ---------------------------------------------------------------
    // Outbox ledger: reconcile + retry
    // ---------------------------------------------------------------

    @Test
    fun reconcile_flipsStalePendingToTimedOut() = runBlocking {
        val stale = EmailOutboxEntity(
            messageId = "stale@desent.xyz", recipientNpub = userNpub, threadKey = "t",
            fromAlias = "alice@desent.xyz", toEmail = "bob@example.com",
            subject = "S", body = "b", sentAt = System.currentTimeMillis() - 10 * 60 * 1000L
        )
        coEvery { outboxDao.findStalePending(userNpub, any()) } returns listOf(stale)

        repo.reconcileOutbox(userNpub)

        coVerify {
            outboxDao.markTimedOut(
                messageId = "stale@desent.xyz",
                reason = any(),
                atMs = any()
            )
        }
    }

    @Test
    fun retry_republishesWithFreshMessageIdAndResetsEntry() = runBlocking {
        val failed = EmailOutboxEntity(
            messageId = "old@desent.xyz", recipientNpub = userNpub, threadKey = "thread-9",
            fromAlias = "alice@desent.xyz", toEmail = "bob@example.com",
            subject = "Re: Hello", body = "retry me",
            sentAt = System.currentTimeMillis() - 60_000,
            status = EmailOutboxEntity.STATUS_FAILED,
            errorMessage = "❌ Send failed: boom",
            inReplyTo = "parent@example.com",
            referencesHeader = "<root@x> <parent@example.com>",
            bodyFormat = "HTML"
        )
        coEvery { outboxDao.getByMessageId("old@desent.xyz") } returns failed

        val result = repo.retrySend("old@desent.xyz")
        assertTrue(result.isSuccess)

        // Republished from the stored entry, verbatim.
        assertEquals("alice@desent.xyz", tag("from"))
        assertEquals("bob@example.com", tag("to"))
        assertEquals("Re: Hello", tag("subject"))
        assertEquals("<parent@example.com>", tag("in_reply_to"))
        assertEquals("<root@x> <parent@example.com>", tag("references"))
        assertEquals("html", tag("format"))
        val freshId = tag("message_id")!!
        assertTrue(freshId.endsWith("@desent.xyz"))

        // One row per logical send: old id replaced by the fresh PENDING entry.
        coVerify { outboxDao.deleteByMessageId("old@desent.xyz") }
        assertEquals(freshId, outboxSlot.captured.messageId)
        assertEquals(EmailOutboxEntity.STATUS_PENDING, outboxSlot.captured.status)
        assertEquals("thread-9", outboxSlot.captured.threadKey)
        assertEquals(null, outboxSlot.captured.errorMessage)

        // The sent bubble is re-pointed so its footer tracks the new entry.
        coVerify { emailDao.updateOutboundMessageId("old@desent.xyz", freshId) }
    }

    @Test
    fun retry_rejectsInFlightEntry() = runBlocking {
        val pending = EmailOutboxEntity(
            messageId = "p@desent.xyz", recipientNpub = userNpub, threadKey = "t",
            fromAlias = "alice@desent.xyz", toEmail = "bob@example.com",
            subject = "S", body = "b", sentAt = System.currentTimeMillis()
        )
        coEvery { outboxDao.getByMessageId("p@desent.xyz") } returns pending

        assertTrue(repo.retrySend("p@desent.xyz").isFailure)
        coVerify(exactly = 0) { outboxDao.deleteByMessageId(any()) }
    }

    @Test
    fun retry_rejectsSyntheticEntry() = runBlocking {
        val synthetic = EmailOutboxEntity(
            messageId = "s@desent.xyz", recipientNpub = userNpub, threadKey = "t",
            fromAlias = "alice@desent.xyz", toEmail = "bob@example.com",
            subject = "S", body = "b", sentAt = System.currentTimeMillis(),
            status = EmailOutboxEntity.STATUS_FAILED,
            isSynthetic = true
        )
        coEvery { outboxDao.getByMessageId("s@desent.xyz") } returns synthetic

        assertTrue(repo.retrySend("s@desent.xyz").isFailure)
    }

    // ---------------------------------------------------------------
    // Forwarding / migration (NIP-EMAIL)
    // ---------------------------------------------------------------

    private fun inboundEntity(
        id: String = "fw-1",
        direction: String? = EmailDirection.INBOUND.name
    ) = EmailEntity(
        id = id,
        recipientNpub = userNpub,
        senderEmail = "bob@example.com",
        senderDomain = "example.com",
        senderName = "Bob",
        replyTo = null,
        subject = "Quarterly report",
        content = "the body",
        bodyFormat = "PLAIN",
        dkimStatus = "PASS",
        spfStatus = "PASS",
        dmarcStatus = "PASS",
        emailType = "OTHER",
        bridge = "email",
        messageId = "orig-1@example.com",
        inReplyTo = "root-0@example.com",
        referencesHeader = "root-0@example.com",
        threadToken = null,
        threadRoot = "root-0@example.com",
        toEmail = null,
        direction = direction,
        alias = "alice@desent.xyz",
        attachmentsJson = """[{"sha256":"abc","mimeType":"application/pdf","size":123,"keyHex":"deadbeef","filename":"report.pdf"}]""",
        senderDate = 1_700_000_000_000L,
        createdAt = 1_700_000_100_000L
    )

    @Test
    fun forward_rebuildsRumorVerbatimWithProvenance() = runBlocking {
        coEvery { emailDao.getEmailById("fw-1") } returns inboundEntity()
        coEvery { ledgerDao.get("fw-1", targetNpub) } returns null

        val progress = mutableListOf<Pair<Int, Int>>()
        val result = repo.forwardEmails(listOf("fw-1"), targetNpub) { p, t -> progress.add(p to t) }

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(1, summary.sent)
        assertEquals(0, summary.alreadyDelivered)
        assertEquals(0, summary.failed)

        // Kind-1010 rumor, gift-wrapped to the TARGET key (not the relay).
        coVerify {
            giftWrap.wrapGift("the body", targetNpub, userNpub, NostrKinds.EMAIL_MESSAGE, any())
        }
        assertEquals(NostrKinds.EMAIL_MESSAGE, kindSlot.captured)

        // Verbatim header tags + inbound direction + provenance.
        assertEquals("bob@example.com", tag("from"))
        assertEquals("Bob", tag("from_name"))
        assertEquals("example.com", tag("from_domain"))
        assertEquals("Quarterly report", tag("subject"))
        assertEquals("inbound", tag("direction"))
        assertEquals("orig-1@example.com", tag("message_id"))
        assertEquals("plain", tag("format"))
        assertEquals("<root-0@example.com>", tag("in_reply_to"))
        assertEquals("pass", tag("dkim"))
        assertEquals("alice@desent.xyz", tag("alias"))
        assertEquals((1_700_000_000_000L / 1000L).toString(), tag("date"))
        // Attachment descriptors ride along, AES key included.
        assertEquals(
            listOf("attachment", "abc", "application/pdf", "123", "deadbeef", "report.pdf"),
            extraTagsSlot.captured.first { it.firstOrNull() == "attachment" }
        )
        // Provenance: the forwarder's hex pubkey (the seal signer).
        assertEquals(identity.publicKey.toHexString(), tag("forwarded_by"))

        // Ledger: PENDING → SENT with the wrap's event id.
        coVerify(atLeast = 1) { ledgerDao.upsert(any()) }
        assertEquals(EmailForwardLedgerEntity.STATUS_SENT, ledgerSlot.captured.status)

        // Progress fired once per message.
        assertEquals(listOf(1 to 1), progress)
    }

    @Test
    fun forward_isIdempotent_alreadySentLedgerEntrySkips() = runBlocking {
        coEvery { emailDao.getEmailById("fw-1") } returns inboundEntity()
        coEvery { ledgerDao.get("fw-1", targetNpub) } returns EmailForwardLedgerEntity(
            emailId = "fw-1", targetNpub = targetNpub,
            status = EmailForwardLedgerEntity.STATUS_SENT,
            giftWrapEventId = "oldevent", createdAt = 1L, resolvedAt = 2L
        )

        val result = repo.forwardEmails(listOf("fw-1"), targetNpub)

        assertEquals(0, result.getOrThrow().sent)
        assertEquals(1, result.getOrThrow().alreadyDelivered)
        coVerify(exactly = 0) { giftWrap.wrapGift(any(), targetNpub, any(), any(), any()) }
    }

    @Test
    fun forward_skipsIneligibleRows_neverPublishes() = runBlocking {
        coEvery { emailDao.getEmailById("out") } returns
            inboundEntity(id = "out", direction = EmailDirection.OUTBOUND.name)
        coEvery { emailDao.getEmailById("sys") } returns
            inboundEntity(id = "sys").copy(emailType = "SYSTEM")
        coEvery { ledgerDao.get(any(), targetNpub) } returns null

        val result = repo.forwardEmails(listOf("out", "sys"), targetNpub)

        val summary = result.getOrThrow()
        assertEquals(0, summary.sent)
        assertEquals(2, summary.total)
        coVerify(exactly = 0) { giftWrap.wrapGift(any(), targetNpub, any(), any(), any()) }
    }

    @Test
    fun forward_recordsFailureInLedger_andContinues() = runBlocking {
        coEvery { emailDao.getEmailById("fw-1") } returns inboundEntity()
        coEvery { emailDao.getEmailById("fw-2") } returns inboundEntity(id = "fw-2")
        coEvery { ledgerDao.get(any(), targetNpub) } returns null
        coEvery {
            giftWrap.wrapGift(any(), targetNpub, userNpub, any(), any())
        } returns Result.failure(Exception("relay said no")) andThen Result.success(wrapEvent)

        val result = repo.forwardEmails(listOf("fw-1", "fw-2"), targetNpub)

        val summary = result.getOrThrow()
        assertEquals(1, summary.sent)
        assertEquals(1, summary.failed)
        // The FAILED ledger row keeps the reason for a retry run.
        coVerify {
            ledgerDao.upsert(match {
                it.status == EmailForwardLedgerEntity.STATUS_FAILED &&
                    it.errorMessage == "relay said no"
            })
        }
    }

    @Test
    fun forward_rejectsSelfTarget_andInvalidNpub_andEmptySelection() = runBlocking {
        assertTrue(repo.forwardEmails(listOf("fw-1"), userNpub).isFailure)
        assertTrue(repo.forwardEmails(listOf("fw-1"), "npub1notarealkey").isFailure)
        assertTrue(repo.forwardEmails(emptyList(), targetNpub).isFailure)
        coVerify(exactly = 0) { giftWrap.wrapGift(any(), any(), any(), any(), any()) }
    }

    @Test
    fun getForwardEligibleEmailIds_wholeMailboxExcludesSpam_threadScopedPassesThrough() = runBlocking {
        val clean = inboundEntity(id = "a")
        val spammy = inboundEntity(id = "b").copy(isSpam = true)
        coEvery { emailDao.getForwardEligibleEmails(userNpub) } returns listOf(clean, spammy)
        coEvery { emailDao.getForwardEligibleEmailsByThreads(userNpub, listOf("t1")) } returns listOf(clean)

        assertEquals(listOf("a"), repo.getForwardEligibleEmailIds(userNpub))
        assertEquals(listOf("a"), repo.getForwardEligibleEmailIds(userNpub, threadKeys = listOf("t1")))
        assertEquals(emptyList<String>(), repo.getForwardEligibleEmailIds(userNpub, threadKeys = emptyList()))
    }

    // ---------------------------------------------------------------
    // Deletion + §6.4 mirror propagation (ANDROID_DM_FANOUT.md)
    // ---------------------------------------------------------------

    @Test
    fun deletion_withFanoutOn_publishesUserSignedKind5AfterHttpSuccess() = runBlocking {
        val email = inboundEntity(id = "del-1")
        coEvery { emailDao.getEmailById(email.id) } returns email
        coEvery { messageClient.deleteMessage(email.id) } returns Result.success(Unit)
        val store = mockk<xyz.desent.data.local.preferences.SecurityConfigStore>()
        coEvery { store.get(userNpub) } returns xyz.desent.domain.model.SecurityConfig(dmFanout = true)
        coEvery { secureKeyManager.getIdentityForAccount(userNpub) } returns Result.success(identity)
        coEvery { preferencesManager.getActiveNpub() } returns userNpub

        val repoWithFanout = EmailRepositoryImpl(
            emailDao = emailDao,
            emailOutboxDao = outboxDao,
            emailForwardLedgerDao = ledgerDao,
            accountDao = accountDao,
            emailMapper = xyz.desent.data.mapper.EmailMapper(),
            relayRepository = relayRepo,
            secureKeyManager = secureKeyManager,
            preferencesManager = preferencesManager,
            registrationRepository = registrationRepo,
            messageClient = messageClient,
            giftWrapEncryptionService = giftWrap,
            securityConfigStore = store
        )

        val result = repoWithFanout.requestDeletion(email.id)

        assertTrue(result.isSuccess)
        coVerify { emailDao.deleteEmail(email.id) }
        // Fire-and-forget publish on the repo's own IO scope — wait for it.
        val eventSlot = slot<GenericEvent>()
        coVerify(timeout = 5_000) {
            relayRepo.publishEventToRelay(capture(eventSlot), RelayConfig.EMAIL_RELAY_URL)
        }
        val published = eventSlot.captured
        assertEquals(NostrKinds.DELETION, published.kind)
        assertEquals(identity.publicKey.toHexString(), published.pubKey.toHexString())
        val eTag = published.tags
            .firstOrNull { (it as? nostr.event.tag.GenericTag)?.getCode() == "e" }
            as? nostr.event.tag.GenericTag
        assertEquals(email.id, eTag?.getParams()?.firstOrNull())
        coVerify { emailDao.markDeletionRequested(email.id, published.id) }
    }

    @Test
    fun deletion_withFanoutOff_skipsTheKind5Publish() = runBlocking {
        val email = inboundEntity(id = "del-2")
        coEvery { emailDao.getEmailById(email.id) } returns email
        coEvery { messageClient.deleteMessage(email.id) } returns Result.success(Unit)
        val store = mockk<xyz.desent.data.local.preferences.SecurityConfigStore>()
        coEvery { store.get(userNpub) } returns xyz.desent.domain.model.SecurityConfig(dmFanout = false)
        coEvery { preferencesManager.getActiveNpub() } returns userNpub

        val repoNoFanout = EmailRepositoryImpl(
            emailDao = emailDao,
            emailOutboxDao = outboxDao,
            emailForwardLedgerDao = ledgerDao,
            accountDao = accountDao,
            emailMapper = xyz.desent.data.mapper.EmailMapper(),
            relayRepository = relayRepo,
            secureKeyManager = secureKeyManager,
            preferencesManager = preferencesManager,
            registrationRepository = registrationRepo,
            messageClient = messageClient,
            giftWrapEncryptionService = giftWrap,
            securityConfigStore = store
        )

        val result = repoNoFanout.requestDeletion(email.id)

        assertTrue(result.isSuccess)
        // Give the (absent) fire-and-forget a beat, then assert nothing fired.
        kotlinx.coroutines.delay(100)
        coVerify(exactly = 0) { relayRepo.publishEventToRelay(any(), any()) }
    }

    @Test
    fun deletion_httpFailure_keepsLocalRowAndNeverPublishes() = runBlocking {
        val email = inboundEntity(id = "del-3")
        coEvery { emailDao.getEmailById(email.id) } returns email
        coEvery { messageClient.deleteMessage(email.id) } returns Result.failure(Exception("boom"))
        val store = mockk<xyz.desent.data.local.preferences.SecurityConfigStore>()
        coEvery { store.get(userNpub) } returns xyz.desent.domain.model.SecurityConfig(dmFanout = true)
        coEvery { secureKeyManager.getIdentityForAccount(userNpub) } returns Result.success(identity)
        coEvery { preferencesManager.getActiveNpub() } returns userNpub

        val repoHttpFail = EmailRepositoryImpl(
            emailDao = emailDao,
            emailOutboxDao = outboxDao,
            emailForwardLedgerDao = ledgerDao,
            accountDao = accountDao,
            emailMapper = xyz.desent.data.mapper.EmailMapper(),
            relayRepository = relayRepo,
            secureKeyManager = secureKeyManager,
            preferencesManager = preferencesManager,
            registrationRepository = registrationRepo,
            messageClient = messageClient,
            giftWrapEncryptionService = giftWrap,
            securityConfigStore = store
        )

        val result = repoHttpFail.requestDeletion(email.id)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { emailDao.deleteEmail(email.id) }
        kotlinx.coroutines.delay(100)
        coVerify(exactly = 0) { relayRepo.publishEventToRelay(any(), any()) }
    }
}
