package xyz.desent.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.GiftWrapEncryptionService
import xyz.desent.data.EmailBridgeTags
import xyz.desent.data.OutboundQuoteBuilder
import xyz.desent.data.RelayConfig
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.local.database.dao.EmailOutboxDao
import xyz.desent.data.local.database.dao.EmailForwardLedgerDao
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.data.local.database.entity.EmailEntity
import xyz.desent.data.local.database.entity.EmailOutboxEntity
import xyz.desent.data.local.database.entity.EmailForwardLedgerEntity
import xyz.desent.data.mapper.EmailMapper
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.message.MessageClient
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.domain.model.EmailDirection
import xyz.desent.domain.model.EmailForwardSummary
import xyz.desent.domain.model.EmailType
import xyz.desent.domain.model.DkimStatus
import xyz.desent.domain.model.SpfStatus
import xyz.desent.domain.repository.EmailRepository
import xyz.desent.domain.repository.RegistrationRepository
import xyz.desent.domain.repository.RelayRepository

class EmailRepositoryImpl(
    private val emailDao: EmailDao,
    private val emailOutboxDao: EmailOutboxDao,
    private val emailForwardLedgerDao: EmailForwardLedgerDao,
    private val accountDao: AccountDao,
    private val emailMapper: EmailMapper,
    private val relayRepository: RelayRepository,
    private val secureKeyManager: xyz.desent.crypto.SecureKeyManager,
    private val preferencesManager: PreferencesManager,
    private val registrationRepository: RegistrationRepository,
    private val messageClient: MessageClient,
    private val giftWrapEncryptionService: GiftWrapEncryptionService,
    private val spamFilterRepository: xyz.desent.domain.repository.SpamFilterRepository? = null,
    private val pgpKeyRepository: xyz.desent.domain.repository.PgpKeyRepository? = null,
    private val pgpAttachmentCache: xyz.desent.data.pgp.PgpAttachmentCache? = null,
    /** Active-alias source for the reply-all self-address filter (optional: tests). */
    private val aliasRepository: xyz.desent.domain.repository.AliasRepository? = null,
    /** 30079 `dm_fanout` opt-in source for §6.4 delete propagation (optional: tests). */
    private val securityConfigStore: xyz.desent.data.local.preferences.SecurityConfigStore? = null
) : EmailRepository {

    /** Fire-and-forget scope for the §6.4 kind-5 propagation publishes. */
    private val mirrorDeletionScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /**
     * Mail-state overlay hook (mail folders + synced read state): set
     * post-construction from AppContainer to avoid a DI cycle. When present,
     * read/unread changes are mirrored into the synced `desent:mail-state`
     * overlay; absent (tests, wear) the local column stays device-local.
     */
    override var mailFolderRepository: xyz.desent.domain.repository.MailFolderRepository? = null

    companion object {
        private const val TAG = "EmailRepository"
        private const val MAX_BODY_BYTES = 10_240
        private const val EMAIL_DOMAIN = "desent.xyz"

        /** Addr-spec shape the relay enforces on every envelope recipient (END-03 §3.2). */
        private val RECIPIENT_ADDRESS_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        /**
         * Cap on the armored PGP MESSAGE published as rumor content. The
         * plaintext body is already capped by [MAX_BODY_BYTES]; armor adds
         * base64 expansion (~1.37x) + packet headers, so 64 KiB is a generous
         * ceiling that stays under every relay-side body cap.
         */
        private const val PGP_MAX_ARMOR_BYTES = 65_536

        /**
         * Pause between forward publishes: the relay accepts client-published
         * 1059s to any p but rate-limits them
         * (refs/FromServer/ANDROID_NIP46_BUNKER.md §2), so a bulk mailbox
         * migration must pace itself.
         */
        const val FORWARD_PACE_MS = 500L

        /**
         * How long a PENDING outbox entry may stay silent before the reconcile
         * sweep flips it to TIMED_OUT. Receipts land within seconds; the window
         * only absorbs slow relays + reconnect backlog, so two minutes is
         * deliberately far above the docs' 30 s UI hint.
         */
        const val OUTBOX_TIMEOUT_MS = 2 * 60 * 1000L
    }

    private fun validateBody(body: String): Result<Unit> {
        val bytes = body.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_BODY_BYTES) {
            return Result.failure(
                Exception("Body too large ($bytes bytes; max $MAX_BODY_BYTES)")
            )
        }
        return Result.success(Unit)
    }

    /**
     * Normalize + validate the full RFC 5322 recipient lists for one send
     * (END-01 §3.4, END-03 §6): every addr-spec must match the same shape the
     * relay enforces (it refuses the whole send — no partial sends),
     * duplicates collapse case-insensitively across to+cc+bcc keeping the
     * first occurrence, and the total is capped at
     * [EmailBridgeTags.MAX_ENVELOPE_RECIPIENTS]. PGP sends are
     * single-recipient: the body is encrypted to exactly one WKD key.
     */
    private fun validateRecipients(
        to: List<xyz.desent.domain.model.EmailRecipient>,
        cc: List<xyz.desent.domain.model.EmailRecipient>,
        bcc: List<xyz.desent.domain.model.EmailRecipient>,
        pgp: Boolean
    ): Result<Triple<List<xyz.desent.domain.model.EmailRecipient>, List<xyz.desent.domain.model.EmailRecipient>, List<xyz.desent.domain.model.EmailRecipient>>> {
        if (to.isEmpty()) return Result.failure(Exception("At least one To recipient is required"))

        val total = to.size + cc.size + bcc.size
        if (total > EmailBridgeTags.MAX_ENVELOPE_RECIPIENTS) {
            return Result.failure(
                Exception("Too many recipients ($total; max ${EmailBridgeTags.MAX_ENVELOPE_RECIPIENTS})")
            )
        }

        val seen = HashSet<String>()
        fun dedupe(list: List<xyz.desent.domain.model.EmailRecipient>) =
            list.filter { seen.add(it.address.trim().lowercase()) }

        val seenAll = to.asSequence() + cc.asSequence() + bcc.asSequence()
        seenAll.forEach { recipient ->
            if (!RECIPIENT_ADDRESS_REGEX.matches(recipient.address.trim())) {
                return Result.failure(Exception("Invalid recipient address: ${recipient.address.trim()}"))
            }
        }

        if (pgp && total > 1) {
            return Result.failure(Exception("PGP sends support a single recipient"))
        }

        return Result.success(Triple(dedupe(to), dedupe(cc), dedupe(bcc)))
    }

    /**
     * Resolve the `from` address for an outbound email: an address the user
     * owns on the bridge domain (the relay rejects sends from anything else).
     * Local account cache first, then the registration API.
     */
    private suspend fun resolveFromAlias(userNpub: String): String? {
        accountDao.getAccount(userNpub)?.nip05
            ?.takeIf { it.endsWith("@$EMAIL_DOMAIN", ignoreCase = true) }
            ?.let { return it }
        val remote = registrationRepository.getAccount().getOrNull() ?: return null
        return remote.nip05?.takeIf { it.endsWith("@$EMAIL_DOMAIN", ignoreCase = true) }
            ?: remote.local?.takeIf { it.isNotBlank() }?.let { "$it@$EMAIL_DOMAIN" }
    }

    override suspend fun saveEmail(email: xyz.desent.domain.model.Email) {
        // Stamp the spam verdict before insert so the inbox (which filters
        // isSpam at the repo layer) never flashes a spam message.
        val entity = emailMapper.mapToEntity(email)
        val stamped = spamFilterRepository?.classify(email)?.let { verdict ->
            entity.copy(
                spamScore = verdict.score,
                isSpam = verdict.isSpam,
                spamReasons = if (verdict.reasons.isEmpty()) null else verdict.reasons.joinToString(",")
            )
        } ?: entity
        emailDao.insertEmail(stamped)
    }

    override suspend fun getEmailById(id: String): xyz.desent.domain.model.Email? {
        return emailDao.getEmailById(id)?.let { emailMapper.mapToDomain(it) }
    }

    override suspend fun markEmailAsRead(id: String) {
        emailDao.markEmailAsRead(id)
        stampOverlayRead(id, read = true)
    }

    override suspend fun markEmailAsUnread(id: String) {
        emailDao.markEmailAsUnread(id)
        stampOverlayRead(id, read = false)
    }

    /** Mirror a read-state change into the synced overlay (no-op without the hook). */
    private suspend fun stampOverlayRead(id: String, read: Boolean) {
        val hook = mailFolderRepository ?: return
        val row = emailDao.getEmailById(id) ?: return
        runCatching { hook.setRead(row.recipientNpub, row.folderKey, read) }
    }

    override suspend fun requestDeletion(emailId: String): Result<String> {
        return try {
            if (getEmailById(emailId) == null) {
                return Result.failure(Exception("Email not found"))
            }

            Log.d(TAG, " Deleting message via DELETE /api/messages: ${emailId.take(16)}")

            // Recipient-initiated gift-wrap deletion MUST go through the HTTP API —
            // the recipient cannot sign with the gift wrap's ephemeral author key,
            // and the endpoint also writes the tombstone that keeps a §6.3 mirror
            // import from ever resurrecting deleted mail. See
            // refs/MESSAGES_API_REFERENCE.md and ANDROID_DM_FANOUT.md §6.4.
            val result = messageClient.deleteMessage(emailId)
            if (result.isFailure) {
                Log.e(TAG, " Message delete failed: ${result.exceptionOrNull()?.message}")
                return result.map { emailId }
            }

            // §6.4: ALSO tell the mirrors — a user-signed kind 5 the relay
            // enqueues to the mirroring targets (strict NIP-09 peers ignore it).
            publishMirrorDeletion(emailId)

            // Server confirmed hard delete → drop locally immediately.
            emailDao.deleteEmail(emailId)
            // Decrypted inner-MIME attachments (if any) go with the message.
            pgpAttachmentCache?.clear(emailId)
            Log.d(TAG, " Message deleted locally and server-side")

            Result.success(emailId)
        } catch (e: Exception) {
            Log.e(TAG, " Failed to delete message: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * ANDROID_DM_FANOUT.md §6.4: fire-and-forget user-signed kind 5 with an
     * `["e", wrapId]` tag, published to the DeSent relay once the HTTP
     * delete has succeeded. The relay enqueues it to the mirrors; strict
     * NIP-09 relays ignore it (wrap authors are ephemeral keys) and
     * everything expires via NIP-40 eventually. Never blocks or fails the
     * local deletion.
     */
    private fun publishMirrorDeletion(emailId: String) {
        val store = securityConfigStore ?: return
        mirrorDeletionScope.launch {
            try {
                val npub = preferencesManager.getActiveNpub() ?: return@launch
                if (store.get(npub)?.dmFanout != true) return@launch

                val identity = secureKeyManager.getIdentityForAccount(npub).getOrNull()
                    ?: return@launch

                val event = GenericEvent.builder()
                    .pubKey(identity.publicKey)
                    .kind(NostrKinds.DELETION)
                    .createdAt(System.currentTimeMillis() / 1000)
                    .content("")
                    .tags(listOf(GenericTag("e", listOf(emailId))) as List<nostr.event.BaseTag>)
                    .build()
                identity.sign(event)

                relayRepository.publishEventToRelay(event, RelayConfig.EMAIL_RELAY_URL)
                // Best-effort durable trace; a no-op when the row is already gone.
                emailDao.markDeletionRequested(emailId, event.id)
                Log.d(TAG, "kind-5 mirror deletion published for ${emailId.take(8)}...")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "mirror deletion publish failed (non-fatal): ${e.message}")
            }
        }
    }

    override fun observeEmails(recipientNpub: String): Flow<List<xyz.desent.domain.model.Email>> {
        return emailDao.observeEmailsByRecipient(recipientNpub).map { entities ->
            entities.map { emailMapper.mapToDomain(it) }.filterNot { it.isSpam }
        }
    }

    override fun observeUnreadCount(recipientNpub: String): Flow<Int> {
        // Inbox unread count excludes quarantined spam.
        return emailDao.observeEmailsByRecipient(recipientNpub).map { entities ->
            entities.count { !it.isRead && !it.isSpam }
        }
    }

    override suspend fun getUnreadCount(recipientNpub: String): Int {
        return emailDao.getUnreadCount(recipientNpub)
    }

    override fun observeSpamCount(recipientNpub: String): Flow<Int> {
        return emailDao.observeSpamCount(recipientNpub)
    }

    override fun observeThreads(recipientNpub: String): Flow<List<xyz.desent.domain.model.Email>> {
        return emailDao.observeThreads(recipientNpub).map { entities ->
            entities.map { emailMapper.mapToDomain(it) }.filterNot { it.isSpam }
        }
    }

    override fun observeThread(recipientNpub: String, threadKey: String): Flow<List<xyz.desent.domain.model.Email>> {
        return emailDao.observeThread(recipientNpub, threadKey).map { entities ->
            entities.map { emailMapper.mapToDomain(it) }
        }
    }

    override fun observeRecentOutboundRecipients(
        recipientNpub: String,
        limit: Int
    ): Flow<List<xyz.desent.domain.repository.RecentCorrespondent>> {
        return emailDao.observeRecentOutboundAddresses(recipientNpub, limit).map { rows ->
            rows.map { xyz.desent.domain.repository.RecentCorrespondent(it.address, it.lastAt) }
        }
    }

    override suspend fun markThreadRead(recipientNpub: String, threadKey: String) {
        emailDao.markThreadRead(recipientNpub, threadKey)
        runCatching { mailFolderRepository?.markThreadRead(recipientNpub, threadKey) }
    }

    override suspend fun findAttachmentKey(sha256: String): String? {
        return try {
            emailDao.getEmailsWithAttachments().firstNotNullOfOrNull { entity ->
                val attachments = emailMapper.mapToDomain(entity).attachments
                attachments.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }?.keyHex
            }
        } catch (e: Exception) {
            Log.w(TAG, "findAttachmentKey failed for $sha256: ${e.message}")
            null
        }
    }

    /**
     * Prefilled recipients for a reply (ANDROID_EMAIL_MIGRATION.md §7):
     * plain reply → the anchor's external address; reply-all → RFC 5322
     * §3.6.2 semantics (`to` = `reply_to` ∥ `from`; `cc` = original to ∪ cc
     * minus every address the user owns). Null when the anchor carries no
     * external recipient to answer.
     */
    override suspend fun computeReplyRecipients(
        emailId: String,
        replyAll: Boolean
    ): xyz.desent.domain.model.ReplyRecipients? {
        val anchor = emailDao.getEmailById(emailId) ?: return null
        val mapper = emailMapper
        val anchorTo = anchor.toRecipientsJson?.let { mapper.decodeRecipients(it) }
            ?: anchor.toEmail?.let { listOf(xyz.desent.domain.model.EmailRecipient(it)) }
            ?: emptyList()
        val anchorCc = anchor.ccRecipientsJson?.let { mapper.decodeRecipients(it) } ?: emptyList()

        val isOutboundAnchor = anchor.direction == EmailDirection.OUTBOUND.name

        // Plain reply: exactly who we were talking to — the sender (or their
        // Reply-To) on an inbound anchor, or the address(es) we sent to on an
        // outbound anchor (replying within our own thread).
        if (!replyAll) {
            val to = if (isOutboundAnchor) {
                anchorTo
            } else {
                listOfNotNull(
                    (anchor.replyTo ?: anchor.senderEmail).takeIf { it.isNotBlank() && it.contains('@') }
                ).map { xyz.desent.domain.model.EmailRecipient(it) }
            }
            if (to.isEmpty()) return null
            return xyz.desent.domain.model.ReplyRecipients(to = to)
        }

        // Reply-all: the user's own addresses never appear in the outgoing
        // lists (primary + active aliases + the addresses this very copy was
        // delivered to — all case-insensitive).
        val userNpub = preferencesManager.npubKey.firstOrNull() ?: return null
        val owned = ownedAddresses(userNpub, anchor)
        val seen = HashSet<String>(owned)

        fun keep(recipient: xyz.desent.domain.model.EmailRecipient): Boolean =
            seen.add(recipient.address.trim().lowercase())

        val to = if (isOutboundAnchor) {
            anchorTo.filter { keep(it) }
        } else {
            buildList {
                anchor.replyTo?.takeIf { it.isNotBlank() && it.contains('@') }
                    ?.let { add(xyz.desent.domain.model.EmailRecipient(it)) }
                add(xyz.desent.domain.model.EmailRecipient(anchor.senderEmail))
            }.filter { keep(it) }
        }
        val cc = (anchorTo + anchorCc).filter { keep(it) }
        if (to.isEmpty()) return null
        return xyz.desent.domain.model.ReplyRecipients(to = to, cc = cc)
    }

    /**
     * Every address the user owns for the reply-all self-filter: the primary
     * bridge address (local account cache, then the registration API),
     * active aliases, and the envelope/alias addresses this anchor copy was
     * delivered to. Alias lookup is fail-soft — offline prefill filters on
     * the primary alone rather than blocking the reply.
     */
    private suspend fun ownedAddresses(userNpub: String, anchor: EmailEntity): Set<String> {
        val owned = mutableSetOf<String>()
        fun addOwned(address: String?) {
            address?.trim()?.lowercase()?.takeIf { it.contains('@') }?.let { owned.add(it) }
        }
        addOwned(accountDao.getAccount(userNpub)?.nip05)
        addOwned(anchor.alias)
        addOwned(anchor.deliveredTo)
        aliasRepository?.listAliases()?.getOrNull()?.let { (aliases, _) ->
            aliases.filter { it.isActive }.forEach { addOwned(it.email) }
        }
        return owned
    }

    /**
     * Reply to any stored email (inbound legacy kind-14, inbound kind-1010, or
     * our own outbound row). Under NIP-EMAIL a reply is just an outbound send
     * whose `in_reply_to` points at the anchor's `message_id` — legacy anchors
     * map naturally (`sender`→`to`, `message_id`→`in_reply_to`) so old threads
     * stay replyable without any server thread state.
     *
     * Empty [to] keeps the plain-reply derivation ([computeReplyRecipients]
     * with replyAll=false); explicit lists (the reply screen, reply-all
     * prefill) are validated and used verbatim.
     * See refs/FromServer/EMAIL_NIP_ANDROID_MIGRATION.md §6–8.
     */
    override suspend fun sendReply(
        emailId: String,
        replyText: String,
        format: xyz.desent.domain.model.EmailBodyFormat,
        pgp: Boolean,
        to: List<xyz.desent.domain.model.EmailRecipient>,
        cc: List<xyz.desent.domain.model.EmailRecipient>,
        bcc: List<xyz.desent.domain.model.EmailRecipient>
    ): Result<Unit> {
        return try {
            validateBody(replyText).onFailure { return@sendReply Result.failure(it) }

            val anchor = emailDao.getEmailById(emailId)
                ?: return Result.failure(Exception("Email not found"))

            val effectiveTo = to.ifEmpty {
                computeReplyRecipients(emailId, replyAll = false)?.to
                    ?: return Result.failure(Exception("No external recipient on this thread"))
            }

            val subject = if (anchor.subject.startsWith("Re:", ignoreCase = true)) {
                anchor.subject
            } else {
                "Re: ${anchor.subject}"
            }

            // RFC 5322 References: the anchor's ancestry + the anchor itself.
            val references = listOfNotNull(anchor.referencesHeader, anchor.messageId)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }

            // Standard email quoting: the anchor rides quoted below the reply
            // so recipients see the conversation under the new text (the
            // relay forwards content verbatim — nothing server-side appends
            // history). Skips receipts, PGP-armored anchors and blank bodies;
            // truncates to the body cap so long histories can't fail sends.
            val outboundBody = OutboundQuoteBuilder.build(replyText, anchor, format, MAX_BODY_BYTES)

            sendOutbound(
                body = outboundBody,
                to = effectiveTo,
                cc = cc,
                bcc = bcc,
                subject = subject,
                inReplyTo = anchor.messageId,
                references = references,
                threadKey = anchor.threadKey,
                format = format,
                pgp = pgp
            )
        } catch (e: Exception) {
            Log.e(TAG, " Failed to send reply: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Cold send a brand-new email (no prior thread context) to the full RFC
     * 5322 recipient lists. Same unified path as a reply — just no
     * `in_reply_to`.
     */
    override suspend fun sendColdEmail(
        to: List<xyz.desent.domain.model.EmailRecipient>,
        subject: String,
        body: String,
        format: xyz.desent.domain.model.EmailBodyFormat,
        pgp: Boolean,
        cc: List<xyz.desent.domain.model.EmailRecipient>,
        bcc: List<xyz.desent.domain.model.EmailRecipient>
    ): Result<Unit> {
        return try {
            validateBody(body).onFailure { return@sendColdEmail Result.failure(it) }
            if (to.isEmpty()) return Result.failure(Exception("At least one To recipient is required"))
            if (subject.isBlank()) return Result.failure(Exception("Subject is required"))

            sendOutbound(
                body = body,
                to = to,
                cc = cc,
                bcc = bcc,
                subject = subject,
                inReplyTo = null,
                references = null,
                // A cold send starts its own thread, keyed by its fresh Message-ID.
                threadKey = null,
                format = format,
                pgp = pgp
            )
        } catch (e: Exception) {
            Log.e(TAG, " Failed to send cold email: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Unified NIP-EMAIL outbound path (kind 1010). Builds the rumor with the
     * `from`/`to`*(N)/`cc`*(N)/`bcc`*(N)/`subject`/`direction: outbound`/
     * `message_id` (+ optional `in_reply_to`/`references`) tags, gift-wraps it
     * to the relay's npub (NIP-42/44/59 layers unchanged), publishes, then
     * records the outbound message + a pending outbox entry. The relay answers
     * with a `direction: delivery-receipt` gift wrap matched by subject +
     * any-address overlap + time. See refs/FromServer/REPLY_AND_FORWARD.md §4,
     * END-01 §3.4, END-03 §3.
     */
    private suspend fun sendOutbound(
        body: String,
        to: List<xyz.desent.domain.model.EmailRecipient>,
        cc: List<xyz.desent.domain.model.EmailRecipient>,
        bcc: List<xyz.desent.domain.model.EmailRecipient>,
        subject: String,
        inReplyTo: String?,
        references: String?,
        threadKey: String?,
        format: xyz.desent.domain.model.EmailBodyFormat,
        pgp: Boolean = false
    ): Result<Unit> {
        val (toNorm, ccNorm, bccNorm) = validateRecipients(to, cc, bcc, pgp).fold(
            onSuccess = { it },
            onFailure = { return Result.failure(it) }
        )
        val toEmail = toNorm.first().address

        val userNpub = preferencesManager.npubKey.firstOrNull()
            ?: return Result.failure(Exception("Not logged in"))
        val fromAlias = resolveFromAlias(userNpub)
            ?: return Result.failure(
                Exception("No @$EMAIL_DOMAIN address claimed — register an address first")
            )

        // PGP sends: discover the (single) recipient's key (WKD proxy),
        // encrypt the body on-device, and publish the armor as the rumor
        // content. The relay re-wraps it into RFC 3156 PGP/MIME — it never
        // sees plaintext. The LOCAL row + outbox entry keep the typed draft.
        var publishContent = body
        if (pgp) {
            val pgpRepo = pgpKeyRepository
                ?: return Result.failure(Exception("PGP is not available on this build"))
            val armor = pgpRepo.encryptForRecipient(toEmail, body).fold(
                onSuccess = { it },
                onFailure = { return Result.failure(it) }
            )
            if (!armor.startsWith(xyz.desent.crypto.OpenPgpCrypto.MESSAGE_ARMOR_HEADER)) {
                return Result.failure(Exception("Encryption produced an invalid PGP MESSAGE block"))
            }
            val armorBytes = armor.toByteArray(Charsets.UTF_8).size
            if (armorBytes > PGP_MAX_ARMOR_BYTES) {
                return Result.failure(
                    Exception("Encrypted body too large ($armorBytes bytes; max $PGP_MAX_ARMOR_BYTES)")
                )
            }
            publishContent = armor
        }

        val messageId = EmailBridgeTags.makeMessageId()
        val effectiveThreadKey = threadKey ?: messageId

        val event = buildAndPublishGiftWrap(
            fromAlias = fromAlias,
            content = publishContent,
            to = toNorm,
            cc = ccNorm,
            bcc = bccNorm,
            subject = subject,
            inReplyTo = inReplyTo,
            references = references,
            format = format,
            messageId = messageId,
            pgpEncrypted = pgp
        ).fold(
            onSuccess = { it },
            onFailure = { return Result.failure(it) }
        )

        // Monotonic thread clock: the row must sort below every message
        // already in the thread (EmailDao.observeThread's CASE), even when
        // the device clock trails the senders'/relay's clocks — otherwise a
        // fresh send lands above the previous message.
        val nowMs = maxOf(
            System.currentTimeMillis(),
            (emailDao.getThreadMaxSortKey(userNpub, effectiveThreadKey) ?: 0L) + 1
        )

        // Outbound row: renders as a sent message in the thread and anchors
        // future replies-to-our-send (their in_reply_to finds it by message_id).
        val outboundRow = EmailEntity(
            id = event.id,
            recipientNpub = userNpub,
            senderEmail = fromAlias,
            senderDomain = EMAIL_DOMAIN,
            senderName = null,
            replyTo = null,
            subject = subject,
            content = body,
            bodyFormat = format.name,
            dkimStatus = DkimStatus.NONE.name,
            spfStatus = SpfStatus.UNKNOWN.name,
            dmarcStatus = null,
            emailType = EmailType.OTHER.name,
            bridge = "email",
            messageId = messageId,
            inReplyTo = inReplyTo,
            referencesHeader = references,
            threadToken = null,
            threadRoot = effectiveThreadKey,
            toEmail = toEmail,
            toRecipientsJson = emailMapper.encodeRecipients(toNorm),
            ccRecipientsJson = emailMapper.encodeRecipients(ccNorm),
            bccRecipientsJson = emailMapper.encodeRecipients(bccNorm),
            direction = EmailDirection.OUTBOUND.name,
            alias = null,
            attachmentsJson = null,
            senderDate = nowMs,
            createdAt = nowMs,
            isRead = true,
            deletionRequested = false,
            deletionEventId = null,
            threadSenderPubkey = null,
            isPgpEncrypted = pgp
        )
        emailDao.insertEmail(outboundRow)

        // Pending outbox entry for delivery-receipt correlation (subject +
        // any-address overlap + time) and one-tap retry (everything needed to
        // rebuild the rumor verbatim).
        emailOutboxDao.insert(
            EmailOutboxEntity(
                messageId = messageId,
                recipientNpub = userNpub,
                threadKey = effectiveThreadKey,
                fromAlias = fromAlias,
                toEmail = toEmail,
                toRecipientsJson = emailMapper.encodeRecipients(toNorm),
                ccRecipientsJson = emailMapper.encodeRecipients(ccNorm),
                bccRecipientsJson = emailMapper.encodeRecipients(bccNorm),
                subject = subject,
                body = body,
                sentAt = nowMs,
                inReplyTo = inReplyTo,
                referencesHeader = references,
                bodyFormat = format.name,
                giftWrapEventId = event.id,
                isPgpEncrypted = pgp
            )
        )

        Log.d(TAG, " Outbound kind-1010 gift wrap published to ${toNorm.size + ccNorm.size + bccNorm.size} recipient(s), first $toEmail (thread ${effectiveThreadKey.take(16)}${if (pgp) ", pgp" else ""})")
        return Result.success(Unit)
    }

    /**
     * The wrap-sign-publish core shared by [sendOutbound] and [retrySend]:
     * builds the rumor tags, NIP-44 seals + gift-wraps to the relay's npub,
     * signs with the user's key (the conversation key is symmetric, so the
     * relay — holder of its own private key — unwraps it), and publishes.
     */
    private suspend fun buildAndPublishGiftWrap(
        fromAlias: String,
        content: String,
        to: List<xyz.desent.domain.model.EmailRecipient>,
        cc: List<xyz.desent.domain.model.EmailRecipient>,
        bcc: List<xyz.desent.domain.model.EmailRecipient>,
        subject: String,
        inReplyTo: String?,
        references: String?,
        format: xyz.desent.domain.model.EmailBodyFormat,
        messageId: String,
        pgpEncrypted: Boolean = false
    ): Result<GenericEvent> {
        val extraTags = EmailBridgeTags.outbound(
            fromAlias = fromAlias,
            to = to,
            cc = cc,
            bcc = bcc,
            subject = subject,
            messageId = messageId,
            inReplyTo = inReplyTo,
            references = references,
            format = format,
            pgpEncrypted = pgpEncrypted
        )
        return publishGiftWrap(
            recipientNpub = RelayConfig.RELAY_PUBKEY_NPUB,
            kind = NostrKinds.EMAIL_MESSAGE,
            content = content,
            extraTags = extraTags
        )
    }

    /**
     * Generalized wrap-sign-publish core: NIP-59 gift wrap of a [kind] rumor
     * to ANY recipient npub (the relay for outbound email, or another user for
     * NIP-EMAIL forwarding). The signed seal authenticates the sender; the
     * outer wrap is signed by a one-time keypair — NIP-44 conversation keys
     * are symmetric, so whoever holds the recipient's private key can unwrap.
     */
    private suspend fun publishGiftWrap(
        recipientNpub: String,
        kind: Int,
        content: String,
        extraTags: List<List<String>>
    ): Result<GenericEvent> = runCatching {
        val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrThrow()
        val senderNpub = Bech32Utils.hexToNpub(identity.publicKey.toHexString())

        val event = giftWrapEncryptionService.wrapGift(
            content = content,
            recipientNpub = recipientNpub,
            senderNpub = senderNpub,
            kind = kind,
            extraTags = extraTags
        ).getOrThrow()

        // Publish to the DeSent service relay only (always connected as a
        // persistent relay) — never broadcast to other relays.
        relayRepository.publishEventToRelay(event, RelayConfig.EMAIL_RELAY_URL)
        event
    }

    // ==================== Outbox (delivery ledger) ====================

    override fun observeOutbox(npub: String): Flow<List<xyz.desent.domain.model.EmailOutboxEntry>> {
        return emailOutboxDao.observeAll(npub).map { entries -> entries.map { it.toDomain() } }
    }

    override fun observeOutboxByThread(
        npub: String,
        threadKey: String
    ): Flow<List<xyz.desent.domain.model.EmailOutboxEntry>> {
        return emailOutboxDao.observeByThread(npub, threadKey).map { entries -> entries.map { it.toDomain() } }
    }

    override suspend fun reconcileOutbox(npub: String) {
        val cutoff = System.currentTimeMillis() - OUTBOX_TIMEOUT_MS
        emailOutboxDao.findStalePending(npub, cutoff).forEach { entry ->
            emailOutboxDao.markTimedOut(
                messageId = entry.messageId,
                reason = "No delivery confirmation received",
                atMs = System.currentTimeMillis()
            )
            Log.w(TAG, " Outbox entry timed out (to=${entry.toEmail}, thread ${entry.threadKey.take(16)})")
        }
    }

    override suspend fun retrySend(messageId: String): Result<Unit> {
        return try {
            val entry = emailOutboxDao.getByMessageId(messageId)
                ?: return Result.failure(Exception("Outbox entry not found"))
            if (entry.status != EmailOutboxEntity.STATUS_FAILED &&
                entry.status != EmailOutboxEntity.STATUS_TIMED_OUT
            ) {
                return Result.failure(Exception("Only failed or timed-out sends can be retried"))
            }
            if (entry.isSynthetic) {
                return Result.failure(Exception("Sends made on another device can't be retried here"))
            }

            val format = runCatching {
                xyz.desent.domain.model.EmailBodyFormat.valueOf(entry.bodyFormat)
            }.getOrDefault(xyz.desent.domain.model.EmailBodyFormat.PLAIN)

            // Rebuild the recipient lists exactly as sent (pre-v56 rows carry
            // only the legacy single `toEmail`).
            val retryTo = entry.toRecipientsJson?.let { emailMapper.decodeRecipients(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(xyz.desent.domain.model.EmailRecipient(entry.toEmail))
            val retryCc = entry.ccRecipientsJson?.let { emailMapper.decodeRecipients(it) }
                ?: emptyList()
            val retryBcc = entry.bccRecipientsJson?.let { emailMapper.decodeRecipients(it) }
                ?: emptyList()

            // PGP sends re-run discovery + encryption from the plaintext
            // draft (fresh WKD state, fresh armor) — identical to the first
            // attempt. Plain sends publish the stored body verbatim.
            var publishContent = entry.body
            if (entry.isPgpEncrypted) {
                val pgpRepo = pgpKeyRepository
                    ?: return Result.failure(Exception("PGP is not available on this build"))
                val armor = pgpRepo.encryptForRecipient(retryTo.first().address, entry.body).fold(
                    onSuccess = { it },
                    onFailure = { return Result.failure(it) }
                )
                publishContent = armor
            }

            // Fresh Message-ID: the relay may have already seen the old rumor,
            // and the receipt correlation is subject+addresses+time anyway.
            val freshMessageId = EmailBridgeTags.makeMessageId()
            val event = buildAndPublishGiftWrap(
                fromAlias = entry.fromAlias,
                content = publishContent,
                to = retryTo,
                cc = retryCc,
                bcc = retryBcc,
                subject = entry.subject,
                inReplyTo = entry.inReplyTo,
                references = entry.referencesHeader,
                format = format,
                messageId = freshMessageId,
                pgpEncrypted = entry.isPgpEncrypted
            ).fold(
                onSuccess = { it },
                onFailure = { return Result.failure(it) }
            )

            val nowMs = System.currentTimeMillis()
            // One row per logical send: replace the entry under the fresh id.
            emailOutboxDao.deleteByMessageId(entry.messageId)
            emailOutboxDao.insert(
                entry.copy(
                    messageId = freshMessageId,
                    sentAt = nowMs,
                    status = EmailOutboxEntity.STATUS_PENDING,
                    receiptEventId = null,
                    resolvedAt = null,
                    errorMessage = null,
                    giftWrapEventId = event.id
                )
            )
            // Re-point the sent bubble so its footer tracks the new entry.
            emailDao.updateOutboundMessageId(entry.messageId, freshMessageId)

            Log.d(TAG, " Outbox retry published for ${entry.toEmail} (thread ${entry.threadKey.take(16)})")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, " Outbox retry failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteOutboxEntry(messageId: String) {
        emailOutboxDao.deleteByMessageId(messageId)
    }

    // ==================== Forwarding / migration (NIP-EMAIL) ====================

    override suspend fun getForwardEligibleEmailIds(
        recipientNpub: String,
        threadKeys: List<String>?
    ): List<String> {
        val entities = if (threadKeys != null) {
            if (threadKeys.isEmpty()) return emptyList()
            emailDao.getForwardEligibleEmailsByThreads(recipientNpub, threadKeys)
        } else {
            // Whole-mailbox migration skips quarantined spam by default —
            // it is junk by definition; the thread picker only offers inbox
            // threads anyway.
            emailDao.getForwardEligibleEmails(recipientNpub).filterNot { it.isSpam }
        }
        return entities.map { it.id }
    }

    override suspend fun forwardEmails(
        emailIds: Collection<String>,
        targetNpub: String,
        onProgress: (processed: Int, total: Int) -> Unit
    ): Result<EmailForwardSummary> {
        return try {
            runForward(emailIds, targetNpub, onProgress)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, " Forward run aborted: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun runForward(
        emailIds: Collection<String>,
        targetNpub: String,
        onProgress: (processed: Int, total: Int) -> Unit
    ): Result<EmailForwardSummary> {
        if (emailIds.isEmpty()) return Result.failure(Exception("Nothing selected to forward"))

        // Validate the target before doing any work: npubToHex verifies the
        // bech32 checksum and HRP.
        runCatching { Bech32Utils.npubToHex(targetNpub) }
            .onFailure { return Result.failure(Exception("Invalid recipient npub")) }

        val userNpub = preferencesManager.npubKey.firstOrNull()
            ?: return Result.failure(Exception("Not logged in"))
        if (targetNpub == userNpub) {
            return Result.failure(Exception("Target key is your own — nothing to migrate"))
        }

        val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrThrow()
        val forwarderHex = identity.publicKey.toHexString()

        var sent = 0
        var alreadyDelivered = 0
        var failed = 0
        var processed = 0
        val total = emailIds.size

        emailIds.forEach { emailId ->
            try {
                val entity = emailDao.getEmailById(emailId)
                when {
                    entity == null -> {
                        // Vanished mid-run (deleted elsewhere) — count as done.
                    }
                    !isForwardEligible(entity) -> {
                        // Defense in depth: the selection queries filter, but
                        // re-check each row before it goes on the wire.
                    }
                    emailForwardLedgerDao.get(emailId, targetNpub)?.status ==
                        EmailForwardLedgerEntity.STATUS_SENT -> {
                        alreadyDelivered++
                    }
                    else -> {
                        val nowMs = System.currentTimeMillis()
                        emailForwardLedgerDao.upsert(
                            EmailForwardLedgerEntity(
                                emailId = emailId,
                                targetNpub = targetNpub,
                                status = EmailForwardLedgerEntity.STATUS_PENDING,
                                createdAt = nowMs
                            )
                        )
                        val tags = EmailBridgeTags.forwarded(
                            email = emailMapper.mapToDomain(entity),
                            forwarderPubkeyHex = forwarderHex
                        )
                        val publish = publishGiftWrap(
                            recipientNpub = targetNpub,
                            kind = NostrKinds.EMAIL_MESSAGE,
                            content = entity.content,
                            extraTags = tags
                        )
                        if (publish.isSuccess) {
                            emailForwardLedgerDao.upsert(
                                EmailForwardLedgerEntity(
                                    emailId = emailId,
                                    targetNpub = targetNpub,
                                    status = EmailForwardLedgerEntity.STATUS_SENT,
                                    giftWrapEventId = publish.getOrNull()?.id,
                                    createdAt = nowMs,
                                    resolvedAt = System.currentTimeMillis()
                                )
                            )
                            sent++
                            Log.d(TAG, " Forwarded ${emailId.take(16)} to ${targetNpub.take(16)}")
                        } else {
                            val reason = publish.exceptionOrNull()?.message ?: "publish failed"
                            emailForwardLedgerDao.upsert(
                                EmailForwardLedgerEntity(
                                    emailId = emailId,
                                    targetNpub = targetNpub,
                                    status = EmailForwardLedgerEntity.STATUS_FAILED,
                                    errorMessage = reason,
                                    createdAt = nowMs,
                                    resolvedAt = System.currentTimeMillis()
                                )
                            )
                            failed++
                            Log.w(TAG, " Forward failed for ${emailId.take(16)}: $reason")
                        }
                    }
                }
            } catch (ce: CancellationException) {
                // Ledger keeps the PENDING row — the next run resumes here.
                throw ce
            } catch (e: Exception) {
                failed++
                Log.e(TAG, " Forward error for ${emailId.take(16)}: ${e.message}", e)
            }

            processed++
            onProgress(processed, total)
            if (processed < total) delay(FORWARD_PACE_MS)
        }

        return Result.success(
            EmailForwardSummary(
                total = total,
                sent = sent,
                alreadyDelivered = alreadyDelivered,
                failed = failed
            )
        )
    }

    /**
     * A row may go on the wire only if it is received mail: our own sends,
     * delivery receipts, SYSTEM rows and security/badge-direction notices
     * never re-deliver. Mirrors the SQL eligibility filter.
     */
    private fun isForwardEligible(entity: EmailEntity): Boolean {
        if (entity.emailType == EmailType.SYSTEM.name) return false
        val direction = entity.direction ?: return true // legacy kind-14 row
        return direction != EmailDirection.OUTBOUND.name &&
            direction != EmailDirection.DELIVERY_RECEIPT.name &&
            direction != EmailDirection.SECURITY.name
    }

    private fun EmailOutboxEntity.toDomain(): xyz.desent.domain.model.EmailOutboxEntry =
        xyz.desent.domain.model.EmailOutboxEntry(
            messageId = messageId,
            recipientNpub = recipientNpub,
            threadKey = threadKey,
            fromAlias = fromAlias,
            toEmail = toEmail,
            toRecipients = toRecipientsJson?.let { emailMapper.decodeRecipients(it) }?.takeIf { it.isNotEmpty() }
                ?: listOf(xyz.desent.domain.model.EmailRecipient(toEmail)),
            ccRecipients = ccRecipientsJson?.let { emailMapper.decodeRecipients(it) } ?: emptyList(),
            bccRecipients = bccRecipientsJson?.let { emailMapper.decodeRecipients(it) } ?: emptyList(),
            subject = subject,
            body = body,
            sentAt = sentAt,
            status = xyz.desent.domain.model.OutboxStatus.fromEntity(status),
            receiptEventId = receiptEventId,
            resolvedAt = resolvedAt,
            inReplyTo = inReplyTo,
            referencesHeader = referencesHeader,
            bodyFormat = runCatching {
                xyz.desent.domain.model.EmailBodyFormat.valueOf(bodyFormat)
            }.getOrDefault(xyz.desent.domain.model.EmailBodyFormat.PLAIN),
            errorMessage = errorMessage,
            isSynthetic = isSynthetic,
            isPgpEncrypted = isPgpEncrypted
        )
}
