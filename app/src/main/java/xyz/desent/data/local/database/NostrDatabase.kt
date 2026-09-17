package xyz.desent.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import xyz.desent.data.local.database.converter.Converters
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.data.local.database.dao.BayesianTokenDao
import xyz.desent.data.local.database.dao.ContactProfileLinkDao
import xyz.desent.data.local.database.dao.DomainFaviconDao
import xyz.desent.data.local.database.dao.FollowDao
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.local.database.dao.EmailOutboxDao
import xyz.desent.data.local.database.dao.EmailForwardLedgerDao
import xyz.desent.data.local.database.dao.PrivateNoteDao
import xyz.desent.data.local.database.dao.CalendarEventDao
import xyz.desent.data.local.database.dao.CalendarDao
import xyz.desent.data.local.database.dao.CalendarRsvpDao
import xyz.desent.data.local.database.dao.FavoriteNoteDao
import xyz.desent.data.local.database.dao.PrivateContactsDao
import xyz.desent.data.local.database.dao.RelayDao
import xyz.desent.data.local.database.dao.SpamRuleDao
import xyz.desent.data.local.database.dao.PersonalSpamRuleDao
import xyz.desent.data.local.database.dao.UserDao
import xyz.desent.data.local.database.dao.SecurityAlertDao
import xyz.desent.data.local.database.dao.BadgeDao
import xyz.desent.data.local.database.dao.BadgeNoticeDao
import xyz.desent.data.local.database.dao.MailFolderDao
import xyz.desent.data.local.database.dao.MailStateDao
import xyz.desent.data.local.database.dao.UserFileDao
import xyz.desent.data.local.database.entity.AccountEntity
import xyz.desent.data.local.database.entity.BayesianTokenEntity
import xyz.desent.data.local.database.entity.FollowEntity
import xyz.desent.data.local.database.entity.RelayEntity
import xyz.desent.data.local.database.entity.SpamRuleEntity
import xyz.desent.data.local.database.entity.PersonalSpamRuleEntity
import xyz.desent.data.local.database.entity.UserEntity
import xyz.desent.data.local.database.entity.BadgeAwardEntity
import xyz.desent.data.local.database.entity.BadgeDefinitionEntity
import xyz.desent.data.local.database.entity.BadgePinEntity
import xyz.desent.data.local.database.entity.BadgeNoticeEntity
import xyz.desent.data.local.database.entity.EmailEntity
import xyz.desent.data.local.database.entity.EmailOutboxEntity
import xyz.desent.data.local.database.entity.EmailForwardLedgerEntity
import xyz.desent.data.local.database.entity.ContactProfileLinkEntity
import xyz.desent.data.local.database.entity.DomainFaviconEntity
import xyz.desent.data.local.database.entity.SecurityAlertEntity
import xyz.desent.data.local.database.entity.PrivateNoteEntity
import xyz.desent.data.local.database.entity.CalendarEventEntity
import xyz.desent.data.local.database.entity.CalendarEntity
import xyz.desent.data.local.database.entity.CalendarRsvpEntity
import xyz.desent.data.local.database.entity.FavoriteNoteEntity
import xyz.desent.data.local.database.entity.PrivateContactsEntity
import xyz.desent.data.local.database.entity.MailFolderManifestEntity
import xyz.desent.data.local.database.entity.MailStateEntity
import xyz.desent.data.local.database.entity.MailShardStateEntity
import xyz.desent.data.local.database.entity.UserFileEntity

@Database(
    entities = [
        UserEntity::class,
        FollowEntity::class,
        RelayEntity::class,
        EmailEntity::class,
        AccountEntity::class,
        SpamRuleEntity::class,
        PersonalSpamRuleEntity::class,
        BayesianTokenEntity::class,
        PrivateNoteEntity::class,
        CalendarEventEntity::class,
        CalendarEntity::class,
        CalendarRsvpEntity::class,
        FavoriteNoteEntity::class,
        PrivateContactsEntity::class,
        EmailOutboxEntity::class,
        SecurityAlertEntity::class,
        BadgeDefinitionEntity::class,
        BadgeAwardEntity::class,
        BadgePinEntity::class,
        BadgeNoticeEntity::class,
        EmailForwardLedgerEntity::class,
        ContactProfileLinkEntity::class,
        DomainFaviconEntity::class,
        MailFolderManifestEntity::class,
        MailStateEntity::class,
        MailShardStateEntity::class,
        UserFileEntity::class
    ],
    version = 57,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class NostrDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun followDao(): FollowDao
    abstract fun relayDao(): RelayDao
    abstract fun emailDao(): EmailDao
    abstract fun accountDao(): AccountDao
    abstract fun spamRuleDao(): SpamRuleDao
    abstract fun personalSpamRuleDao(): PersonalSpamRuleDao
    abstract fun bayesianTokenDao(): BayesianTokenDao
    abstract fun privateNoteDao(): PrivateNoteDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun calendarDao(): CalendarDao
    abstract fun calendarRsvpDao(): CalendarRsvpDao
    abstract fun favoriteNoteDao(): FavoriteNoteDao
    abstract fun privateContactsDao(): PrivateContactsDao
    abstract fun emailOutboxDao(): EmailOutboxDao
    abstract fun emailForwardLedgerDao(): EmailForwardLedgerDao
    abstract fun securityAlertDao(): SecurityAlertDao
    abstract fun badgeDao(): BadgeDao
    abstract fun badgeNoticeDao(): BadgeNoticeDao
    abstract fun contactProfileLinkDao(): ContactProfileLinkDao
    abstract fun domainFaviconDao(): DomainFaviconDao
    abstract fun mailFolderDao(): MailFolderDao
    abstract fun mailStateDao(): MailStateDao
    abstract fun userFileDao(): UserFileDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create wallet_balance table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS wallet_balance (
                        id INTEGER PRIMARY KEY NOT NULL,
                        balanceSats INTEGER NOT NULL,
                        lastUpdatedAt INTEGER NOT NULL
                    )
                """.trimIndent())

                // Create wallet_transactions table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS wallet_transactions (
                        invoice TEXT PRIMARY KEY NOT NULL,
                        type TEXT NOT NULL,
                        description TEXT,
                        amountMillisats INTEGER NOT NULL,
                        feesMillisats INTEGER,
                        settled INTEGER NOT NULL,
                        preimage TEXT,
                        paymentHash TEXT,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER,
                        metadataJson TEXT,
                        cachedAt INTEGER NOT NULL
                    )
                """.trimIndent())

                // Create indexes
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wallet_transactions_createdAt ON wallet_transactions(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wallet_transactions_settled ON wallet_transactions(settled)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN contentType TEXT NOT NULL DEFAULT 'TEXT'")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS presence (
                        npub TEXT PRIMARY KEY NOT NULL,
                        state TEXT NOT NULL,
                        lastSeenAt INTEGER NOT NULL,
                        lastUpdatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN relayListJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN sentToRelay TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE relays ADD COLUMN isWrite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    UPDATE relays SET isWrite = 1 WHERE url IN (
                        'wss://relay.desent.xyz',
                        'wss://nostr.ac',
                        'wss://relay.damus.io',
                        'wss://relay.primal.net'
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop the table if it exists from a failed migration attempt
                db.execSQL("DROP TABLE IF EXISTS emails")

                // Create the table fresh
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS emails (
                        id TEXT PRIMARY KEY NOT NULL,
                        recipientNpub TEXT NOT NULL,
                        senderEmail TEXT NOT NULL,
                        senderDomain TEXT,
                        subject TEXT NOT NULL,
                        content TEXT NOT NULL,
                        dkimStatus TEXT NOT NULL,
                        emailType TEXT NOT NULL,
                        bridge TEXT NOT NULL,
                        messageId TEXT,
                        threadToken TEXT,
                        createdAt INTEGER NOT NULL,
                        isRead INTEGER NOT NULL DEFAULT 0,
                        deletionRequested INTEGER NOT NULL DEFAULT 0,
                        deletionEventId TEXT
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS index_emails_recipient ON emails(recipientNpub)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_emails_createdAt ON emails(createdAt)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ensure email.desent.xyz is marked as write relay
                db.execSQL("""
                    UPDATE relays SET isWrite = 1 WHERE url = 'wss://email.desent.xyz'
                """.trimIndent())

                // Insert email.desent.xyz if it doesn't exist
                db.execSQL("""
                    INSERT OR IGNORE INTO relays (url, isActive, connectionStatus, failureCount, lastConnectedAt, isWrite, createdAt)
                    VALUES ('wss://email.desent.xyz', 1, 'DISCONNECTED', 0, NULL, 1, ${System.currentTimeMillis()})
                """.trimIndent())
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add NIP-11 metadata columns
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11Name TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11Description TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11Pubkey TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11Contact TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11SupportedNips TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11Version TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11Icon TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11Software TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11RelayCountries TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11LanguageTags TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11PostingPolicy TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11LimitationsJson TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11FeesJson TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11Payments TEXT")
                db.execSQL("ALTER TABLE relays ADD COLUMN nip11CachedAt INTEGER")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE relays ADD COLUMN isPersistent INTEGER NOT NULL DEFAULT 1")
                // Mark the two DeSent relays as persistent, all others as temporary
                db.execSQL("""
                    UPDATE relays SET isPersistent = 0
                    WHERE url NOT IN ('wss://relay.desent.xyz', 'wss://email.desent.xyz')
                """.trimIndent())
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN seenOnRelays TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT NOT NULL DEFAULT 'SENT'")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Email rumor tag expansion (see refs/RUMOR_TAG_REFERENCE.md).
                // All new columns are nullable/defaulted so older rows stay valid.
                db.execSQL("ALTER TABLE emails ADD COLUMN senderName TEXT")
                db.execSQL("ALTER TABLE emails ADD COLUMN replyTo TEXT")
                db.execSQL("ALTER TABLE emails ADD COLUMN spfStatus TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE emails ADD COLUMN inReplyTo TEXT")
                db.execSQL("ALTER TABLE emails ADD COLUMN alias TEXT")
                db.execSQL("ALTER TABLE emails ADD COLUMN attachmentsJson TEXT")
                db.execSQL("ALTER TABLE emails ADD COLUMN senderDate INTEGER")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // NIP-01 profile banner support.
                db.execSQL("ALTER TABLE users ADD COLUMN banner TEXT")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // NIP-01 profile extras: website + Lightning address (lud06/lud16).
                db.execSQL("ALTER TABLE users ADD COLUMN website TEXT")
                db.execSQL("ALTER TABLE users ADD COLUMN lud06 TEXT")
                db.execSQL("ALTER TABLE users ADD COLUMN lud16 TEXT")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // NIP-17 group chat support.
                db.execSQL("ALTER TABLE messages ADD COLUMN conversationId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN subject TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN isGroup INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN membersJson TEXT")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Email reply: store the thread sender keypair (seal.pubkey) for addressing replies.
                db.execSQL("ALTER TABLE emails ADD COLUMN threadSenderPubkey TEXT")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Re-target public events (kind 0 / 30315 / 10002) at the
                // PUBLIC_PROFILE_RELAYS set: email.desent.xyz, relay.primal.net,
                // relay.damus.io. relay.desent.xyz stays persistent (DMs) but is
                // dropped from the write set. DM/email traffic is unchanged.
                val now = System.currentTimeMillis()

                db.execSQL("""
                    INSERT OR IGNORE INTO relays (url, isActive, connectionStatus, failureCount,
                        lastConnectedAt, isWrite, isPersistent, createdAt)
                    VALUES ('wss://relay.primal.net', 1, 'DISCONNECTED', 0, NULL, 1, 1, $now)
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO relays (url, isActive, connectionStatus, failureCount,
                        lastConnectedAt, isWrite, isPersistent, createdAt)
                    VALUES ('wss://relay.damus.io', 1, 'DISCONNECTED', 0, NULL, 1, 1, $now)
                """.trimIndent())

                db.execSQL("""
                    UPDATE relays SET isPersistent = 1, isWrite = 1
                    WHERE url IN ('wss://relay.primal.net', 'wss://relay.damus.io')
                """.trimIndent())

                // relay.desent.xyz remains persistent (DMs) but no longer receives
                // public profile/status/relay-list publishes.
                db.execSQL("UPDATE relays SET isWrite = 0 WHERE url = 'wss://relay.desent.xyz'")
            }
        }

        /**
         * v20 → v21: multi-account support.
         *
         * Adds the `accounts` table (the source of truth for saved accounts) and
         * an `ownerNpub` column to the previously-global tables so each account
         * owns its own automation rules, status history/templates, and wallet
         * data. The `wallet_balance` table is rebuilt to key by `ownerNpub`
         * instead of the legacy singleton `id = 1`.
         *
         * `ownerNpub` is added with DEFAULT '' here; the active account's npub
         * is back-filled in a Kotlin post-migration step
         * ([xyz.desent.data.repository.AccountRepositoryImpl.seedFromLegacySingleAccount])
         * that reads the legacy single-slot nsec/npub and stamps every
         * still-empty `ownerNpub` row.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. New accounts table.
                //    Use the exact column quoting + separate PRIMARY KEY clause
                //    that Room 2.6.1 generates; otherwise Room's schema
                //    validation (identity-hash comparison) will reject the
                //    migration at runtime.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `accounts` (" +
                        "`npub` TEXT NOT NULL, " +
                        "`displayName` TEXT, " +
                        "`picture` TEXT, " +
                        "`nip05` TEXT, " +
                        "`requiresBiometrics` INTEGER NOT NULL, " +
                        "`lastActiveAt` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`npub`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_accounts_lastActiveAt` " +
                        "ON `accounts`(`lastActiveAt`)"
                )

                // 2. automation_rules: add ownerNpub + index.
                db.execSQL(
                    "ALTER TABLE automation_rules ADD COLUMN ownerNpub TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_automation_rules_ownerNpub` " +
                        "ON `automation_rules`(`ownerNpub`)"
                )

                // 3. status_history: add ownerNpub + composite index for content lookups.
                //    The index name MUST match the custom name declared in
                //    StatusHistoryEntity (idx_, not index_).
                db.execSQL(
                    "ALTER TABLE status_history ADD COLUMN ownerNpub TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_status_history_owner_content` " +
                        "ON `status_history`(`ownerNpub`, `content`)"
                )

                // 4. status_templates: add ownerNpub + index.
                db.execSQL(
                    "ALTER TABLE status_templates ADD COLUMN ownerNpub TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_status_templates_ownerNpub` " +
                        "ON `status_templates`(`ownerNpub`)"
                )

                // 5. wallet_balance: rebuild keyed by ownerNpub (was singleton id=1).
                //    CREATE TABLE format must match Room's generated SQL exactly
                //    (backtick-quoted columns, separate PRIMARY KEY clause).
                db.execSQL("ALTER TABLE wallet_balance RENAME TO wallet_balance_old")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wallet_balance` (" +
                        "`ownerNpub` TEXT NOT NULL, " +
                        "`balanceSats` INTEGER NOT NULL, " +
                        "`lastUpdatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ownerNpub`))"
                )
                db.execSQL(
                    "INSERT INTO wallet_balance (ownerNpub, balanceSats, lastUpdatedAt) " +
                        "SELECT '', balanceSats, lastUpdatedAt FROM wallet_balance_old"
                )
                db.execSQL("DROP TABLE wallet_balance_old")

                // 6. wallet_transactions: add ownerNpub + per-owner indices.
                //    The v20 schema declared global indices on (createdAt) and
                //    (settled) which the v21 entity no longer declares. Room 2.6.1
                //    validates the index SET exactly, so the old indices must be
                //    explicitly dropped — otherwise the migrated schema will have
                //    extra indices that cause an identity-hash mismatch.
                db.execSQL(
                    "ALTER TABLE wallet_transactions ADD COLUMN ownerNpub TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL("DROP INDEX IF EXISTS `index_wallet_transactions_createdAt`")
                db.execSQL("DROP INDEX IF EXISTS `index_wallet_transactions_settled`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wallet_transactions_ownerNpub` " +
                        "ON `wallet_transactions`(`ownerNpub`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wallet_transactions_ownerNpub_createdAt` " +
                        "ON `wallet_transactions`(`ownerNpub`, `createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wallet_transactions_ownerNpub_settled` " +
                        "ON `wallet_transactions`(`ownerNpub`, `settled`)"
                )
            }
        }

        /**
         * v21 → v22: per-account Nostr Wallet Connect URL.
         *
         * The NWC connection URI (which embeds the wallet relay + client secret)
         * previously lived in a single global DataStore key, shared across all
         * accounts. It now lives on each account row so each account remembers
         * its own wallet.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN nwcUrl TEXT")
            }
        }

        /**
         * v22 → v23: client-side spam filter (see refs/SPAM_FILTER_REFERENCE.md).
         *
         * Adds the stamped verdict columns to `emails` (defaulting every existing
         * row to not-spam; the first-run [xyz.desent.domain.usecase.ReclassifyInboxUseCase]
         * back-fills real verdicts) and two new tables:
         *  - `spam_rules`   — single-row cache of the NIP-51 manifest
         *                     (refs/SPAM_LIST_REFERENCE.md)
         *  - `bayesian_tokens` — per-account SHA-256-hashed token counts for the
         *                     self-learning Layer 3 classifier
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Stamped verdict columns on emails (existing rows → ham).
                db.execSQL("ALTER TABLE emails ADD COLUMN spamScore REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE emails ADD COLUMN isSpam INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE emails ADD COLUMN spamReasons TEXT")

                // 2. spam_rules — singleton cache (id = 0).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `spam_rules` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`version` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`ttlHours` INTEGER NOT NULL, " +
                        "`blockedDomains` TEXT NOT NULL, " +
                        "`allowedDomains` TEXT NOT NULL, " +
                        "`blockedSenders` TEXT NOT NULL, " +
                        "`spamPatterns` TEXT NOT NULL, " +
                        "`trustedBridgeDomains` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )

                // 3. bayesian_tokens — hashed token counts keyed by ownerNpub.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bayesian_tokens` (" +
                        "`tokenHash` TEXT NOT NULL, " +
                        "`ownerNpub` TEXT NOT NULL, " +
                        "`spamCount` INTEGER NOT NULL, " +
                        "`hamCount` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`tokenHash`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bayesian_tokens_ownerNpub` " +
                        "ON `bayesian_tokens`(`ownerNpub`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_emails_isSpam` ON `emails`(`isSpam`)"
                )
            }
        }

        /**
         * v23 → v24: NIP-78 private storage (refs/PRIVATE_STORAGE_PROTOCOL.md).
         *
         * Two new user-scoped tables:
         *  - `private_notes`   — one row per encrypted note (kind 30078,
         *    `d = "desent:note:<uuid>"`), keyed by the note uuid.
         *  - `private_contacts` — single row per account caching the global
         *    encrypted email address book (kind 30078, `d = "desent:contacts"`).
         *
         * Both follow the multi-account `ownerNpub` convention (see v20→v21).
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `private_notes` (" +
                        "`id` TEXT NOT NULL, " +
                        "`ownerNpub` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`body` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`attachmentsJson` TEXT NOT NULL, " +
                        "`dTag` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_private_notes_ownerNpub` " +
                        "ON `private_notes`(`ownerNpub`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `private_contacts` (" +
                        "`ownerNpub` TEXT NOT NULL, " +
                        "`contactsJson` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`dTag` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ownerNpub`))"
                )
            }
        }

        /**
         * v24 → v25: add `folder` column to `private_notes`
         * (refs/PRIVATE_STORAGE_PROTOCOL.md). The note payload now carries an
         * optional slash-separated folder path; `attachmentsJson` keeps the
         * same TEXT column (its JSON content shape moved from `[sha,…]` to the
         * rich per-attachment object form, which needs no DDL change).
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `private_notes` ADD COLUMN `folder` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v25 → v26: device-local "favorite note" pins.
         *
         * Lets a user star private notes (for the Notes home-screen widget and
         * quick access) without polluting the NIP-78 payload — favorite state
         * is intentionally device-local and survives inbound REPLACE re-syncs.
         * Keyed by (ownerNpub, noteId) for multi-account scoping.
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorite_notes` (" +
                        "`ownerNpub` TEXT NOT NULL, " +
                        "`noteId` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ownerNpub`, `noteId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_favorite_notes_ownerNpub` " +
                        "ON `favorite_notes`(`ownerNpub`)"
                )
            }
        }

        /**
         * v26 → v27: encrypted Nostr calendar (refs/CALENDAR_PROTOCOL.md).
         *
         * One new user-scoped table:
         *  - `calendar_events` — one row per decrypted NIP-52 calendar event
         *    (kind 31922 / 31923, `d = "desent:event:<id>"`), keyed by the event
         *    uuid. Denormalized `startSec` / `kind` / `title` columns let the
         *    relay-blind time-range queries run locally.
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `calendar_events` (" +
                        "`id` TEXT NOT NULL, " +
                        "`ownerNpub` TEXT NOT NULL, " +
                        "`kind` INTEGER NOT NULL, " +
                        "`dTag` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`startSec` INTEGER NOT NULL, " +
                        "`endSec` INTEGER, " +
                        "`calendarD` TEXT, " +
                        "`payloadJson` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_calendar_events_ownerNpub` " +
                        "ON `calendar_events`(`ownerNpub`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_calendar_events_startSec` " +
                        "ON `calendar_events`(`startSec`)"
                )
            }
        }

        /**
         * v27 → v28: NIP-52 calendar collections (kind 31924).
         *
         * One new user-scoped table:
         *  - `calendars` — one row per decrypted calendar collection
         *    (`d = "desent:calendar:<id>"`), keyed by the calendar uuid. The
         *    full membership list (`event_ds`) + shares live inside the
         *    encrypted payloadJson.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `calendars` (" +
                        "`id` TEXT NOT NULL, " +
                        "`ownerNpub` TEXT NOT NULL, " +
                        "`dTag` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`color` TEXT, " +
                        "`payloadJson` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_calendars_ownerNpub` " +
                        "ON `calendars`(`ownerNpub`)"
                )
            }
        }

        /**
         * v28 → v29: calendar RSVP cache (kind 31925 gift-wrap notifications).
         *
         * Stores RSVP responses the organizer receives via
         * `["bridge","calendar"]/type=rsvp` gift wraps. Composite PK on
         * (owner, event, sender) so an invitee's latest status replaces prior
         * ones. (The invitee's durable RSVP is a 31925 in their own storage;
         * this table is just the organizer's local view of notifications.)
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `calendar_rsvps` (" +
                        "`ownerNpub` TEXT NOT NULL, " +
                        "`eventD` TEXT NOT NULL, " +
                        "`senderPubkey` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`freebusy` TEXT, " +
                        "`note` TEXT, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ownerNpub`, `eventD`, `senderPubkey`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_calendar_rsvps_ownerNpub` " +
                        "ON `calendar_rsvps`(`ownerNpub`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_calendar_rsvps_eventD` " +
                        "ON `calendar_rsvps`(`eventD`)"
                )
            }
        }

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // relay.desent.xyz is decommissioned — DM + P2P signaling traffic
                // moves to chat.desent.xyz. nostr.ac is removed entirely (it was
                // also the Blossom image-upload host; uploads move to chat.desent.xyz).
                val now = System.currentTimeMillis()

                db.execSQL("DELETE FROM relays WHERE url IN ('wss://relay.desent.xyz', 'wss://nostr.ac')")

                db.execSQL(
                    """
                    INSERT OR IGNORE INTO relays (url, isActive, connectionStatus, failureCount,
                        lastConnectedAt, isWrite, isPersistent, createdAt)
                    VALUES ('wss://chat.desent.xyz', 1, 'DISCONNECTED', 0, NULL, 1, 1, $now)
                    """.trimIndent()
                )

                // Any queued/pending messages still addressed to the dead relay are
                // cleared so the send-retry path doesn't keep trying a dead host.
                db.execSQL(
                    "UPDATE messages SET sentToRelay = REPLACE(sentToRelay, 'wss://relay.desent.xyz', 'wss://chat.desent.xyz') " +
                        "WHERE sentToRelay LIKE '%wss://relay.desent.xyz%'"
                )
            }
        }

        /**
         * Introduces the `user_relays` table — a typed, queryable store of the
         * per-user relay lists Nostr's NIPs define (NIP-65 read/write,
         * NIP-17 DM relays, NIP-51 search relays).
         *
         * The legacy `users.relayListJson` JSON blob is intentionally NOT
         * migrated here: SQLite's JSON support is inconsistent across the
         * platform's bundled versions, and the own user's NIP-65 list is
         * re-fetched at login (`NostrRepository.fetchUserMetadata` subscribes
         * to kinds [0, 10002]), which repopulates the typed table. Until that
         * refresh lands, callers fall back to the always-connected DeSent
         * service relays. The JSON column stays in place for backward
         * compatibility with existing consumers during the transition.
         */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_relays` (
                        `pubkey` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `sourceKind` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`pubkey`, `url`, `role`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_relays_pubkey` ON `user_relays`(`pubkey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_relays_role` ON `user_relays`(`role`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_relays_sourceKind` ON `user_relays`(`sourceKind`)"
                )
            }
        }

        /**
         * Adds the three Cashu (NIP-60) wallet tables — wallet config, unspent
         * ecash proofs, and append-only spending history. See
         * refs/CASHU_WALLET_PROTOCOL.md. All three are owner-scoped (keyed by
         * `ownerNpub`); Nostr-side content is NIP-44 self-encrypted.
         */
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cashu_wallet_config` (
                        `ownerNpub` TEXT NOT NULL,
                        `mintsJson` TEXT NOT NULL,
                        `walletPrivKeyHex` TEXT NOT NULL,
                        `unit` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerNpub`)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cashu_proofs` (
                        `ownerNpub` TEXT NOT NULL,
                        `secret` TEXT NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `keysetId` TEXT NOT NULL,
                        `C` TEXT NOT NULL,
                        `mint` TEXT NOT NULL,
                        `unit` TEXT NOT NULL,
                        `eventId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerNpub`, `secret`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cashu_proofs_ownerNpub` ON `cashu_proofs`(`ownerNpub`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cashu_proofs_ownerNpub_mint` ON `cashu_proofs`(`ownerNpub`, `mint`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cashu_history` (
                        `ownerNpub` TEXT NOT NULL,
                        `eventId` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `unit` TEXT NOT NULL,
                        `memo` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerNpub`, `eventId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cashu_history_ownerNpub_createdAt` ON `cashu_history`(`ownerNpub`, `createdAt`)"
                )
            }
        }

        /**
         * v32 → v33: NIP-02 petname preservation on follows.
         *
         * The `follows` table caches the parsed `["p", hex, relayUrl?, petname?]`
         * tags from kind 3 contact-list events. Until now only the hex pubkey
         * (param 0) was kept; the petname (param 2) was discarded, so republishing
         * a contact list would silently strip every petname. This adds a nullable
         * `petname` column so [xyz.desent.data.nostr.NostrEventProcessor] can store
         * the inbound petname and [xyz.desent.data.repository.NostrRepository]'s
         * kind 3 publish path can round-trip it. Relay hints (param 1) are still
         * discarded — they are advisory and the local pool is the source of truth.
         */
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE follows ADD COLUMN petname TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN nip05Verified INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v34 → v35: NIP-EMAIL (kind 1010). Threading becomes client-owned
         * (RFC 5322 `in_reply_to`/`references`), confirmations become
         * `direction: delivery-receipt` messages matched by `to` + `subject`
         * + time, and outbound messages are stored locally as thread members.
         *
         * - `emails`: new nullable columns `dmarcStatus`, `referencesHeader`,
         *   `threadRoot` (computed thread grouping key), `toEmail`,
         *   `direction`, `bodyFormat` (`["format", …]` tag; null = legacy
         *   kind-14 row, rendered as HTML). Legacy rows keep rendering:
         *   `threadRoot` stays NULL so the DAO's `COALESCE(threadRoot,
         *   threadToken, id)` grouping is unchanged for them, and new replies
         *   to legacy threads resolve to the old `threadToken` key.
         * - `email_outbox`: pending outbound entries used to correlate the
         *   relay's delivery-receipt (no correlation id exists on the wire).
         */
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE emails ADD COLUMN dmarcStatus TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE emails ADD COLUMN referencesHeader TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE emails ADD COLUMN threadRoot TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE emails ADD COLUMN toEmail TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE emails ADD COLUMN direction TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE emails ADD COLUMN bodyFormat TEXT DEFAULT NULL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `email_outbox` (
                        `messageId` TEXT NOT NULL PRIMARY KEY,
                        `recipientNpub` TEXT NOT NULL,
                        `threadKey` TEXT NOT NULL,
                        `fromAlias` TEXT NOT NULL,
                        `toEmail` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `sentAt` INTEGER NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'PENDING',
                        `receiptEventId` TEXT,
                        `resolvedAt` INTEGER
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v35 → v36: production domain flip — `email.desent.xyz` is
         * decommissioned (hard cutover) and everything it hosted (email-bridge
         * relay, blob/alias/message/attachments APIs, NIP-46 bunker) now lives
         * on the apex `desent.xyz`. Mirrors MIGRATION_29_30.
         *
         * - `relays`: rewrite stored rows so existing installs keep their
         *   isWrite/isPersistent settings; insert the new row for DBs that
         *   never had the old one.
         * - `messages`: retarget queued sends still addressed to the dead
         *   host so the send-retry path doesn't loop on DNS failures.
         * - `user_relays`: rewrite cached per-user relay hints; OR REPLACE
         *   drops the loser if both old and new rows exist for the same
         *   (pubkey, url, role) key. Content is re-fetched at login anyway.
         *
         * `chat.desent.xyz` (DM home relay) is untouched.
         */
        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // OR REPLACE: if a 'wss://desent.xyz' row already exists (e.g.
                // added manually pre-flip), a plain UPDATE would violate the
                // url primary key and abort the migration — crash-looping the
                // app, since there is no destructive fallback. REPLACE lets
                // the rewritten row win instead.
                db.execSQL(
                    "UPDATE OR REPLACE relays SET url = 'wss://desent.xyz' WHERE url = 'wss://email.desent.xyz'"
                )

                db.execSQL(
                    """
                    INSERT OR IGNORE INTO relays (url, isActive, connectionStatus, failureCount,
                        lastConnectedAt, isWrite, isPersistent, createdAt)
                    VALUES ('wss://desent.xyz', 1, 'DISCONNECTED', 0, NULL, 1, 1, ${System.currentTimeMillis()})
                    """.trimIndent()
                )

                db.execSQL(
                    "UPDATE messages SET sentToRelay = REPLACE(sentToRelay, 'wss://email.desent.xyz', 'wss://desent.xyz') " +
                        "WHERE sentToRelay LIKE '%wss://email.desent.xyz%'"
                )

                db.execSQL(
                    "UPDATE OR REPLACE user_relays SET url = 'wss://desent.xyz' WHERE url = 'wss://email.desent.xyz'"
                )
            }
        }

        /**
         * v36 → v37: login-security alerts (refs/FromServer/ANDROID_SECURITY_ALERTS.md).
         *
         * One new user-scoped table:
         *  - `security_alerts` — one row per stored kind-1010 `direction:
         *    "security"` gift wrap (relay-sealed). Deliberately separate from
         *    `emails` so alerts never render as ordinary inbox mail and are
         *    never stamped by the spam filter. Local rows mirror the relay's
         *    fixed 30-day NIP-40 expiration via SecurityAlertDao.purgeOlderThan.
         */
        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `security_alerts` (
                        `eventId` TEXT NOT NULL,
                        `ownerNpub` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `surface` TEXT NOT NULL,
                        `time` TEXT NOT NULL,
                        `ip` TEXT,
                        `geo` TEXT,
                        `ua` TEXT,
                        `device` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `isSeen` INTEGER NOT NULL,
                        PRIMARY KEY(`eventId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_security_alerts_ownerNpub` " +
                        "ON `security_alerts`(`ownerNpub`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_security_alerts_receivedAt` " +
                        "ON `security_alerts`(`receivedAt`)"
                )
            }
        }

        /**
         * v37 → v38: client-custodied accounts (refs/FromServer/CUSTODIAL_ACCOUNTS.md).
         *
         * One new nullable column on `accounts`:
         *  - `custodialUsername` — the username of the password-based login
         *    that provisioned the account on this device. Null for key-import
         *    accounts; non-null enables the Settings "change password" entry.
         */
        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `accounts` ADD COLUMN `custodialUsername` TEXT"
                )
            }
        }

        /**
         * v38 → v39: recurring calendar events.
         *
         * One new nullable column on `calendar_events`:
         *  - `recurFreq` — denormalized `Recurrence.freq` wire value
         *    (DAILY/WEEKLY/MONTHLY/YEARLY); null = one-off event. Lets the
         *    expansion path select only recurring rows via
         *    `WHERE recurFreq IS NOT NULL`. The full rule (interval, BYDAY,
         *    UNTIL/COUNT) lives inside the encrypted `payloadJson`, so no
         *    plaintext is added and existing rows are unaffected.
         *
         * Occurrences are never materialized — they are expanded client-side
         * at read time (see RecurringEventExpander), so recurrence adds zero
         * storage growth per occurrence.
         */
        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `calendar_events` ADD COLUMN `recurFreq` TEXT")
            }
        }

        /**
         * v39 → v40: NIP-58 badges (refs/FROM_email.desent.xyz/BADGES_PROTOCOL.md).
         *
         * Three new tables, all fed from the DeSent relay only:
         *  - `badge_definitions` — kind 30009 cache, one row per badge slug
         *    (relay-authored; retirement deletes the row).
         *  - `badge_awards` — kind 8 cache keyed by award event id (the `e`
         *    value of pin lists; revocation = kind 5 deletes the row).
         *  - `badge_pins` — the user's kind 30008 pin list, replaced wholesale
         *    on every publish/echo; PK (ownerNpub, awardEventId).
         */
        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `badge_definitions` (
                        `slug` TEXT NOT NULL,
                        `eventId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT,
                        `imageUrl` TEXT,
                        `thumbUrl` TEXT,
                        `iconName` TEXT,
                        `color` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`slug`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_badge_definitions_eventId` " +
                        "ON `badge_definitions`(`eventId`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `badge_awards` (
                        `eventId` TEXT NOT NULL,
                        `awardeeNpub` TEXT NOT NULL,
                        `slug` TEXT NOT NULL,
                        `definitionAddress` TEXT NOT NULL,
                        `awardedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`eventId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_badge_awards_awardeeNpub` " +
                        "ON `badge_awards`(`awardeeNpub`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_badge_awards_slug` " +
                        "ON `badge_awards`(`slug`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `badge_pins` (
                        `ownerNpub` TEXT NOT NULL,
                        `awardEventId` TEXT NOT NULL,
                        `slug` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerNpub`, `awardEventId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_badge_pins_ownerNpub` " +
                        "ON `badge_pins`(`ownerNpub`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_badge_pins_awardEventId` " +
                        "ON `badge_pins`(`awardEventId`)"
                )
            }
        }

        /**
         * v40 → v41: badge-award notices tray.
         *
         * One new user-scoped table, `badge_notices` — one row per stored
         * relay-sealed kind-1010 `direction: "badge"` gift wrap
         * (refs/FromServer/ANDROID_BADGES.md §7). Same shape and lifecycle
         * as `security_alerts` (v36→v37): IGNORE-insert dedup by wrap event
         * id makes the once-per-award OS notification idempotent across
         * login re-downloads of the relay's 30-day backlog, `isSeen` powers
         * the tray's unseen state, and rows purge on the same 30-day clock.
         */
        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `badge_notices` (
                        `eventId` TEXT NOT NULL,
                        `ownerNpub` TEXT NOT NULL,
                        `slug` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `isSeen` INTEGER NOT NULL,
                        PRIMARY KEY(`eventId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_badge_notices_ownerNpub` " +
                        "ON `badge_notices`(`ownerNpub`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_badge_notices_receivedAt` " +
                        "ON `badge_notices`(`receivedAt`)"
                )
            }
        }

        /**
         * v41 → v42: the proper email Outbox.
         *
         * `email_outbox` grows the fields a retry and the Outbox screen need:
         *  - `inReplyTo` / `referencesHeader` / `bodyFormat` — rebuild the
         *    outbound rumor verbatim on a one-tap retry;
         *  - `errorMessage` — the ❌ receipt's failure reason;
         *  - `giftWrapEventId` — our publish's event id;
         *  - `isSynthetic` — entries reconstructed from a receipt for a send
         *    this device never made (shown in the Outbox, not retryable).
         *
         * Alongside the schema change, the receipt pipeline stops inserting
         * bridge receipts as inbox mail (they resolve outbox state instead),
         * and PENDING entries that never hear back flip to TIMED_OUT via the
         * reconcile sweep — see DeliveryReceiptHandler + EmailRepositoryImpl.
         */
        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `email_outbox` ADD COLUMN `inReplyTo` TEXT")
                db.execSQL("ALTER TABLE `email_outbox` ADD COLUMN `referencesHeader` TEXT")
                db.execSQL(
                    "ALTER TABLE `email_outbox` ADD COLUMN `bodyFormat` TEXT NOT NULL DEFAULT 'PLAIN'"
                )
                db.execSQL("ALTER TABLE `email_outbox` ADD COLUMN `errorMessage` TEXT")
                db.execSQL("ALTER TABLE `email_outbox` ADD COLUMN `giftWrapEventId` TEXT")
                db.execSQL(
                    "ALTER TABLE `email_outbox` ADD COLUMN `isSynthetic` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v42 → v43: NIP-EMAIL forwarding / mailbox migration.
         *
         * - `emails.forwardedByNpub` — provenance for mail re-delivered by
         *   another Nostr key (the kind-1010 `forwarded_by` tag); null on
         *   ordinary relay-delivered mail.
         * - `email_forward_ledger` — one row per (email, target) forward
         *   attempt. SENT rows make bulk runs idempotent/resumable; FAILED
         *   rows carry the reason for the retry UI.
         */
        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `emails` ADD COLUMN `forwardedByNpub` TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `email_forward_ledger` (
                        `emailId` TEXT NOT NULL,
                        `targetNpub` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `giftWrapEventId` TEXT,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `resolvedAt` INTEGER,
                        PRIMARY KEY(`emailId`, `targetNpub`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_email_forward_ledger_targetNpub` " +
                        "ON `email_forward_ledger`(`targetNpub`)"
                )
            }
        }

        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Message reactions (NIP-25-style emoji riding NIP-17 kind-14
                // gift wraps). One per author per message (unique index).
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reactions` (
                        `id` TEXT NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `emoji` TEXT NOT NULL,
                        `authorNpub` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reactions_messageId` ON `reactions`(`messageId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_reactions_messageId_authorNpub` " +
                        "ON `reactions`(`messageId`, `authorNpub`)"
                )
            }
        }

        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // NIP-17 group room metadata: persisted member roster so rooms
                // exist before their first message, survive process death, and
                // appear in the conversation roster.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_conversations` (
                        `conversationId` TEXT NOT NULL,
                        `ownerNpub` TEXT NOT NULL,
                        `subject` TEXT,
                        `membersJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastActivityAt` INTEGER NOT NULL,
                        PRIMARY KEY(`conversationId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_group_conversations_ownerNpub` " +
                        "ON `group_conversations`(`ownerNpub`)"
                )
                // Backfill rooms for existing group messages. Every stored group
                // row belongs to the local account (receiverNpub is always the
                // local user — both sent copies and received wraps).
                db.execSQL(
                    """
                    INSERT INTO group_conversations
                        (conversationId, ownerNpub, subject, membersJson, createdAt, lastActivityAt)
                    SELECT conversationId, receiverNpub, MAX(subject), membersJson,
                           MIN(createdAt), MAX(createdAt)
                    FROM messages
                    WHERE isGroup = 1 AND conversationId IS NOT NULL AND membersJson IS NOT NULL
                    GROUP BY conversationId, receiverNpub
                    """.trimIndent()
                )
            }
        }

        /**
         * v45 → v46: favorite-only follow rows.
         *
         * `follows.isLocalOnly` marks device-local rows created by favoriting
         * a user the account doesn't actually follow
         * ([xyz.desent.data.repository.FollowRepositoryImpl.toggleFavorite]).
         * These rows must never be published to the Nostr kind-3 contact
         * list, never appear as "my follows", and are deleted when the
         * favorite is removed. Existing rows default to 0 (real follows).
         */
        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `follows` ADD COLUMN `isLocalOnly` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v46 → v47: drop the tables of the removed features — direct messages
         * (`messages`, `queued_messages`, `reactions`, `group_conversations`)
         * and the wallets (`wallet_balance`, `wallet_transactions`, and the
         * NIP-60 Cashu tables) — plus the per-account `accounts.nwcUrl`
         * column (NWC wallet removal) and the NIP-17 DM rows in `user_relays`
         * (kind-10050 handling is gone; READ/WRITE/SEARCH rows survive).
         *
         * SQLite has no DROP COLUMN before 3.35 / API 35, so `accounts` is
         * rebuilt without the column, copying the surviving fields over.
         */
        val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `messages`")
                db.execSQL("DROP TABLE IF EXISTS `queued_messages`")
                db.execSQL("DROP TABLE IF EXISTS `reactions`")
                db.execSQL("DROP TABLE IF EXISTS `group_conversations`")
                db.execSQL("DROP TABLE IF EXISTS `wallet_balance`")
                db.execSQL("DROP TABLE IF EXISTS `wallet_transactions`")
                db.execSQL("DROP TABLE IF EXISTS `cashu_wallet_config`")
                db.execSQL("DROP TABLE IF EXISTS `cashu_proofs`")
                db.execSQL("DROP TABLE IF EXISTS `cashu_history`")

                db.execSQL(
                    "CREATE TABLE `accounts_new` (" +
                        "`npub` TEXT NOT NULL, " +
                        "`displayName` TEXT, " +
                        "`picture` TEXT, " +
                        "`nip05` TEXT, " +
                        "`requiresBiometrics` INTEGER NOT NULL DEFAULT 0, " +
                        "`lastActiveAt` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "`custodialUsername` TEXT, " +
                        "PRIMARY KEY(`npub`))"
                )
                db.execSQL(
                    "INSERT INTO `accounts_new` (`npub`, `displayName`, `picture`, `nip05`, " +
                        "`requiresBiometrics`, `lastActiveAt`, `addedAt`, `custodialUsername`) " +
                        "SELECT `npub`, `displayName`, `picture`, `nip05`, `requiresBiometrics`, " +
                        "`lastActiveAt`, `addedAt`, `custodialUsername` FROM `accounts`"
                )
                db.execSQL("DROP TABLE `accounts`")
                db.execSQL("ALTER TABLE `accounts_new` RENAME TO `accounts`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_accounts_lastActiveAt` ON `accounts`(`lastActiveAt`)"
                )

                db.execSQL("DELETE FROM `user_relays` WHERE `role` = 'DM'")
            }
        }

        /**
         * v47 → v48: drop the tables of the removed features — NIP-38 statuses
         * (`statuses`, `status_history`, `status_templates`), status automation
         * (`automation_rules`, `selected_calendars`), presence (`presence`)
         * and the per-user relay lists (`user_relays`) — and prune the
         * connection pool so only
         * `wss://desent.xyz` remains (the app no longer publishes or syncs
         * through any other relay).
         */
        val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `statuses`")
                db.execSQL("DROP TABLE IF EXISTS `status_history`")
                db.execSQL("DROP TABLE IF EXISTS `status_templates`")
                db.execSQL("DROP TABLE IF EXISTS `automation_rules`")
                db.execSQL("DROP TABLE IF EXISTS `presence`")
                db.execSQL("DROP TABLE IF EXISTS `user_relays`")
                db.execSQL("DROP TABLE IF EXISTS `selected_calendars`")

                db.execSQL("DELETE FROM `relays` WHERE `url` != 'wss://desent.xyz'")
            }
        }

        /**
         * Cached primary `<local>@desent.xyz` address per account, fetched
         * from `GET /api/me` at launch/login/switch. A dedicated column —
         * not `accounts.nip05` — because the switcher's joined query
         * COALESCEs the kind-0 `users.nip05` over it, and an externally
         * published nip05 must never shadow the registered address.
         */
        val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `primaryAddress` TEXT")
            }
        }

        /**
         * v49 → v50: durable NIP-05 identifier → pubkey resolutions for
         * contact-profile enrichment (see ContactProfileResolverImpl), so an
         * app open doesn't re-fetch `.well-known/nostr.json` for email-only
         * contacts.
         */
        val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `contact_profile_links` (
                        `identifier` TEXT PRIMARY KEY NOT NULL,
                        `hexPubkey` TEXT NOT NULL,
                        `resolvedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         * v50 → v51: per-domain favicon availability cache for email-sender
         * avatar fallbacks (see FaviconResolver) — known icon-less domains
         * are skipped instead of re-probed on every open.
         */
        val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `domain_favicons` (
                        `domain` TEXT PRIMARY KEY NOT NULL,
                        `available` INTEGER NOT NULL,
                        `checkedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         * v51 → v52: PGP end-to-end encryption (ANDROID_PGP.md) — mark
         * armored PGP messages on `emails` and PGP sends on `email_outbox`
         * (retry re-encrypts from the stored plaintext draft).
         */
        val MIGRATION_51_52 = object : Migration(51, 52) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `emails` ADD COLUMN `isPgpEncrypted` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `email_outbox` ADD COLUMN `isPgpEncrypted` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v52 → v53: mail folders + synced read state
         * (refs/FROM_email.desent.xyz/ANDROID_MAIL_FOLDERS.md). The overlay
         * rides on kind-30078 `desent:mail-folders` /
         * `desent:mail-state:<i>`; the folder manifest is cached verbatim
         * (round-trip rule for unknown keys), per-message state is a
         * standalone LWW table so entries survive ahead of the mail they
         * reference, and `mail_shard_state` tracks dirty/published shards
         * for the debounced flush. The pinned message key is derived from
         * existing `emails` columns — no backfill needed.
         */
        val MIGRATION_52_53 = object : Migration(52, 53) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `mail_folder_manifest` (
                        `ownerNpub` TEXT NOT NULL,
                        `manifestJson` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `dTag` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerNpub`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `mail_state` (
                        `ownerNpub` TEXT NOT NULL,
                        `folderKey` TEXT NOT NULL,
                        `folderId` TEXT,
                        `isRead` INTEGER NOT NULL,
                        `ts` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerNpub`, `folderKey`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `mail_shard_state` (
                        `ownerNpub` TEXT NOT NULL,
                        `shardIndex` INTEGER NOT NULL,
                        `dirty` INTEGER NOT NULL,
                        `published` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerNpub`, `shardIndex`)
                    )
                """.trimIndent())
            }
        }

        /**
         * Personal spam rules (user's own mark-as-spam / not-spam feedback),
         * kept per-account and separate from the global NIP-51 manifest so
         * they can sync privately via the NIP-78 spam-settings payload.
         */
        val MIGRATION_53_54 = object : Migration(53, 54) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `personal_spam_rules` (
                        `ownerNpub` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerNpub`, `type`, `value`)
                    )
                """.trimIndent())
            }
        }

        /**
         * AI-agent action proposals (kind-1010 direction:"agent-action",
         * refs/ANDROID_AI_AGENTS.md §6): relay-sealed wraps routed to the
         * notifications tray, never the inbox.
         */
        /**
         * AI-agent calendar proposals (refs/ANDROID_AI_AGENTS.md §6, Sept
         * 2026): agent proposals arrive as normal inbound mail carrying an
         * `action` tag and a machine-readable `cal` JSON tag. Both are
         * persisted on the email row so the detail view can offer
         * "Add to calendar".
         */
        val MIGRATION_54_55 = object : Migration(54, 55) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `emails` ADD COLUMN `actionTag` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `emails` ADD COLUMN `calJson` TEXT DEFAULT NULL")
            }
        }

        /**
         * RFC 5322 recipient lists (END-01 §3.4, deployed 2026-09): the full
         * to/cc/bcc lists join the legacy single-address `toEmail` columns
         * (which keep holding the FIRST To addr-spec), plus the inbound
         * envelope `deliveredTo` that powers the "BCC'd to you" chip. The
         * outbox stores the lists it sent so delivery-receipt matching is
         * subject + any-address overlap and retries rebuild the rumor
         * verbatim (ANDROID_EMAIL_MIGRATION.md §7–8).
         */
        val MIGRATION_55_56 = object : Migration(55, 56) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `emails` ADD COLUMN `toRecipientsJson` TEXT")
                db.execSQL("ALTER TABLE `emails` ADD COLUMN `ccRecipientsJson` TEXT")
                db.execSQL("ALTER TABLE `emails` ADD COLUMN `bccRecipientsJson` TEXT")
                db.execSQL("ALTER TABLE `emails` ADD COLUMN `deliveredTo` TEXT")
                db.execSQL("ALTER TABLE `email_outbox` ADD COLUMN `toRecipientsJson` TEXT")
                db.execSQL("ALTER TABLE `email_outbox` ADD COLUMN `ccRecipientsJson` TEXT")
                db.execSQL("ALTER TABLE `email_outbox` ADD COLUMN `bccRecipientsJson` TEXT")
            }
        }

        /**
         * User-uploaded encrypted files (kind 30078, `d = "desent:file:<sha>"`,
         * END-23): Room cache of the self-encrypted entry payloads, keyed by
         * the ciphertext sha256
         * (refs/FROM_email.desent.xyz/ANDROID_USER_FILES.md).
         */
        val MIGRATION_56_57 = object : Migration(56, 57) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `user_files` (
                        `sha256` TEXT PRIMARY KEY NOT NULL,
                        `ownerNpub` TEXT NOT NULL,
                        `filename` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `size` INTEGER NOT NULL,
                        `keyHex` TEXT NOT NULL,
                        `nonceHex` TEXT NOT NULL,
                        `uploadedAt` INTEGER NOT NULL,
                        `blurhash` TEXT,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `dTag` TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_files_ownerNpub` ON `user_files` (`ownerNpub`)")
            }
        }
    }
}