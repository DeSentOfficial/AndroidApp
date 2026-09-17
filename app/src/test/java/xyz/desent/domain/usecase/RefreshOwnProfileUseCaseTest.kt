package xyz.desent.domain.usecase

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xyz.desent.data.RelayConfig
import xyz.desent.data.repository.NostrRepository

/**
 * Contract of [RefreshOwnProfileUseCase]: the own kind-0 profile refresh must
 * run the settled switch-path sequence — DeSent relay fetch first, public
 * bootstrap fetch second — on the use case's own scope, so it survives the
 * teardown of the screen that triggered it (login navigation / splash pop)
 * and tolerates either fetch failing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshOwnProfileUseCaseTest {

    private lateinit var nostrRepository: NostrRepository
    private lateinit var scope: TestScope

    @Before
    fun setUp() {
        nostrRepository = mockk(relaxed = true)
        scope = TestScope(StandardTestDispatcher())
        // Result<T> is a value class, which relaxed mocks cannot synthesize.
        coEvery { nostrRepository.fetchOwnProfileFromRelays(any(), any()) } returns
            Result.success(Unit)
    }

    private fun useCase() = RefreshOwnProfileUseCase(nostrRepository, scope)

    @Test
    fun refresh_fetchesHomeRelayFirst_thenPublicBootstrapRelays() {
        useCase().launchInBackground(NPUB)

        scope.advanceUntilIdle()

        coVerify(exactly = 1) { nostrRepository.fetchUserMetadata(NPUB) }
        coVerifyOrder {
            nostrRepository.fetchUserMetadata(NPUB)
            nostrRepository.fetchOwnProfileFromRelays(NPUB, RelayConfig.PUBLIC_PROFILE_RELAYS)
        }
    }

    @Test
    fun graceDelay_holdsOffTheFirstFetchUntilItElapses() {
        useCase().launchInBackground(NPUB, graceMs = 2000L)

        scope.testScheduler.advanceTimeBy(1999L)
        scope.testScheduler.runCurrent()
        coVerify(exactly = 0) { nostrRepository.fetchUserMetadata(any()) }

        scope.advanceUntilIdle()
        coVerify(exactly = 1) { nostrRepository.fetchUserMetadata(NPUB) }
    }

    @Test
    fun homeRelayFetchFailure_stillRunsPublicBootstrapFetch() {
        coEvery { nostrRepository.fetchUserMetadata(NPUB) } throws RuntimeException("relay down")

        useCase().launchInBackground(NPUB)

        scope.advanceUntilIdle()

        coVerify(exactly = 1) {
            nostrRepository.fetchOwnProfileFromRelays(NPUB, RelayConfig.PUBLIC_PROFILE_RELAYS)
        }
    }

    @Test
    fun bootstrapFetchFailure_isNonFatal() {
        coEvery { nostrRepository.fetchOwnProfileFromRelays(any(), any()) } returns
            Result.failure(IllegalStateException("all bootstrap relays refused"))

        useCase().launchInBackground(NPUB)

        scope.advanceUntilIdle()

        coVerify(exactly = 1) { nostrRepository.fetchUserMetadata(NPUB) }
        coVerify(exactly = 1) { nostrRepository.fetchOwnProfileFromRelays(any(), any()) }
    }

    @Test
    fun refresh_survivesCallerCancellation() {
        // The real-world shape of the bug: the caller (login navigation /
        // splash teardown) is cancelled right after firing the refresh. The
        // refresh must not be a child of the caller's job.
        val callerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scope.testScheduler))
        callerScope.launch { useCase().launchInBackground(NPUB) }
        callerScope.cancel()

        scope.advanceUntilIdle()

        coVerify(exactly = 1) { nostrRepository.fetchUserMetadata(NPUB) }
        coVerify(exactly = 1) {
            nostrRepository.fetchOwnProfileFromRelays(NPUB, RelayConfig.PUBLIC_PROFILE_RELAYS)
        }
    }

    @Test
    fun refresh_withoutGrace_runsImmediately() {
        useCase().launchInBackground(NPUB)

        scope.testScheduler.runCurrent()

        coVerify(exactly = 1) { nostrRepository.fetchUserMetadata(NPUB) }
        // No delay was inserted before the first fetch.
        assertEquals(0L, scope.testScheduler.currentTime)
    }

    private companion object {
        const val NPUB = "npub1testaccount0000000000000000000000000000000000000000000000000"
    }
}
