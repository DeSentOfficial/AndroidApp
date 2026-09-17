package xyz.desent.di

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import xyz.desent.crypto.BiometricAuthManager
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.crypto.SimpleKeyManager
import xyz.desent.crypto.GiftWrapEncryptionService
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.alias.AliasClient
import xyz.desent.data.registration.RegistrationClient
import xyz.desent.data.attachments.AttachmentsClient
import xyz.desent.data.attachments.NoteAttachmentClient
import xyz.desent.data.storage.StorageClient
import xyz.desent.data.attachment.AttachmentDownloader
import xyz.desent.data.attachment.DesentBlobUploader
import xyz.desent.data.message.MessageClient
import xyz.desent.data.local.database.NostrDatabase
import xyz.desent.data.nip05.Nip05VerificationService
import xyz.desent.data.nip11.Nip11MetadataService
import xyz.desent.data.local.database.dao.FollowDao
import xyz.desent.data.local.database.dao.RelayDao
import xyz.desent.data.local.database.dao.UserDao
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.mapper.EmailMapper
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.mapper.AccountMapper
import xyz.desent.data.mapper.FollowMapper
import xyz.desent.data.mapper.RelayMapper
import xyz.desent.data.mapper.UserMapper
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.data.repository.AuthRepositoryImpl
import xyz.desent.data.repository.FollowRepositoryImpl
import xyz.desent.data.repository.NostrRepository
import xyz.desent.data.repository.RelayRepositoryImpl
import xyz.desent.data.repository.UserRepositoryImpl
import xyz.desent.domain.repository.EmailRepository
import xyz.desent.data.repository.EmailRepositoryImpl
import xyz.desent.domain.repository.AliasRepository
import xyz.desent.domain.repository.RegistrationRepository
import xyz.desent.data.repository.AliasRepositoryImpl
import xyz.desent.data.repository.RegistrationRepositoryImpl
import xyz.desent.domain.repository.AttachmentsRepository
import xyz.desent.data.repository.AttachmentsRepositoryImpl
import xyz.desent.domain.repository.StorageRepository
import xyz.desent.data.repository.StorageRepositoryImpl
import xyz.desent.domain.repository.AuthRepository
import xyz.desent.domain.repository.FollowRepository
import xyz.desent.domain.repository.RelayRepository
import xyz.desent.domain.repository.UserRepository
import xyz.desent.domain.usecase.AuthUseCase
import xyz.desent.domain.usecase.FollowUseCase
import xyz.desent.domain.usecase.MediaUploadUseCase
import xyz.desent.domain.usecase.RelayUseCase
import xyz.desent.domain.usecase.UserUseCase
import xyz.desent.domain.usecase.EmailUseCase
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.RegistrationUseCase
import xyz.desent.domain.usecase.AttachmentsUseCase
import xyz.desent.domain.usecase.StorageUseCase
import xyz.desent.domain.usecase.PrivateStorageUseCase
import xyz.desent.widget.WidgetDataHelper

/**
 * Manual dependency injection container for the app.
 * All singletons are created and held here.
 */
class AppContainer(val context: Context) {

    /** Process-lifetime scope for long-running collectors (notifications, etc.). */
    private val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    // ==================== CRYPTO & SECURITY ====================
    val secureKeyManager: SecureKeyManager by lazy {
        SecureKeyManager(context)
    }
    
    val biometricAuthManager: BiometricAuthManager by lazy {
        BiometricAuthManager(context)
    }
    
    private val simpleKeyManager: SimpleKeyManager by lazy {
        SimpleKeyManager(context)
    }
    
    // ==================== NETWORKING ====================
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Disk cache for the desent.xyz REST API (tier info, alias config,
            // relay settings, …): without it every call re-downloads its JSON.
            .cache(Cache(File(context.cacheDir, "api_cache"), 25L * 1024 * 1024))
            // The API sends no cache headers; give unauthenticated GETs a
            // conservative freshness window so repeat calls hit the disk
            // cache. Authenticated responses are left uncacheable.
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                if (request.method == "GET" &&
                    request.header("Authorization") == null &&
                    response.header("Cache-Control") == null
                ) {
                    response.newBuilder()
                        .header("Cache-Control", "public, max-age=300")
                        .build()
                } else {
                    response
                }
            }
            .build()
    }
    
    // ==================== DATABASE ====================
    private val nostrDatabase: NostrDatabase by lazy {
        Room.databaseBuilder(
            context,
            NostrDatabase::class.java,
            "nostr_database"
        )
        .addMigrations(NostrDatabase.MIGRATION_3_4, NostrDatabase.MIGRATION_4_5, NostrDatabase.MIGRATION_5_6, NostrDatabase.MIGRATION_6_7, NostrDatabase.MIGRATION_9_10, NostrDatabase.MIGRATION_10_11, NostrDatabase.MIGRATION_11_12, NostrDatabase.MIGRATION_12_13, NostrDatabase.MIGRATION_13_14, NostrDatabase.MIGRATION_14_15, NostrDatabase.MIGRATION_15_16, NostrDatabase.MIGRATION_16_17, NostrDatabase.MIGRATION_17_18, NostrDatabase.MIGRATION_18_19, NostrDatabase.MIGRATION_19_20, NostrDatabase.MIGRATION_20_21, NostrDatabase.MIGRATION_21_22, NostrDatabase.MIGRATION_22_23, NostrDatabase.MIGRATION_23_24, NostrDatabase.MIGRATION_24_25, NostrDatabase.MIGRATION_25_26, NostrDatabase.MIGRATION_26_27, NostrDatabase.MIGRATION_27_28, NostrDatabase.MIGRATION_28_29, NostrDatabase.MIGRATION_29_30, NostrDatabase.MIGRATION_30_31, NostrDatabase.MIGRATION_31_32, NostrDatabase.MIGRATION_32_33, NostrDatabase.MIGRATION_33_34, NostrDatabase.MIGRATION_34_35, NostrDatabase.MIGRATION_35_36, NostrDatabase.MIGRATION_36_37, NostrDatabase.MIGRATION_37_38, NostrDatabase.MIGRATION_38_39, NostrDatabase.MIGRATION_39_40, NostrDatabase.MIGRATION_40_41, NostrDatabase.MIGRATION_41_42, NostrDatabase.MIGRATION_42_43, NostrDatabase.MIGRATION_43_44, NostrDatabase.MIGRATION_44_45, NostrDatabase.MIGRATION_45_46, NostrDatabase.MIGRATION_46_47, NostrDatabase.MIGRATION_47_48, NostrDatabase.MIGRATION_48_49, NostrDatabase.MIGRATION_49_50, NostrDatabase.MIGRATION_50_51, NostrDatabase.MIGRATION_51_52, NostrDatabase.MIGRATION_52_53, NostrDatabase.MIGRATION_53_54, NostrDatabase.MIGRATION_54_55, NostrDatabase.MIGRATION_55_56, NostrDatabase.MIGRATION_56_57)
        .build()
    }

    private val userDao: UserDao by lazy { nostrDatabase.userDao() }
    val followDao: FollowDao by lazy { nostrDatabase.followDao() }
    private val relayDao: RelayDao by lazy { nostrDatabase.relayDao() }
    private val emailDao: EmailDao by lazy { nostrDatabase.emailDao() }
    private val emailOutboxDao: xyz.desent.data.local.database.dao.EmailOutboxDao by lazy { nostrDatabase.emailOutboxDao() }
    private val emailForwardLedgerDao: xyz.desent.data.local.database.dao.EmailForwardLedgerDao by lazy { nostrDatabase.emailForwardLedgerDao() }
    val securityAlertDao: xyz.desent.data.local.database.dao.SecurityAlertDao by lazy { nostrDatabase.securityAlertDao() }

    val accountDao: xyz.desent.data.local.database.dao.AccountDao by lazy { nostrDatabase.accountDao() }
    private val spamRuleDao: xyz.desent.data.local.database.dao.SpamRuleDao by lazy { nostrDatabase.spamRuleDao() }
    private val bayesianTokenDao: xyz.desent.data.local.database.dao.BayesianTokenDao by lazy { nostrDatabase.bayesianTokenDao() }
    private val privateNoteDao: xyz.desent.data.local.database.dao.PrivateNoteDao by lazy { nostrDatabase.privateNoteDao() }
    private val calendarEventDao: xyz.desent.data.local.database.dao.CalendarEventDao by lazy { nostrDatabase.calendarEventDao() }
    private val calendarDao: xyz.desent.data.local.database.dao.CalendarDao by lazy { nostrDatabase.calendarDao() }
    private val calendarRsvpDao: xyz.desent.data.local.database.dao.CalendarRsvpDao by lazy { nostrDatabase.calendarRsvpDao() }
    val favoriteNoteDao: xyz.desent.data.local.database.dao.FavoriteNoteDao by lazy { nostrDatabase.favoriteNoteDao() }
    private val privateContactsDao: xyz.desent.data.local.database.dao.PrivateContactsDao by lazy { nostrDatabase.privateContactsDao() }
    val badgeDao: xyz.desent.data.local.database.dao.BadgeDao by lazy { nostrDatabase.badgeDao() }
    val badgeNoticeDao: xyz.desent.data.local.database.dao.BadgeNoticeDao by lazy { nostrDatabase.badgeNoticeDao() }

    // ==================== PREFERENCES ====================
    val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(context)
    }

    /**
     * Per-account relay-sync cursors driving the `since` in the persistent
     * REQ filters (see RelaySyncWatermarks) — without them every app open and
     * relay reconnect re-downloaded the full limit-N event backlogs.
     */
    val relaySyncWatermarks: xyz.desent.data.relay.RelaySyncWatermarks by lazy {
        xyz.desent.data.relay.RelaySyncWatermarks(preferencesManager.dataStore)
    }
    
    // ==================== MAPPERS ====================
    private val accountMapper: AccountMapper by lazy { AccountMapper() }
    private val userMapper: UserMapper by lazy { UserMapper() }
    private val followMapper: FollowMapper by lazy { FollowMapper() }
    private val relayMapper: RelayMapper by lazy { RelayMapper() }
    private val emailMapper: EmailMapper by lazy { EmailMapper() }
    private val privateStorageMapper: xyz.desent.data.mapper.PrivateStorageMapper by lazy {
        xyz.desent.data.mapper.PrivateStorageMapper()
    }
    private val calendarMapper: xyz.desent.data.mapper.CalendarMapper by lazy {
        xyz.desent.data.mapper.CalendarMapper()
    }
    
    // ==================== SERVICES ====================
    val giftWrapEncryptionService: GiftWrapEncryptionService by lazy {
        GiftWrapEncryptionService(secureKeyManager)
    }

    // ==================== NOTIFICATIONS ====================
    val inAppNotificationManager: xyz.desent.presentation.notification.InAppNotificationManager by lazy {
        xyz.desent.presentation.notification.InAppNotificationManager()
    }

    /**
     * Decorated large-icon bitmaps for email notifications (sender avatar
     * or initials + DeSent badge). Room-only URL resolution, Coil byte
     * cache, bounded network wait — see NotificationAvatarFactory.
     */
    val notificationAvatarFactory: xyz.desent.presentation.notification.NotificationAvatarFactory by lazy {
        xyz.desent.presentation.notification.NotificationAvatarFactory(
            context = context,
            profileResolver = contactProfileResolver,
            faviconResolver = faviconResolver
        )
    }

    /**
     * System-tray (notification drawer) dispatcher. Collects email / security
     * arrivals on the application scope so notifications fire while the app
     * is open or backgrounded, for as long as the process lives.
     */
    val systemNotificationDispatcher: xyz.desent.presentation.notification.SystemNotificationDispatcher by lazy {
        xyz.desent.presentation.notification.SystemNotificationDispatcher(
            context = context,
            eventProcessor = nostrEventProcessor,
            preferencesManager = preferencesManager,
            nip46BunkerService = nip46BunkerService,
            avatarFactory = notificationAvatarFactory,
            conversationShortcutManager = conversationShortcutManager,
            scope = applicationScope
        )
    }

    /**
     * Long-lived conversation shortcuts for email senders, bound to each
     * notification via setShortcutId so the collapsed conversation card
     * renders the sender avatar far-left — see ConversationShortcutManager.
     */
    val conversationShortcutManager: xyz.desent.presentation.notification.ConversationShortcutManager by lazy {
        xyz.desent.presentation.notification.ConversationShortcutManager(context = context)
    }

    /**
     * Starts / stops the optional foreground service that keeps relay
     * connections alive in the background for much longer than plain process
     * lifetime.
     */
    val backgroundServiceController: xyz.desent.service.BackgroundServiceController by lazy {
        xyz.desent.service.BackgroundServiceController(context, preferencesManager, applicationScope)
    }

    /** App-lock (biometric / PIN) gate state, observed by MainActivity's lock overlay. */
    val appLockController: xyz.desent.presentation.lock.AppLockController by lazy {
        xyz.desent.presentation.lock.AppLockController(preferencesManager, applicationScope)
    }

    // ==================== BLOB UPLOAD (desent.xyz/blobs) ====================
    val desentBlobUploader: DesentBlobUploader by lazy {
        DesentBlobUploader(okHttpClient, secureKeyManager)
    }
    
    // ==================== NIP-05 VERIFICATION ====================
    val nip05VerificationService: Nip05VerificationService by lazy {
        Nip05VerificationService(okHttpClient, preferencesManager)
    }
    
    // ==================== NIP-11 METADATA SERVICE ====================
    private val nip11MetadataService: Nip11MetadataService by lazy {
        Nip11MetadataService(okHttpClient)
    }
    
    // ==================== REPOSITORIES ====================
    val userRepository: UserRepository by lazy {
        UserRepositoryImpl(
            userDao, userMapper, relayRepository, nostrEventProcessor,
            nip05VerificationService, preferencesManager
        )
    }

    private val followRepository: FollowRepository by lazy {
        FollowRepositoryImpl(followDao, followMapper, relayRepository, nostrEventProcessor, nostrRepository)
    }

    internal val emailRepository: EmailRepository by lazy {
        EmailRepositoryImpl(
            emailDao,
            emailOutboxDao,
            emailForwardLedgerDao,
            accountDao,
            emailMapper,
            relayRepository,
            secureKeyManager,
            preferencesManager,
            registrationRepository,
            messageClient,
            giftWrapEncryptionService,
            spamFilterRepository,
            pgpKeyRepository = pgpKeyManager,
            pgpAttachmentCache = pgpAttachmentCache,
            aliasRepository = aliasRepository,
            securityConfigStore = securityConfigStore
        )
    }

    /** Offline mail export/import (.dsme) — NIP-EMAIL forwarding fallback. */
    val mailTransferManager: xyz.desent.data.mail.MailTransferManager by lazy {
        xyz.desent.data.mail.MailTransferManager(emailDao, emailMapper, preferencesManager)
    }
    
    // ==================== NOSTR SERVICES ====================
    val nostrEventProcessor: NostrEventProcessor by lazy {
        NostrEventProcessor(
            context,
            userDao,
            followDao,
            userMapper,
            followMapper,
            giftWrapEncryptionService,
            emailDao,
            emailMapper,
            emailOutboxDao,
            securityAlertDao,
            relaySyncWatermarks
        )
    }

    val relayRepository: RelayRepository by lazy {
        RelayRepositoryImpl(relayDao, relayMapper, nostrEventProcessor, secureKeyManager)
    }

    /** Local cache of the active user's kind-35050 mailbox configuration. */
    val mailboxConfigStore: xyz.desent.data.local.preferences.MailboxConfigStore by lazy {
        xyz.desent.data.local.preferences.MailboxConfigStore(preferencesManager)
    }

    /** Decrypts + caches the active user's kind-35050 mailbox configuration. */
    val mailboxConfigHandler: xyz.desent.data.mailbox.MailboxConfigHandler by lazy {
        xyz.desent.data.mailbox.MailboxConfigHandler(secureKeyManager, mailboxConfigStore)
    }

    val mailboxConfigRepository: xyz.desent.domain.repository.MailboxConfigRepository by lazy {
        xyz.desent.data.repository.MailboxConfigRepositoryImpl(
            mailboxConfigStore,
            secureKeyManager,
            nostrRepository,
            preferencesManager
        )
    }

    val mailboxConfigUseCase: xyz.desent.domain.usecase.MailboxConfigUseCase by lazy {
        xyz.desent.domain.usecase.MailboxConfigUseCase(mailboxConfigRepository)
    }

    // ==================== SECURITY ALERTS + SECURITY CONFIG ====================
    // Login-security alerts (kind-1010 direction "security") land in their own
    // table; the security_alerts preference rides the kind-30079 user-settings
    // event as its OWN configuration (refs/FromServer/ANDROID_SECURITY_ALERTS.md
    // + USER_SETTINGS_PROTOCOL.md) — separate from 35050 MailboxConfig and the
    // 30078 spam-settings namespace.

    /** Local cache of the active user's kind-30079 security-config slice. */
    val securityConfigStore: xyz.desent.data.local.preferences.SecurityConfigStore by lazy {
        xyz.desent.data.local.preferences.SecurityConfigStore(preferencesManager)
    }

    /** Parses + caches the active user's kind-30079 security-config slice. */
    val securityConfigHandler: xyz.desent.data.security.SecurityConfigHandler by lazy {
        xyz.desent.data.security.SecurityConfigHandler(secureKeyManager, securityConfigStore)
    }

    internal val securityConfigRepositoryImpl: xyz.desent.data.repository.SecurityConfigRepositoryImpl by lazy {
        xyz.desent.data.repository.SecurityConfigRepositoryImpl(
            securityConfigStore,
            nostrRepository
        )
    }

    val securityConfigRepository: xyz.desent.domain.repository.SecurityConfigRepository
        get() = securityConfigRepositoryImpl

    val securityConfigUseCase: xyz.desent.domain.usecase.SecurityConfigUseCase by lazy {
        xyz.desent.domain.usecase.SecurityConfigUseCase(securityConfigRepository)
    }

    /**
     * NIP-58 badges (refs/FROM_email.desent.xyz/BADGES_PROTOCOL.md): cache
     * + sync + kind 30008 publishing, all scoped to the DeSent relay.
     */
    val badgeRepository: xyz.desent.data.repository.BadgeRepositoryImpl by lazy {
        xyz.desent.data.repository.BadgeRepositoryImpl(
            badgeDao = badgeDao,
            relayRepository = relayRepository,
            secureKeyManager = secureKeyManager,
            nip11MetadataService = nip11MetadataService,
            relayDao = relayDao
        )
    }

    val nostrRepository: NostrRepository by lazy {
        NostrRepository(relayRepository, secureKeyManager, nostrEventProcessor, relaySyncWatermarks)
    }

    // ==================== SESSION (multi-account) ====================
    val sessionManager: xyz.desent.data.session.SessionManager by lazy {
        xyz.desent.data.session.SessionManager(accountDao, preferencesManager, accountMapper)
    }

    val accountRepository: xyz.desent.domain.repository.AccountRepository by lazy {
        xyz.desent.data.repository.AccountRepositoryImpl(
            database = nostrDatabase,
            accountDao = accountDao,
            secureKeyManager = secureKeyManager,
            preferencesManager = preferencesManager,
            emailDao = emailDao,
            followDao = followDao,
            privateNoteDao = privateNoteDao,
            calendarEventDao = calendarEventDao,
            calendarDao = calendarDao,
            calendarRsvpDao = calendarRsvpDao,
            favoriteNoteDao = favoriteNoteDao,
            privateContactsDao = privateContactsDao,
            accountMapper = accountMapper
        )
    }

    val switchAccountUseCase: xyz.desent.domain.usecase.SwitchAccountUseCase by lazy {
        xyz.desent.domain.usecase.SwitchAccountUseCase(
            secureKeyManager = secureKeyManager,
            preferencesManager = preferencesManager,
            accountDao = accountDao,
            accountRepository = accountRepository,
            sessionManager = sessionManager,
            nostrRepository = nostrRepository,
            relayRepository = relayRepository,
            refreshPrimaryAddressUseCase = refreshPrimaryAddressUseCase
        )
    }
    
    // ==================== MEDIA UPLOAD ====================
    val mediaUploadUseCase: MediaUploadUseCase by lazy {
        MediaUploadUseCase(desentBlobUploader)
    }
    
    // ==================== KEY ROTATION (migration 040/042) ====================

    val keyRotationClient: xyz.desent.data.registration.KeyRotationClient by lazy {
        xyz.desent.data.registration.KeyRotationClient(okHttpClient, nostrHttpAuth)
    }

    val nostrLinkClient: xyz.desent.data.registration.NostrLinkClient by lazy {
        xyz.desent.data.registration.NostrLinkClient(okHttpClient, nostrHttpAuth)
    }

    val keyRotationUseCase: xyz.desent.domain.usecase.KeyRotationUseCase by lazy {
        xyz.desent.domain.usecase.KeyRotationUseCase(
            secureKeyManager = secureKeyManager,
            preferencesManager = preferencesManager,
            accountDao = accountDao,
            accountRepository = accountRepository,
            custodialAccountRepository = custodialAccountRepository,
            relayRepository = relayRepository,
            keyRotationClient = keyRotationClient,
            switchAccountUseCase = switchAccountUseCase
        )
    }

    private val authRepository: AuthRepository by lazy {
        AuthRepositoryImpl(
            secureKeyManager,
            preferencesManager,
            relayRepository,
            userRepository,
            nostrRepository,
            nostrEventProcessor,
            mediaUploadUseCase,
            registrationUseCase,
            custodialAccountRepository,
            nostrLinkClient,
            accountRepository,
            accountDao,
            switchAccountUseCase
        )
    }

    // ==================== KEY BACKUP ====================
    val keyBackupRepository: xyz.desent.domain.repository.KeyBackupRepository by lazy {
        xyz.desent.data.repository.KeyBackupRepositoryImpl(
            secureKeyManager = secureKeyManager,
            accountDao = accountDao,
            relayDao = relayDao,
        )
    }

    val exportBackupUseCase: xyz.desent.domain.usecase.ExportBackupUseCase by lazy {
        xyz.desent.domain.usecase.ExportBackupUseCase(keyBackupRepository)
    }

    val importBackupUseCase: xyz.desent.domain.usecase.ImportBackupUseCase by lazy {
        xyz.desent.domain.usecase.ImportBackupUseCase(
            keyBackupRepository = keyBackupRepository,
            accountRepository = accountRepository,
            authRepository = authRepository,
            relayRepository = relayRepository,
        )
    }
    
    // ==================== WIDGET ====================
    val widgetDataHelper: WidgetDataHelper by lazy {
        WidgetDataHelper(
            userDao,
            preferencesManager,
            privateNoteDao,
            favoriteNoteDao,
            calendarEventDao
        )
    }

    // ==================== USE CASES ====================
    val authUseCase: AuthUseCase by lazy {
        AuthUseCase(authRepository)
    }

    val userUseCase: UserUseCase by lazy {
        UserUseCase(userRepository)
    }

    val followUseCase: FollowUseCase by lazy {
        FollowUseCase(followRepository)
    }

    val emailUseCase: EmailUseCase by lazy {
        EmailUseCase(emailRepository)
    }

    // ==================== SPAM FILTER (client-side) ====================
    // See refs/SPAM_FILTER_REFERENCE.md. Three layers, all on-device.
    private val bundledSpamLists: xyz.desent.data.spam.BundledSpamLists by lazy {
        xyz.desent.data.spam.BundledSpamLists(context)
    }
    private val heuristicRules: xyz.desent.data.spam.HeuristicRules by lazy {
        xyz.desent.data.spam.HeuristicRules(bundledSpamLists)
    }
    private val bayesianClassifier: xyz.desent.data.spam.BayesianClassifier by lazy {
        xyz.desent.data.spam.BayesianClassifier(bayesianTokenDao)
    }
    private val spamClassifier: xyz.desent.data.spam.SpamClassifier by lazy {
        xyz.desent.data.spam.SpamClassifier(heuristicRules, bayesianClassifier)
    }
    private val spamListSyncer: xyz.desent.data.spam.SpamListSyncer by lazy {
        xyz.desent.data.spam.SpamListSyncer(relayRepository)
    }

    val spamFilterRepository: xyz.desent.domain.repository.SpamFilterRepository by lazy {
        xyz.desent.data.repository.SpamFilterRepositoryImpl(
            spamRuleDao = spamRuleDao,
            personalSpamRuleDao = nostrDatabase.personalSpamRuleDao(),
            bayesianTokenDao = bayesianTokenDao,
            emailDao = emailDao,
            emailMapper = emailMapper,
            classifier = spamClassifier,
            syncer = spamListSyncer,
            preferencesManager = preferencesManager
        )
    }

    val classifyEmailSpamUseCase: xyz.desent.domain.usecase.ClassifyEmailSpamUseCase by lazy {
        xyz.desent.domain.usecase.ClassifyEmailSpamUseCase(spamFilterRepository)
    }
    val trainSpamUseCase: xyz.desent.domain.usecase.TrainSpamUseCase by lazy {
        xyz.desent.domain.usecase.TrainSpamUseCase(spamFilterRepository)
    }
    val syncSpamListUseCase: xyz.desent.domain.usecase.SyncSpamListUseCase by lazy {
        xyz.desent.domain.usecase.SyncSpamListUseCase(spamFilterRepository)
    }
    val reclassifyInboxUseCase: xyz.desent.domain.usecase.ReclassifyInboxUseCase by lazy {
        xyz.desent.domain.usecase.ReclassifyInboxUseCase(emailDao, emailMapper, spamFilterRepository)
    }

    // ==================== ALIAS (NIP-98 HTTP API) ====================
    val nostrHttpAuth: NostrHttpAuth by lazy {
        NostrHttpAuth(secureKeyManager)
    }

    private val aliasClient: AliasClient by lazy {
        AliasClient(okHttpClient, nostrHttpAuth)
    }

    private val vanityClient: xyz.desent.data.vanity.VanityClient by lazy {
        xyz.desent.data.vanity.VanityClient(okHttpClient, nostrHttpAuth)
    }

    internal val aliasRepository: AliasRepository by lazy {
        AliasRepositoryImpl(aliasClient, vanityClient)
    }

    val aliasUseCase: AliasUseCase by lazy {
        AliasUseCase(aliasRepository)
    }

    // ==================== AI AGENTS (END-21) ====================
    // refs/FROM_email.desent.xyz/ANDROID_AI_AGENTS.md — user-provisioned
    // OpenClaw-style agents with their own address + Nostr keypair (key
    // generated on-device; only the npub is ever sent).
    private val agentsClient: xyz.desent.data.agents.AgentsClient by lazy {
        xyz.desent.data.agents.AgentsClient(okHttpClient, nostrHttpAuth)
    }

    internal val agentsRepository: xyz.desent.domain.repository.AgentsRepository by lazy {
        xyz.desent.data.repository.AgentsRepositoryImpl(agentsClient)
    }

    val agentsUseCase: xyz.desent.domain.usecase.AgentsUseCase by lazy {
        xyz.desent.domain.usecase.AgentsUseCase(agentsRepository)
    }

    // ==================== PAYMENTS (Strike lightning checkout, NIP-98) ====================
    // refs/FROM_email.desent.xyz/ANDROID_PAYMENTS.md — invoice mint/poll for
    // vanity requests, slot packs and the paid tier. All Strike communication
    // is server-side; the client only renders the BOLT11 it receives.
    private val paymentsClient: xyz.desent.data.payments.PaymentsClient by lazy {
        xyz.desent.data.payments.PaymentsClient(okHttpClient, nostrHttpAuth)
    }

    internal val paymentsRepository: xyz.desent.domain.repository.PaymentsRepository by lazy {
        xyz.desent.data.repository.PaymentsRepositoryImpl(paymentsClient)
    }

    val paymentsUseCase: xyz.desent.domain.usecase.PaymentsUseCase by lazy {
        xyz.desent.domain.usecase.PaymentsUseCase(paymentsRepository)
    }

    // ==================== DM-relay fan-out (ANDROID_DM_FANOUT.md) ====================

    private val fanoutClient: xyz.desent.data.fanout.FanoutClient by lazy {
        xyz.desent.data.fanout.FanoutClient(okHttpClient, nostrHttpAuth)
    }

    /** Public relay directory (directory.yadha.net) — mirroring relay picker. */
    private val relayDirectoryClient: xyz.desent.data.directory.RelayDirectoryClient by lazy {
        xyz.desent.data.directory.RelayDirectoryClient(okHttpClient)
    }

    val fanoutRepository: xyz.desent.domain.repository.FanoutRepository by lazy {
        xyz.desent.data.repository.FanoutRepositoryImpl(
            nostrRepository = nostrRepository,
            fanoutClient = fanoutClient,
            relayDirectoryClient = relayDirectoryClient,
            nip11MetadataService = nip11MetadataService,
            securityConfigStore = securityConfigStore
        )
    }

    val fanoutUseCase: xyz.desent.domain.usecase.FanoutUseCase by lazy {
        xyz.desent.domain.usecase.FanoutUseCase(fanoutRepository)
    }

    // ==================== REGISTRATION API (primary @desent.xyz address) ====================
    private val registrationClient: RegistrationClient by lazy {
        RegistrationClient(okHttpClient, nostrHttpAuth)
    }

    internal val registrationRepository: RegistrationRepository by lazy {
        RegistrationRepositoryImpl(registrationClient)
    }

    val registrationUseCase: RegistrationUseCase by lazy {
        RegistrationUseCase(registrationRepository)
    }

    /** Cached primary-address refresh (launch / login / switch hooks). */
    val refreshPrimaryAddressUseCase: xyz.desent.domain.usecase.RefreshPrimaryAddressUseCase by lazy {
        xyz.desent.domain.usecase.RefreshPrimaryAddressUseCase(
            accountDao, secureKeyManager, registrationRepository
        )
    }

    // ==================== CUSTODIAL ACCOUNTS (username & password) ====================
    // refs/FromServer/CUSTODIAL_ACCOUNTS.md — Argon2id verifier + AES-GCM key
    // blob, all crypto on-device; shares the registration API host/client.
    internal val custodialAccountRepository: xyz.desent.domain.repository.CustodialAccountRepository by lazy {
        xyz.desent.data.repository.CustodialAccountRepositoryImpl(registrationClient, secureKeyManager)
    }

    // ==================== MESSAGES API (recipient-initiated gift-wrap delete) ====================
    private val messageClient: MessageClient by lazy {
        MessageClient(okHttpClient, nostrHttpAuth)
    }

    // ==================== ATTACHMENTS API (blob list/download/delete) ====================
    private val attachmentsClient: AttachmentsClient by lazy {
        AttachmentsClient(okHttpClient, nostrHttpAuth)
    }

    internal val attachmentsRepository: AttachmentsRepository by lazy {
        AttachmentsRepositoryImpl(context, attachmentsClient)
    }

    val attachmentsUseCase: AttachmentsUseCase by lazy {
        AttachmentsUseCase(attachmentsRepository)
    }

    // ==================== STORAGE API (per-category byte breakdown) ====================
    // See refs/STORAGE_TAB_ANDROID.md. GET /api/storage on desent.xyz,
    // NIP-98 auth (same okHttpClient + nostrHttpAuth as the alias family).
    private val storageClient: StorageClient by lazy {
        StorageClient(okHttpClient, nostrHttpAuth)
    }

    internal val storageRepository: StorageRepository by lazy {
        StorageRepositoryImpl(storageClient)
    }

    // ==================== RELAY SETTINGS (feature flags) ====================
    // See refs/CALENDAR_PROTOCOL.md §Feature flag. GET /api/relay-settings on
    // desent.xyz, NIP-98 auth. Drives the `calendar_enabled` gate.
    private val relaySettingsClient: xyz.desent.data.relay.RelaySettingsClient by lazy {
        xyz.desent.data.relay.RelaySettingsClient(okHttpClient, nostrHttpAuth)
    }

    val relaySettingsRepository: xyz.desent.data.relay.RelaySettingsRepository by lazy {
        xyz.desent.data.relay.RelaySettingsRepository(relaySettingsClient)
    }

    val storageUseCase: StorageUseCase by lazy {
        StorageUseCase(storageRepository)
    }

    // ==================== NOTE ATTACHMENTS (client-side AES-256-GCM) ====================
    // See refs/PRIVATE_STORAGE_PROTOCOL.md §"Attachment wire endpoints". The two
    // ciphertext endpoints are the only attachment flow for notes; they carry
    // opaque ciphertext + NIP-98 auth (the AES key never leaves the device).
    private val noteAttachmentClient: NoteAttachmentClient by lazy {
        NoteAttachmentClient(okHttpClient, nostrHttpAuth)
    }

    // ==================== PRIVATE STORAGE (NIP-78 kind 30078) ====================
    // See refs/PRIVATE_STORAGE_PROTOCOL.md. Notes + contacts self-encrypted via
    // NIP-44 and synced as kind 30078; relay force-scopes reads per-user. Also
    // hosts the cross-device spam-policy namespaces (`desent:spam-settings` and
    // `desent:spam-tokens:*`) — see SpamSettingsSyncCoordinator.
    internal val privateStorageRepository: xyz.desent.data.repository.PrivateStorageRepositoryImpl by lazy {
        xyz.desent.data.repository.PrivateStorageRepositoryImpl(
            noteDao = privateNoteDao,
            contactsDao = privateContactsDao,
            mapper = privateStorageMapper,
            secureKeyManager = secureKeyManager,
            nostrRepository = nostrRepository,
            bayesianTokenDao = bayesianTokenDao,
            personalSpamRuleDao = nostrDatabase.personalSpamRuleDao(),
            userFileDao = nostrDatabase.userFileDao(),
            preferencesManager = preferencesManager
        )
    }

    /**
     * Mail folders + synced read state on the kind-30078 overlay
     * (`desent:mail-folders` / `desent:mail-state:<i>`); see
     * refs/FROM_email.desent.xyz/ANDROID_MAIL_FOLDERS.md. Read/unread hooks
     * and inbound dispatch are wired in the init block below.
     */
    internal val mailFolderRepository: xyz.desent.data.repository.MailFolderRepositoryImpl by lazy {
        xyz.desent.data.repository.MailFolderRepositoryImpl(
            mailFolderDao = nostrDatabase.mailFolderDao(),
            mailStateDao = nostrDatabase.mailStateDao(),
            emailDao = emailDao,
            secureKeyManager = secureKeyManager,
            nostrRepository = nostrRepository,
            preferencesManager = preferencesManager,
            coroutineScope = applicationScope
        )
    }

    /**
     * Reactive publisher for `desent:spam-settings`. Debounced observer over
     * the local config Flow; started in [DeSentApplication] on the application
     * scope so it lives for the life of the process.
     */
    val spamSettingsSyncCoordinator: xyz.desent.data.spam.SpamSettingsSyncCoordinator by lazy {
        xyz.desent.data.spam.SpamSettingsSyncCoordinator(
            preferencesManager = preferencesManager,
            personalSpamRuleDao = nostrDatabase.personalSpamRuleDao(),
            privateStorageRepository = privateStorageRepository,
            coroutineScope = applicationScope
        )
    }

    /**
     * Builds per-screen [xyz.desent.data.spam.RemoteImagePolicyState] holders
     * bound to a ViewModel scope: combines the (cloud-synced) spam config with
     * the encrypted contacts list to decide per-sender remote-image blocking.
     */
    val remoteImagePolicyStateFactory: xyz.desent.data.spam.RemoteImagePolicyStateFactory by lazy {
        xyz.desent.data.spam.RemoteImagePolicyStateFactory(
            spamFilterRepository = spamFilterRepository,
            privateStorageRepository = privateStorageRepository,
            preferencesManager = preferencesManager
        )
    }

    val privateStorageUseCase: PrivateStorageUseCase by lazy {
        PrivateStorageUseCase(
            repository = privateStorageRepository,
            preferencesManager = preferencesManager,
            attachmentClient = noteAttachmentClient
        )
    }

    // ==================== PGP (OpenPGP E2E MAIL ENCRYPTION) ====================
    // See refs/FROM_email.desent.xyz/ANDROID_PGP.md + PGP_ENCRYPTION.md. The
    // private key is stored ONLY as kind-30078 `desent:pgp` (NIP-44-to-self)
    // plus a Keystore-backed device mirror; the HTTP registry ever sees the
    // public half. All key/WKD endpoints live on https://desent.xyz.
    val pgpClient: xyz.desent.data.pgp.PgpClient by lazy {
        xyz.desent.data.pgp.PgpClient(okHttpClient, nostrHttpAuth)
    }

    /** Client mirror of the relay's `pgp_enabled` master switch (fail-closed). */
    val pgpFeatureGate: xyz.desent.data.pgp.PgpFeatureGate by lazy {
        xyz.desent.data.pgp.PgpFeatureGate(pgpClient)
    }

    val pgpKeyManager: xyz.desent.data.pgp.PgpKeyManager by lazy {
        xyz.desent.data.pgp.PgpKeyManager(
            privateStorageRepository = privateStorageRepository,
            secureKeyManager = secureKeyManager,
            pgpClient = pgpClient
        )
    }

    val pgpMimeParser: xyz.desent.data.pgp.PgpMimeParser by lazy {
        xyz.desent.data.pgp.PgpMimeParser()
    }

    /** App-private cache for attachments decrypted out of the PGP envelope. */
    val pgpAttachmentCache: xyz.desent.data.pgp.PgpAttachmentCache by lazy {
        xyz.desent.data.pgp.PgpAttachmentCache(context)
    }

    /**
     * Owner-scoped `pgp_auto_encrypt` preference (kind 30079 slice) for the
     * compose/reply lock auto-check — re-subscribes on account switch.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun pgpAutoEncryptFlow(): kotlinx.coroutines.flow.Flow<Boolean> =
        preferencesManager.npubKey.flatMapLatest { npub ->
            if (npub == null) {
                kotlinx.coroutines.flow.flowOf(false)
            } else {
                securityConfigRepository.observe(npub).map { it?.pgpAutoEncrypt ?: false }
            }
        }


    /**
     * Room-first kind-0 profile enrichment for contact-linked nostr
     * identities (ANDROID_CONTACTS.md §5): serves cached `users` rows with
     * zero network traffic, refreshes stale ones in the background via
     * one-shot REQs across [xyz.desent.data.RelayConfig.PUBLIC_PROFILE_RELAYS]
     * (temporary, reference-counted third-party connections), and resolves
     * email-only contacts through NIP-05 with links persisted in
     * `contact_profile_links`.
     */
    val contactProfileResolver: xyz.desent.domain.repository.ContactProfileResolver by lazy {
        xyz.desent.data.contacts.ContactProfileResolverImpl(
            relayRepository = relayRepository,
            eventProcessor = nostrEventProcessor,
            userDao = userDao,
            nip05Service = nip05VerificationService,
            contactProfileLinkDao = nostrDatabase.contactProfileLinkDao()
        )
    }

    /**
     * Favicon fallback for email-sender avatars: the NIP-98 favicon cache
     * at `desent.xyz/api/favicon/<domain>` by default (user preference;
     * direct `https://<sender-domain>/favicon.ico` probes when disabled or
     * on endpoint failure), with availability persisted in
     * `domain_favicons` so known misses aren't re-probed (24h re-check
     * window).
     */
    val faviconResolver: xyz.desent.data.avatar.FaviconResolver by lazy {
        xyz.desent.data.avatar.FaviconResolver(
            okHttpClient = okHttpClient,
            dao = nostrDatabase.domainFaviconDao(),
            nostrHttpAuth = nostrHttpAuth,
            serverCacheEnabled = {
                preferencesManager.isFaviconServerCacheEnabled.first()
            }
        )
    }

    // ==================== ENCRYPTED CALENDAR (NIP-52 kinds 31922-31925) ====================
    // See refs/CALENDAR_PROTOCOL.md. Events are NIP-44 self-encrypted and
    // synced as replaceable kind 31922/31923; relay force-scopes reads per-user.
    internal val calendarRepository: xyz.desent.data.repository.CalendarRepositoryImpl by lazy {
        xyz.desent.data.repository.CalendarRepositoryImpl(
            eventDao = calendarEventDao,
            calendarDao = calendarDao,
            rsvpDao = calendarRsvpDao,
            mapper = calendarMapper,
            secureKeyManager = secureKeyManager,
            nostrRepository = nostrRepository,
            giftWrapEncryptionService = giftWrapEncryptionService,
            relayRepository = relayRepository
        )
    }

    val calendarUseCase: xyz.desent.domain.usecase.CalendarUseCase by lazy {
        xyz.desent.domain.usecase.CalendarUseCase(
            repository = calendarRepository,
            preferencesManager = preferencesManager,
            attachmentClient = noteAttachmentClient
        )
    }

    /** Platform-geocoder-backed text → coordinates resolution for calendar locations. */
    val locationResolver: xyz.desent.domain.repository.LocationResolver by lazy {
        xyz.desent.data.repository.AndroidLocationResolver(context)
    }

    /** Downloads + decrypts a note attachment and exposes it via FileProvider. */
    val noteAttachmentOpener: xyz.desent.data.attachment.NoteAttachmentOpener by lazy {
        xyz.desent.data.attachment.NoteAttachmentOpener(context, privateStorageUseCase)
    }

    /** Downloads + decrypts a user-uploaded file and exposes it via FileProvider. */
    val userFileOpener: xyz.desent.data.attachment.UserFileOpener by lazy {
        xyz.desent.data.attachment.UserFileOpener(context, privateStorageUseCase)
    }

    /** END-23 §2 step 2: client-side blurhash + dimensions for image uploads. */
    val uploadPreviewGenerator: xyz.desent.data.attachment.UploadPreviewGenerator by lazy {
        xyz.desent.data.attachment.UploadPreviewGenerator()
    }

    /** Share-intent hand-off (ACTION_SEND → Files screen upload). */
    val pendingUploads: xyz.desent.presentation.ui.files.PendingUploads by lazy {
        xyz.desent.presentation.ui.files.PendingUploads()
    }

    /** Downloads + decrypts a calendar-event attachment and exposes it via FileProvider. */
    val calendarAttachmentOpener: xyz.desent.data.attachment.CalendarAttachmentOpener by lazy {
        xyz.desent.data.attachment.CalendarAttachmentOpener(context, calendarUseCase)
    }

    /** On-disk persistence for in-progress note drafts. */
    val noteDraftStore: xyz.desent.data.local.preferences.NoteDraftStore by lazy {
        xyz.desent.data.local.preferences.NoteDraftStore(context)
    }

    /** Reads/writes markdown documents opened from the device (SAF / ACTION_VIEW). */
    val markdownFileIO: xyz.desent.data.markdown.MarkdownFileIO by lazy {
        xyz.desent.data.markdown.ContentResolverMarkdownFileIO(context.contentResolver)
    }

    // ==================== ATTACHMENT DOWNLOADER (Blossom) ====================
    val attachmentDownloader: AttachmentDownloader by lazy {
        AttachmentDownloader(context, okHttpClient, secureKeyManager)
    }

    val relayUseCase: RelayUseCase by lazy {
        RelayUseCase(relayRepository)
    }
    
    val backgroundDataFetchUseCase: xyz.desent.domain.usecase.BackgroundDataFetchUseCase by lazy {
        xyz.desent.domain.usecase.BackgroundDataFetchUseCaseImpl(
            nostrRepository = nostrRepository,
            userUseCase = userUseCase,
            followUseCase = followUseCase
        )
    }

    // ==================== WEAR OS SYNC (config + inbox + calendar + bunker push) ====================
    val wearSyncManager: xyz.desent.wear.WearSyncManager by lazy {
        xyz.desent.wear.WearSyncManager(
            context = context,
            preferencesManager = preferencesManager,
            emailDao = emailDao,
            calendarRepository = calendarRepository,
            privateStorageRepository = privateStorageRepository,
            nip46BunkerService = nip46BunkerService
        )
    }

    /**
     * Collector: when the phone's theme preference changes, re-push the
     * wear config payload so the watch restyles. Rare event; started from
     * DeSentApplication.
     */
    fun startWearConfigSync() {
        applicationScope.launch {
            var last: xyz.desent.data.local.preferences.ThemeMode? = null
            preferencesManager.themeMode.collect { mode ->
                if (mode != last) {
                    last = mode
                    wearSyncManager.pushConfig()
                }
            }
        }
    }

    /**
     * Collector: whenever the active account's inbox or spam threads change
     * (new mail, read-state flips, spam reclassification, account switch)
     * — debounced, since a single ingest can ripple through several Room
     * writes — re-push the decrypted inbox snapshot to the watch.
     * [WearSyncManager.pushInbox] re-reads the current state itself, so the
     * collector only signals "something changed".
     */
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun startWearInboxSync() {
        applicationScope.launch {
            preferencesManager.activeNpub
                .distinctUntilChanged()
                .flatMapLatest { npub ->
                    if (npub.isNullOrBlank()) {
                        flowOf(null)
                    } else {
                        combine(
                            emailDao.observeThreads(npub),
                            emailDao.observeSpamThreads(npub),
                            preferencesManager.wearSyncSpam
                        ) { threads, spam, spamEnabled -> Triple(threads, spam, spamEnabled) }
                    }
                }
                .debounce(5_000)
                .collect {
                    runCatching { wearSyncManager.pushInbox() }
                        .onFailure { e ->
                            android.util.Log.w("AppContainer", "Wear inbox push failed: ${e.message}")
                        }
                }
        }
    }

    /**
     * Collector: whenever the active account's calendar events or private
     * contacts change (edits, shares arriving, relay echo, account switch)
     * — debounced, since one edit can ripple through several Room writes —
     * re-push the decrypted calendar snapshot to the watch.
     * [WearSyncManager.pushCalendar] re-reads the current state itself, so
     * the collector only signals "something changed". Contacts participate
     * because anniversaries are contact-derived.
     */
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun startWearCalendarSync() {
        applicationScope.launch {
            preferencesManager.activeNpub
                .distinctUntilChanged()
                .flatMapLatest { npub ->
                    if (npub.isNullOrBlank()) {
                        flowOf(null)
                    } else {
                        combine(
                            calendarRepository.observeEvents(npub),
                            privateStorageRepository.observeContacts(npub)
                        ) { events, contacts -> events to contacts }
                    }
                }
                .debounce(5_000)
                .collect {
                    runCatching { wearSyncManager.pushCalendar() }
                        .onFailure { e ->
                            android.util.Log.w("AppContainer", "Wear calendar push failed: ${e.message}")
                        }
                }
        }
    }

    /**
     * Collector: mirror the bunker's pending-prompt slot to the watch. A new
     * prompt or a decision/auto-deny clearing it must reach the watch within
     * seconds (the watch countdown is anchored to the pushed expiry), so
     * unlike the inbox/calendar collectors there is NO debounce. Flipping the
     * bunker sync pref to off pushes a cleared payload once so a request that
     * was already on the watch disappears.
     */
    fun startWearBunkerSync() {
        applicationScope.launch {
            var lastEnabled: Boolean? = null
            combine(
                preferencesManager.wearSyncBunker,
                nip46BunkerService.pendingSignPrompt
            ) { enabled, prompt -> enabled to prompt }
                .collect { (enabled, _) ->
                    val wasEnabled = lastEnabled
                    lastEnabled = enabled
                    if (!enabled && wasEnabled == false) return@collect // stayed off; nothing to do
                    runCatching { wearSyncManager.pushBunkerState() }
                        .onFailure { e ->
                            android.util.Log.w("AppContainer", "Wear bunker push failed: ${e.message}")
                        }
                }
        }
    }

    // ==================== NIP-46 BUNKER (remote signer) ====================
    val nip46PairingStore: xyz.desent.data.nip46.Nip46PairingStore by lazy {
        xyz.desent.data.nip46.Nip46PairingStore(context)
    }

    val nip46BunkerService: xyz.desent.data.nip46.Nip46BunkerService by lazy {
        xyz.desent.data.nip46.Nip46BunkerService(
            pairingStore = nip46PairingStore,
            secureKeyManager = secureKeyManager,
            giftWrap = giftWrapEncryptionService,
            relayRepository = relayRepository,
            preferencesManager = preferencesManager,
            scope = applicationScope,
            relaySyncWatermarks = relaySyncWatermarks
        )
    }

    init {
        // Set the NIP-46 bunker so gift-wrapped nip46 rumors route to it.
        nostrEventProcessor.setNip46BunkerService(nip46BunkerService)

        // Let the repository's gift-wrap subscription sweeps also re-open the
        // RAW (kind 24133) NIP-46 subscriptions for active pairings.
        nostrRepository.setNip46BunkerService(nip46BunkerService)

        // Wire the spam filter so inbound emails are stamped at ingestion time.
        nostrEventProcessor.setSpamFilterRepository(spamFilterRepository)

        // Wire private storage so inbound kind-30078 events decrypt + cache.
        nostrEventProcessor.setPrivateStorageRepository(privateStorageRepository)

        // Wire the mail-folder overlay: 30078 dispatch (manifest + shards),
        // apply-on-arrival at ingest, and read-state mirroring from the
        // email repository (mail folders + synced read state).
        privateStorageRepository.mailFolderRepository = mailFolderRepository
        nostrEventProcessor.setMailFolderRepository(mailFolderRepository)
        emailRepository.mailFolderRepository = mailFolderRepository

        // Wire the encrypted calendar so inbound kind-31922..31925 events
        // decrypt + cache (see refs/CALENDAR_PROTOCOL.md).
        nostrEventProcessor.setCalendarRepository(calendarRepository)

        // Wire the NIP-EMAIL mailbox configuration (kind 35050) so the user's
        // retention TTL + private rules decrypt + cache on inbound.
        nostrEventProcessor.setMailboxConfigHandler(mailboxConfigHandler)

        // Wire the kind-30079 user-settings handler so the security-alert
        // mode decrypts + caches on inbound (own publish echo included, LWW).
        nostrEventProcessor.setSecurityConfigHandler(securityConfigHandler)

        // Wire NIP-58 badges so inbound kinds 8/30008/30009/5 cache, and so
        // login opens the badge subscriptions for the active account.
        nostrEventProcessor.setBadgeRepository(badgeRepository)
        nostrRepository.setBadgeRepository(badgeRepository)

        // Wire the relay-mirroring orchestrator: the gift-wrap subscription
        // sweep triggers the backup fetch from the user's NIP-65 relays, and
        // enabling the 30079 opt-in pulls immediately.
        nostrRepository.setFanoutRepository(fanoutRepository)
        securityConfigRepositoryImpl.onDmFanoutEnabled = { fanoutRepository.syncBackupIfEnabled() }

        // Persist badge-award notices (tray + once-per-award notifications).
        nostrEventProcessor.setBadgeNoticeHandler(
            xyz.desent.data.nostr.BadgeNoticeHandler(badgeNoticeDao)
        )

        // Wire the PGP key repository so inbound PGP mail is transiently
        // decrypted for spam scoring (verdict persisted, plaintext never).
        nostrEventProcessor.setPgpKeyRepository(pgpKeyManager)

        // Fetch the relay's pgp_enabled gate once per app start (fail-closed).
        pgpFeatureGate.ensureLoaded()
    }

    fun createNip46ViewModel(): xyz.desent.presentation.ui.nip46.Nip46ViewModel {
        return xyz.desent.presentation.ui.nip46.Nip46ViewModel(
            bunkerService = nip46BunkerService,
            preferencesManager = preferencesManager,
            secureKeyManager = secureKeyManager
        )
    }

    companion object {
        private var instance: AppContainer? = null

        fun getInstance(context: Context): AppContainer {
            return instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
        }

    }
}
