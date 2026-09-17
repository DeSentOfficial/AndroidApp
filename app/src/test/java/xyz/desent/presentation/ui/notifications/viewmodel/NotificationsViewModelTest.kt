package xyz.desent.presentation.ui.notifications.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xyz.desent.data.local.database.dao.BadgeNoticeDao
import xyz.desent.data.local.database.dao.BadgeNoticeJoinRow
import xyz.desent.data.local.database.dao.SecurityAlertDao
import xyz.desent.data.local.database.entity.SecurityAlertEntity
import xyz.desent.data.local.preferences.PreferencesManager

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private val npub = "npub1active"

    private lateinit var badgeNoticeDao: BadgeNoticeDao
    private lateinit var securityAlertDao: SecurityAlertDao
    private lateinit var preferencesManager: PreferencesManager

    private fun badgeRow(
        eventId: String,
        receivedAt: Long,
        isSeen: Boolean = false,
        slug: String = "early-adopter",
        defName: String? = "Early Adopter"
    ) = BadgeNoticeJoinRow(
        eventId = eventId,
        ownerNpub = npub,
        slug = slug,
        subject = "New badge: ${defName ?: slug}",
        body = "You earned it",
        receivedAt = receivedAt,
        isSeen = isSeen,
        defName = defName,
        defDescription = null,
        defImageUrl = null,
        defThumbUrl = null,
        defIconName = null,
        defColor = null,
        awardEventId = "award-$eventId",
        awardedAt = receivedAt / 1000
    )

    private fun securityAlert(
        eventId: String,
        receivedAt: Long,
        isSeen: Boolean = false
    ) = SecurityAlertEntity(
        eventId = eventId,
        ownerNpub = npub,
        subject = "New sign-in",
        surface = "ws",
        time = "2026-08-21 07:00 UTC",
        device = "",
        body = "",
        receivedAt = receivedAt,
        isSeen = isSeen
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        badgeNoticeDao = mockk(relaxed = true)
        securityAlertDao = mockk(relaxed = true)
        preferencesManager = mockk()

        coEvery { preferencesManager.npubKey } returns flowOf(npub)
        coEvery { badgeNoticeDao.purgeOlderThan(any()) } returns Unit
        coEvery { badgeNoticeDao.observeForOwner(npub) } returns flowOf(emptyList())
        coEvery { badgeNoticeDao.observeUnseenCount(npub) } returns flowOf(0)
        coEvery { securityAlertDao.observeForOwner(npub) } returns flowOf(emptyList())
        coEvery { securityAlertDao.observeUnseenCount(npub) } returns flowOf(0)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeVm(): NotificationsViewModel = NotificationsViewModel(
        badgeNoticeDao = badgeNoticeDao,
        securityAlertDao = securityAlertDao,
        preferencesManager = preferencesManager
    )

    @Test
    fun init_mergesBadgeAndSecurityRowsNewestFirst() = runTest(mainDispatcher) {
        coEvery { badgeNoticeDao.observeForOwner(npub) } returns flowOf(
            listOf(badgeRow("b1", receivedAt = 1_000L))
        )
        coEvery { securityAlertDao.observeForOwner(npub) } returns flowOf(
            listOf(securityAlert("s1", receivedAt = 2_000L), securityAlert("s2", receivedAt = 500L))
        )

        val vm = makeVm()

        val ids = vm.uiState.value.rows.map { it.eventId }
        assertEquals(listOf("s1", "b1", "s2"), ids)
    }

    @Test
    fun init_sumsUnseenAcrossBothSources() = runTest(mainDispatcher) {
        coEvery { badgeNoticeDao.observeUnseenCount(npub) } returns flowOf(2)
        coEvery { securityAlertDao.observeUnseenCount(npub) } returns flowOf(3)

        val vm = makeVm()

        assertEquals(2, vm.uiState.value.unseenBadgeCount)
        assertEquals(3, vm.uiState.value.unseenSecurityCount)
    }

    @Test
    fun selectBadge_marksNoticeSeen() = runTest(mainDispatcher) {
        val vm = makeVm()

        vm.selectBadge("b1")

        assertEquals("b1", vm.uiState.value.selectedBadgeEventId)
        coVerify { badgeNoticeDao.markSeen("b1") }
    }

    @Test
    fun selectBadge_nullClosesSheetWithoutDaoCall() = runTest(mainDispatcher) {
        val vm = makeVm()

        vm.selectBadge("b1")
        vm.selectBadge(null)

        assertEquals(null, vm.uiState.value.selectedBadgeEventId)
        coVerify(exactly = 1) { badgeNoticeDao.markSeen(any()) }
    }

    @Test
    fun markAllSeen_hitsBothTables() = runTest(mainDispatcher) {
        val vm = makeVm()

        vm.markAllSeen()

        coVerify { badgeNoticeDao.markAllSeen(npub) }
        coVerify { securityAlertDao.markAllSeen(npub) }
        assertEquals("All notifications marked read", vm.uiState.value.toast)
    }

    @Test
    fun init_purgesNoticesOlderThanThirtyDays() = runTest(mainDispatcher) {
        makeVm()

        coVerify {
            badgeNoticeDao.purgeOlderThan(any())
        }
    }

    @Test
    fun init_withoutActiveAccountStaysEmpty() = runTest(mainDispatcher) {
        coEvery { preferencesManager.npubKey } returns flowOf(null)

        val vm = makeVm()

        assertEquals(emptyList<NotificationRow>(), vm.uiState.value.rows)
    }
}
