package xyz.desent.data.session

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.mapper.AccountMapper
import xyz.desent.domain.model.Account

/**
 * The reactive source of truth for "which account is active" and "what
 * accounts are saved on this device".
 *
 * - [activeNpub] mirrors [PreferencesManager.npubKey] so that any switch is
 *   immediately observable app-wide.
 * - [activeAccount] joins the active npub with the `accounts` table to surface
 *   display name / picture / nip05 for the avatar UI.
 * - [accounts] is the list shown in the account switcher.
 */
class SessionManager(
    private val accountDao: AccountDao,
    private val preferencesManager: PreferencesManager,
    private val accountMapper: AccountMapper = AccountMapper()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * True while an account switch ([SwitchAccountUseCase]) is in progress.
     * Screen-level UI observes this to immediately drop the previous
     * account's data and render skeletons instead of showing stale
     * profile/follows until the nav-stack reset recreates the ViewModels.
     */
    private val _isSwitchingAccount = MutableStateFlow(false)
    val isSwitchingAccount: StateFlow<Boolean> = _isSwitchingAccount.asStateFlow()

    fun setAccountSwitching(active: Boolean) {
        _isSwitchingAccount.value = active
    }

    val activeNpub: StateFlow<String?> = preferencesManager.activeNpub
        .stateIn(scope, SharingStarted.Eagerly, null)

    val accounts: StateFlow<List<Account>> = accountDao.observeAccountsJoined()
        .map { accountMapper.mapToDomainList(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activeAccount: StateFlow<Account?> = combine(
        preferencesManager.activeNpub,
        accountDao.observeAccountsJoined()
    ) { npub, entities ->
        npub?.let { target ->
            entities.firstOrNull { it.npub == target }?.let { accountMapper.mapToDomain(it) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun getActiveNpub(): String? = preferencesManager.getActiveNpub()

    /**
     * @return the active npub or throws if no account is active. Use this in
     * code paths that assume a logged-in user (signing, publishing, etc.).
     */
    suspend fun requireActiveNpub(): String {
        return preferencesManager.getActiveNpub()
            ?: throw IllegalStateException("No active account")
    }

    suspend fun setActive(npub: String) {
        preferencesManager.setActiveNpub(npub)
        accountDao.setLastActiveAt(npub, System.currentTimeMillis())
        Log.d(TAG, "Active account set to ${npub.take(8)}")
    }

    companion object {
        private const val TAG = "SessionManager"
    }
}
