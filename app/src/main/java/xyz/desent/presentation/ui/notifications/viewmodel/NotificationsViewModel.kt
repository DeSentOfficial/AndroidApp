package xyz.desent.presentation.ui.notifications.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import xyz.desent.data.local.database.dao.BadgeNoticeDao
import xyz.desent.data.local.database.dao.BadgeNoticeJoinRow
import xyz.desent.data.local.database.dao.SecurityAlertDao
import xyz.desent.data.local.database.entity.SecurityAlertEntity
import xyz.desent.data.local.preferences.PreferencesManager

sealed interface NotificationRow {
    val eventId: String
    val timestamp: Long
    val isSeen: Boolean

    data class BadgeAward(val row: BadgeNoticeJoinRow) : NotificationRow {
        override val eventId: String get() = row.eventId
        override val timestamp: Long get() = row.receivedAt
        override val isSeen: Boolean get() = row.isSeen
    }

    data class Security(val alert: SecurityAlertEntity) : NotificationRow {
        override val eventId: String get() = alert.eventId
        override val timestamp: Long get() = alert.receivedAt
        override val isSeen: Boolean get() = alert.isSeen
    }
}

data class NotificationsUiState(
    val rows: List<NotificationRow> = emptyList(),
    val unseenBadgeCount: Int = 0,
    val unseenSecurityCount: Int = 0,
    /** Non-null while a badge detail sheet is open. */
    val selectedBadgeEventId: String? = null,
    val toast: String? = null
)

/**
 * Backs the notifications tray: one chronological list of the active
 * account's badge-award notices (`badge_notices`, ANDROID_BADGES.md §7)
 * and login-security alerts (`security_alerts`). Badge rows mark seen on
 * open and show the badge detail sheet; security rows navigate to the
 * existing Security screen (which marks seen there as today).
 */
class NotificationsViewModel(
    private val badgeNoticeDao: BadgeNoticeDao,
    private val securityAlertDao: SecurityAlertDao,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    private var ownerNpub: String? = null

    init {
        viewModelScope.launch {
            // Same 30-day retention clock as the relay's NIP-40 expiration
            // on the notice wraps (mirrors SecurityAlertsViewModel).
            val cutoff = System.currentTimeMillis() - NOTICE_RETENTION_MS
            runCatching { badgeNoticeDao.purgeOlderThan(cutoff) }

            val npub = preferencesManager.npubKey.firstOrNull() ?: return@launch
            ownerNpub = npub

            combine(
                badgeNoticeDao.observeForOwner(npub),
                securityAlertDao.observeForOwner(npub)
            ) { badges, alerts ->
                val rows = (badges.map { NotificationRow.BadgeAward(it) } +
                    alerts.map { NotificationRow.Security(it) })
                    .sortedByDescending { it.timestamp }
                val badgeIds = badges.map { it.eventId }.toSet()
                rows to badgeIds
            }.onEach { (rows, badgeIds) ->
                _uiState.value = _uiState.value.copy(
                    rows = rows,
                    // Drop a selected badge row that was purged.
                    selectedBadgeEventId = _uiState.value.selectedBadgeEventId
                        ?.takeIf { it in badgeIds }
                )
            }.launchIn(viewModelScope)

            badgeNoticeDao.observeUnseenCount(npub)
                .onEach { n -> _uiState.value = _uiState.value.copy(unseenBadgeCount = n) }
                .launchIn(viewModelScope)

            securityAlertDao.observeUnseenCount(npub)
                .onEach { n -> _uiState.value = _uiState.value.copy(unseenSecurityCount = n) }
                .launchIn(viewModelScope)
        }
    }

    /** Open a badge row (marks it seen + opens the detail sheet) or close (null). */
    fun selectBadge(eventId: String?) {
        _uiState.value = _uiState.value.copy(selectedBadgeEventId = eventId)
        if (eventId != null) {
            viewModelScope.launch { runCatching { badgeNoticeDao.markSeen(eventId) } }
        }
    }

    fun markAllSeen() {
        viewModelScope.launch {
            val npub = ownerNpub ?: preferencesManager.npubKey.firstOrNull() ?: return@launch
            runCatching { badgeNoticeDao.markAllSeen(npub) }
            runCatching { securityAlertDao.markAllSeen(npub) }
            _uiState.value = _uiState.value.copy(toast = "All notifications marked read")
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    companion object {
        private const val NOTICE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
