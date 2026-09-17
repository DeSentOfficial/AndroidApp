package xyz.desent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.di.AppContainer
import xyz.desent.domain.usecase.AuthUseCase
import xyz.desent.domain.usecase.UserUseCase
import xyz.desent.presentation.ui.login.viewmodel.LoginViewModel
import xyz.desent.presentation.ui.settings.viewmodel.SettingsViewModel
import xyz.desent.presentation.ui.splash.viewmodel.SplashViewModel
import xyz.desent.presentation.ui.email.viewmodel.EmailInboxViewModel
import xyz.desent.presentation.ui.email.viewmodel.EmailDetailViewModel
import xyz.desent.presentation.ui.email.viewmodel.SpamDetailViewModel
import xyz.desent.presentation.ui.alias.viewmodel.AliasViewModel
import xyz.desent.presentation.ui.files.viewmodel.FilesViewModel
import xyz.desent.presentation.ui.profile.ProfileEditViewModel

/**
 * Factory for creating ViewModels with manual dependency injection.
 */
class ViewModelFactory(public val appContainer: AppContainer) : ViewModelProvider.Factory {    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            LoginViewModel::class.java -> {
                LoginViewModel(
                    appContainer.authUseCase,
                    appContainer.registrationUseCase,
                    appContainer.custodialAccountRepository,
                    appContainer.refreshPrimaryAddressUseCase,
                    appContainer.paymentsUseCase,
                    appContainer.aliasUseCase
                ) as T
            }
            SplashViewModel::class.java -> {
                SplashViewModel(
                    appContainer.authUseCase,
                    appContainer.userUseCase,
                    appContainer.nostrRepository,
                    appContainer.relayRepository,
                    appContainer.accountRepository,
                    appContainer.refreshPrimaryAddressUseCase,
                    appContainer.refreshOwnProfileUseCase
                ) as T
            }
            SettingsViewModel::class.java -> {
                SettingsViewModel(
                    appContainer.preferencesManager
                ) as T
            }
            xyz.desent.presentation.ui.nip46.Nip46ViewModel::class.java -> {
                appContainer.createNip46ViewModel() as T
            }
            EmailInboxViewModel::class.java -> {
                EmailInboxViewModel(
                    appContainer.emailUseCase,
                    appContainer.preferencesManager,
                    appContainer.trainSpamUseCase,
                    appContainer.spamFilterRepository,
                    appContainer.contactProfileResolver,
                appContainer.faviconResolver,
                mailFolderRepository = appContainer.mailFolderRepository,
                aliasUseCase = appContainer.aliasUseCase,
                fanoutUseCase = appContainer.fanoutUseCase,
                securityConfigRepository = appContainer.securityConfigRepository
                ) as T
            }
            AliasViewModel::class.java -> {
                AliasViewModel(appContainer.aliasUseCase, appContainer.paymentsUseCase) as T
            }
            xyz.desent.presentation.ui.agents.viewmodel.AgentsViewModel::class.java -> {
                xyz.desent.presentation.ui.agents.viewmodel.AgentsViewModel(
                    appContainer.agentsUseCase,
                    appContainer.aliasUseCase,
                    appContainer.paymentsUseCase
                ) as T
            }
            FilesViewModel::class.java -> {
                FilesViewModel(
                    appContainer.attachmentsUseCase,
                    appContainer.emailUseCase,
                    appContainer.aliasUseCase,
                    appContainer.privateStorageUseCase,
                    appContainer.userFileOpener,
                    appContainer.noteAttachmentOpener,
                    appContainer.uploadPreviewGenerator,
                    appContainer.pendingUploads
                ) as T
            }
            xyz.desent.presentation.ui.storage.viewmodel.StorageViewModel::class.java -> {
                xyz.desent.presentation.ui.storage.viewmodel.StorageViewModel(appContainer.storageUseCase) as T
            }
            xyz.desent.presentation.ui.notes.viewmodel.NotesViewModel::class.java -> {
                xyz.desent.presentation.ui.notes.viewmodel.NotesViewModel(
                    appContainer.privateStorageUseCase,
                    appContainer.favoriteNoteDao
                ) as T
            }
            xyz.desent.presentation.ui.calendar.viewmodel.CalendarViewModel::class.java -> {
                xyz.desent.presentation.ui.calendar.viewmodel.CalendarViewModel(
                    appContainer.calendarUseCase,
                    appContainer.privateStorageUseCase,
                    appContainer.contactProfileResolver
                ) as T
            }
            xyz.desent.presentation.ui.calendar.viewmodel.CalendarListsViewModel::class.java -> {
                xyz.desent.presentation.ui.calendar.viewmodel.CalendarListsViewModel(
                    appContainer.calendarUseCase
                ) as T
            }
            xyz.desent.presentation.ui.contacts.viewmodel.ContactsViewModel::class.java -> {
                xyz.desent.presentation.ui.contacts.viewmodel.ContactsViewModel(
                    appContainer.privateStorageUseCase,
                    appContainer.contactProfileResolver
                ) as T
            }
            xyz.desent.presentation.ui.billing.viewmodel.BillingViewModel::class.java -> {
                xyz.desent.presentation.ui.billing.viewmodel.BillingViewModel(
                    appContainer.paymentsUseCase,
                    appContainer.aliasUseCase
                ) as T
            }
            ProfileEditViewModel::class.java -> {
                ProfileEditViewModel(
                    appContainer.userUseCase,
                    appContainer.registrationUseCase,
                    appContainer.preferencesManager,
                    appContainer.nostrRepository,
                    appContainer.secureKeyManager,
                    appContainer.authUseCase,
                    appContainer.aliasUseCase,
                    appContainer.accountRepository
                ) as T
            }
            xyz.desent.presentation.ui.settings.backup.viewmodel.BackupViewModel::class.java -> {
                xyz.desent.presentation.ui.settings.backup.viewmodel.BackupViewModel(
                    exportBackupUseCase = appContainer.exportBackupUseCase,
                    accountRepository = appContainer.accountRepository,
                    context = appContainer.context,
                    secureKeyManager = appContainer.secureKeyManager
                ) as T
            }
            xyz.desent.presentation.ui.settings.backup.viewmodel.RestoreViewModel::class.java -> {
                xyz.desent.presentation.ui.settings.backup.viewmodel.RestoreViewModel(
                    importBackupUseCase = appContainer.importBackupUseCase,
                    context = appContainer.context
                ) as T
            }
            xyz.desent.presentation.ui.settings.viewmodel.SpamPolicyViewModel::class.java -> {
                xyz.desent.presentation.ui.settings.viewmodel.SpamPolicyViewModel(
                    spamFilterRepository = appContainer.spamFilterRepository,
                    privateStorageRepository = appContainer.privateStorageRepository,
                    preferencesManager = appContainer.preferencesManager,
                    mailFolderRepository = appContainer.mailFolderRepository
                ) as T
            }
            xyz.desent.presentation.ui.settings.viewmodel.ImagePolicyViewModel::class.java -> {
                xyz.desent.presentation.ui.settings.viewmodel.ImagePolicyViewModel(
                    spamFilterRepository = appContainer.spamFilterRepository,
                    privateStorageRepository = appContainer.privateStorageRepository,
                    preferencesManager = appContainer.preferencesManager
                ) as T
            }
            xyz.desent.presentation.ui.invites.viewmodel.InviteCodesViewModel::class.java -> {
                xyz.desent.presentation.ui.invites.viewmodel.InviteCodesViewModel(
                    appContainer.registrationUseCase
                ) as T
            }
            else -> {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}

class EmailDetailViewModelFactory(
    private val emailId: String,
    private val appContainer: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            EmailDetailViewModel::class.java -> {
                EmailDetailViewModel(
                    emailId,
                    appContainer.emailUseCase,
                    appContainer.emailRepository,
                    appContainer.attachmentDownloader,
                    appContainer.trainSpamUseCase,
                    appContainer.remoteImagePolicyStateFactory,
                    appContainer.privateStorageUseCase,
                    appContainer.contactProfileResolver,
                    appContainer.faviconResolver,
                    pgpKeyRepository = appContainer.pgpKeyManager,
                    pgpFeatureGate = appContainer.pgpFeatureGate,
                    pgpMimeParser = appContainer.pgpMimeParser,
                    pgpAttachmentCache = appContainer.pgpAttachmentCache,
                    mailFolderRepository = appContainer.mailFolderRepository
                ) as T
            }
            else -> {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}

class SpamDetailViewModelFactory(
    private val emailId: String,
    private val appContainer: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            SpamDetailViewModel::class.java -> {
                SpamDetailViewModel(
                    emailId,
                    appContainer.emailRepository,
                    appContainer.emailUseCase,
                    appContainer.trainSpamUseCase,
                    appContainer.remoteImagePolicyStateFactory,
                    appContainer.contactProfileResolver,
                appContainer.faviconResolver
                ) as T
            }
            else -> {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}

/**
 * Factory for the Security alerts screen. [initialAlertId] comes from the
 * `desent://security?alertId=` notification deep link (null = plain list);
 * [ownerNpub] scopes the screen to one specific account arriving from the
 * account switcher's per-account section (null = the active account).
 */
class SecurityAlertsViewModelFactory(
    private val initialAlertId: String?,
    private val ownerNpub: String?,
    private val appContainer: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            xyz.desent.presentation.ui.security.viewmodel.SecurityAlertsViewModel::class.java -> {
                xyz.desent.presentation.ui.security.viewmodel.SecurityAlertsViewModel(
                    appContainer.securityAlertDao,
                    appContainer.securityConfigUseCase,
                    appContainer.preferencesManager,
                    initialAlertId,
                    ownerNpub
                ) as T
            }
            else -> {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}

/** Factory for the notifications tray (active account scope). */
class NotificationsViewModelFactory(
    private val appContainer: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            xyz.desent.presentation.ui.notifications.viewmodel.NotificationsViewModel::class.java -> {
                xyz.desent.presentation.ui.notifications.viewmodel.NotificationsViewModel(
                    appContainer.badgeNoticeDao,
                    appContainer.securityAlertDao,
                    appContainer.preferencesManager
                ) as T
            }
            else -> {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
