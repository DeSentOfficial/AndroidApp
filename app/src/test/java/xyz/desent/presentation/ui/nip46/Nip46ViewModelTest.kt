package xyz.desent.presentation.ui.nip46

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nostr.id.Identity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.nip46.Nip46BunkerService
import xyz.desent.data.nip46.Nip46Pairing
import xyz.desent.data.nip46.Nip46PermissionProfile

/**
 * Viewing scope of the remote-signing screen: the pairing list follows the
 * account tapped in the switcher sheet (via [Nip46ViewModel.setViewedAccount]),
 * defaulting to the active identity. The bunker runtime itself always signs
 * with the active identity, so a non-active view is flagged read-only via
 * [Nip46ViewModel.isViewingOtherAccount].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Nip46ViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private val activeIdentity = Identity.create(
        "d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa"
    )
    private val otherIdentity = Identity.create(
        "a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00"
    )

    private val activeHex = activeIdentity.publicKey.toHexString()
    private val otherHex = otherIdentity.publicKey.toHexString()
    private val activeNpub = Bech32Utils.hexToNpub(activeHex)
    private val otherNpub = Bech32Utils.hexToNpub(otherHex)

    private lateinit var bunkerService: Nip46BunkerService
    private lateinit var secureKeyManager: SecureKeyManager

    private val pairings = MutableStateFlow<List<Nip46Pairing>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        bunkerService = mockk()
        secureKeyManager = mockk()

        every { bunkerService.pairings } returns pairings
        every { bunkerService.pendingSignPrompt } returns MutableStateFlow(null)
        coEvery { secureKeyManager.getIdentityFromStoredNSEC() } returns Result.success(activeIdentity)

        pairings.value = listOf(
            pairing("sess-active", activeHex),
            pairing("sess-other", otherHex)
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeVm() = Nip46ViewModel(
        bunkerService,
        mockk(relaxed = true),
        secureKeyManager
    )

    private fun pairing(sessionPubkey: String, userPubkey: String) = Nip46Pairing(
        sessionPubkey = sessionPubkey,
        userPubkey = userPubkey,
        label = "device",
        profile = Nip46PermissionProfile.GENERAL_NOSTR,
        pairedAt = 1L,
        lastUsedAt = 1L,
        expiresAt = null,
        pairingSecretHash = "hash-$sessionPubkey"
    )

    @Test
    fun defaultsToActiveAccountPairings() = runTest(mainDispatcher) {
        val vm = makeVm()

        assertEquals(listOf("sess-active"), vm.activePairings.value.map { it.sessionPubkey })
        assertFalse(vm.isViewingOtherAccount.value)
    }

    @Test
    fun setViewedAccount_scopesPairingsToThatAccount() = runTest(mainDispatcher) {
        val vm = makeVm()

        vm.setViewedAccount(otherNpub)

        assertEquals(listOf("sess-other"), vm.activePairings.value.map { it.sessionPubkey })
        assertTrue(vm.isViewingOtherAccount.value)
    }

    @Test
    fun setViewedAccount_withActiveNpub_isNotReadOnly() = runTest(mainDispatcher) {
        val vm = makeVm()

        vm.setViewedAccount(activeNpub)

        assertEquals(listOf("sess-active"), vm.activePairings.value.map { it.sessionPubkey })
        assertFalse(vm.isViewingOtherAccount.value)
    }

    @Test
    fun setViewedAccount_null_resetsToActiveAccount() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.setViewedAccount(otherNpub)
        assertEquals(listOf("sess-other"), vm.activePairings.value.map { it.sessionPubkey })

        vm.setViewedAccount(null)

        assertEquals(listOf("sess-active"), vm.activePairings.value.map { it.sessionPubkey })
        assertFalse(vm.isViewingOtherAccount.value)
    }

    @Test
    fun setViewedAccount_invalidNpub_fallsBackToActive() = runTest(mainDispatcher) {
        val vm = makeVm()

        vm.setViewedAccount("not-an-npub")

        assertEquals(listOf("sess-active"), vm.activePairings.value.map { it.sessionPubkey })
        // Undecodable npub never reports read-only for the active account.
        assertFalse(vm.isViewingOtherAccount.value)
    }
}
