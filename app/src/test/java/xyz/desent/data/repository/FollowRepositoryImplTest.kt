package xyz.desent.data.repository

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.local.database.dao.FollowDao
import xyz.desent.data.local.database.entity.FollowEntity
import xyz.desent.data.mapper.FollowMapper
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.domain.repository.ContactListEntry
import xyz.desent.domain.repository.NostrPublisher
import xyz.desent.domain.repository.RelayRepository

class FollowRepositoryImplTest {

    private lateinit var followDao: FollowDao
    private lateinit var relayRepository: RelayRepository
    private lateinit var nostrEventProcessor: NostrEventProcessor
    private lateinit var nostrPublisher: NostrPublisher
    private lateinit var repo: FollowRepositoryImpl

    private val myNpub = "npub1me"
    private val targetNpub = "npub1target"

    @Before
    fun setUp() {
        followDao = mockk(relaxed = true)
        relayRepository = mockk(relaxed = true)
        nostrEventProcessor = mockk(relaxed = true)
        nostrPublisher = mockk(relaxed = true)
        repo = FollowRepositoryImpl(
            followDao = followDao,
            followMapper = FollowMapper(),
            relayRepository = relayRepository,
            nostrEventProcessor = nostrEventProcessor,
            nostrPublisher = nostrPublisher
        )
    }

    @Test
    fun followUser_insertsRowAndPublishesContactList() = runBlocking {
        coEvery { followDao.getFollowById("$myNpub-$targetNpub") } returns null
        coEvery { nostrPublisher.publishContactList(any()) } returns Result.success(Unit)

        val result = repo.followUser(myNpub, targetNpub, petname = "alice")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { followDao.insertFollow(any()) }
        coVerify(exactly = 1) { nostrPublisher.publishContactList(any()) }
    }

    @Test
    fun followUser_publishesEntriesFromOwnOutgoingFollowsOnly() = runBlocking {
        // The DB contains: my real follow, PLUS a reverse row stored by the
        // inbound kind-3 sync (target -> me), a device-local favorite-only
        // row, and a row where I'm the one being followed by someone else
        // entirely. Only MY real outgoing follow must appear in the published
        // contact list.
        val myOutgoing = entity(id = "$myNpub-$targetNpub", follower = myNpub, following = targetNpub, petname = "alice")
        val reverseRow = entity(id = "$targetNpub-$myNpub", follower = targetNpub, following = myNpub)
        val localOnlyFavorite = entity(
            id = "$myNpub-npub1other", follower = myNpub, following = "npub1other",
            isFavorite = true, isLocalOnly = true
        )
        val someoneElseFollowsMe = entity(id = "npub1other-$myNpub", follower = "npub1other", following = myNpub)

        coEvery { followDao.getFollowById("$myNpub-$targetNpub") } returns null
        coEvery { followDao.getFollowsByFollower(myNpub) } returns
            listOf(myOutgoing, reverseRow, localOnlyFavorite, someoneElseFollowsMe)
        val publishSlot = slot<List<ContactListEntry>>()
        coEvery { nostrPublisher.publishContactList(capture(publishSlot)) } returns Result.success(Unit)

        repo.followUser(myNpub, targetNpub, petname = "alice")

        val published = publishSlot.captured
        // Only rows where I am the follower are published.
        assertEquals(1, published.size)
        assertEquals(targetNpub, published[0].followingNpub)
        // Reverse rows (where I'm the following party) are excluded.
        assertTrue(published.none { it.followingNpub == myNpub })
        // Device-local favorite-only rows are excluded.
        assertTrue(published.none { it.followingNpub == "npub1other" })
    }

    @Test
    fun followUser_preservesExistingFavoriteWhenUpserting() = runBlocking {
        val existingFavorite = entity(
            id = "$myNpub-$targetNpub",
            follower = myNpub,
            following = targetNpub,
            isFavorite = true
        )
        coEvery { followDao.getFollowById("$myNpub-$targetNpub") } returns existingFavorite
        val insertedSlot = slot<FollowEntity>()
        coEvery { followDao.insertFollow(capture(insertedSlot)) } just Runs
        coEvery { followDao.getFollowsByFollower(myNpub) } returns emptyList()
        coEvery { nostrPublisher.publishContactList(any()) } returns Result.success(Unit)

        repo.followUser(myNpub, targetNpub, petname = "newpet")

        assertTrue("Existing favorite flag must be preserved on re-follow", insertedSlot.captured.isFavorite)
        assertEquals("newpet", insertedSlot.captured.petname)
    }

    @Test
    fun unfollowUser_deletesRowAndRepublishes() = runBlocking {
        coEvery { followDao.getFollowsByFollower(myNpub) } returns emptyList()
        coEvery { nostrPublisher.publishContactList(any()) } returns Result.success(Unit)

        val result = repo.unfollowUser(myNpub, targetNpub)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { followDao.deleteFollow(myNpub, targetNpub) }
        coVerify(exactly = 1) { nostrPublisher.publishContactList(any()) }
    }

    @Test
    fun unfollowUser_canPublishEmptyList() = runBlocking {
        // kind 3 is replaceable — unfollowing the last user publishes an empty list.
        coEvery { followDao.getFollowsByFollower(myNpub) } returns emptyList()
        val publishSlot = slot<List<ContactListEntry>>()
        coEvery { nostrPublisher.publishContactList(capture(publishSlot)) } returns Result.success(Unit)

        repo.unfollowUser(myNpub, targetNpub)

        assertTrue(publishSlot.captured.isEmpty())
    }

    @Test
    fun followUser_succeedsEvenWhenPublishFails_localWriteIsAuthoritative() = runBlocking {
        // The local row write is the source of truth; a publish failure is
        // logged but does not fail followUser — the next follow/unfollow
        // republishes the full contact list, self-healing the relay state.
        coEvery { followDao.getFollowById(any()) } returns null
        coEvery { followDao.getFollowsByFollower(myNpub) } returns emptyList()
        coEvery { nostrPublisher.publishContactList(any()) } returns
            Result.failure(Exception("relay down"))

        val result = repo.followUser(myNpub, targetNpub)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { followDao.insertFollow(any()) }
    }

    @Test
    fun observeFollowingNpubs_filtersToOwnOutgoingRows() = runBlocking {
        val myOutgoing = entity(id = "$myNpub-$targetNpub", follower = myNpub, following = targetNpub)
        val reverse = entity(id = "$targetNpub-$myNpub", follower = targetNpub, following = myNpub)
        every { followDao.observeFollowsByFollower(myNpub) } returns flowOf(listOf(myOutgoing, reverse))

        val npubs = repo.observeFollowingNpubs(myNpub).first()

        assertEquals(setOf(targetNpub), npubs)
    }

    @Test
    fun observeFollowingNpubs_isEmptyWhenNoFollows() = runBlocking {
        every { followDao.observeFollowsByFollower(myNpub) } returns flowOf(emptyList())

        val npubs = repo.observeFollowingNpubs(myNpub).first()

        assertTrue(npubs.isEmpty())
    }

    @Test
    fun observeFollowingNpubs_excludesLocalOnlyFavoriteRows() = runBlocking {
        // Favorite-only rows are not real follows: they must not leak into
        // the "who I follow" set (DM gating, summary stats, subscriptions).
        val realFollow = entity(id = "$myNpub-$targetNpub", follower = myNpub, following = targetNpub)
        val localOnlyFavorite = entity(
            id = "$myNpub-npub1other", follower = myNpub, following = "npub1other",
            isFavorite = true, isLocalOnly = true
        )
        every { followDao.observeFollowsByFollower(myNpub) } returns
            flowOf(listOf(realFollow, localOnlyFavorite))

        val npubs = repo.observeFollowingNpubs(myNpub).first()

        assertEquals(setOf(targetNpub), npubs)
    }

    // ---------------- toggleFavorite ----------------

    @Test
    fun toggleFavorite_newFavorite_createsLocalOnlyRowAndNoSyntheticReverse() = runBlocking {
        // Favoriting a user I don't follow and who doesn't follow me.
        coEvery { followDao.getFollowById("$myNpub-$targetNpub") } returns null
        coEvery { followDao.getFollowById("$targetNpub-$myNpub") } returns null
        val insertSlot = slot<FollowEntity>()
        coEvery { followDao.insertFollow(capture(insertSlot)) } just Runs

        val favorited = repo.toggleFavorite(myNpub, targetNpub)

        assertTrue(favorited)
        // Exactly ONE row inserted: the primary (me -> target) row, marked
        // favorite + local-only. The old bug inserted a synthetic reverse
        // (target -> me) row here, which fabricated a follower and fired a
        // false "started following you" notification.
        coVerify(exactly = 1) { followDao.insertFollow(any()) }
        val inserted = insertSlot.captured
        assertEquals("$myNpub-$targetNpub", inserted.id)
        assertTrue(inserted.isFavorite)
        assertTrue(inserted.isLocalOnly)
    }

    @Test
    fun toggleFavorite_unfavoriteLocalOnlyRow_deletesIt() = runBlocking {
        // A favorite-only row losing its star has nothing real left — it must
        // be deleted, not left behind as a phantom "following" row.
        val localOnlyFavorite = entity(
            id = "$myNpub-$targetNpub", follower = myNpub, following = targetNpub,
            isFavorite = true, isLocalOnly = true
        )
        coEvery { followDao.getFollowById("$myNpub-$targetNpub") } returns localOnlyFavorite
        coEvery { followDao.getFollowById("$targetNpub-$myNpub") } returns null

        val favorited = repo.toggleFavorite(myNpub, targetNpub)

        assertTrue(!favorited)
        coVerify(exactly = 1) { followDao.deleteFollow(myNpub, targetNpub) }
        coVerify(exactly = 0) { followDao.updateFavoriteStatus(any(), any(), any()) }
        coVerify(exactly = 0) { followDao.insertFollow(any()) }
    }

    @Test
    fun toggleFavorite_realRow_updatesFlagAndSyncsReverseRow() = runBlocking {
        // Real outgoing follow (unfavorited) plus a real reverse row (target
        // actually follows me): both flags update, nothing is inserted.
        val myFollow = entity(id = "$myNpub-$targetNpub", follower = myNpub, following = targetNpub)
        val theirFollow = entity(id = "$targetNpub-$myNpub", follower = targetNpub, following = myNpub)
        coEvery { followDao.getFollowById("$myNpub-$targetNpub") } returns myFollow
        coEvery { followDao.getFollowById("$targetNpub-$myNpub") } returns theirFollow

        val favorited = repo.toggleFavorite(myNpub, targetNpub)

        assertTrue(favorited)
        coVerify(exactly = 1) { followDao.updateFavoriteStatus(targetNpub, myNpub, true) }
        coVerify(exactly = 1) { followDao.updateFavoriteStatus(myNpub, targetNpub, true) }
        coVerify(exactly = 0) { followDao.insertFollow(any()) }
        coVerify(exactly = 0) { followDao.deleteFollow(any(), any()) }
    }

    @Test
    fun followUser_upgradesLocalOnlyFavoriteToRealFollow() = runBlocking {
        // Actually following a favorited contact clears the local-only marker
        // (the row now publishes with the kind-3 list) while keeping the star.
        val localOnlyFavorite = entity(
            id = "$myNpub-$targetNpub", follower = myNpub, following = targetNpub,
            isFavorite = true, isLocalOnly = true
        )
        coEvery { followDao.getFollowById("$myNpub-$targetNpub") } returns localOnlyFavorite
        val insertedSlot = slot<FollowEntity>()
        coEvery { followDao.insertFollow(capture(insertedSlot)) } just Runs
        coEvery { followDao.getFollowsByFollower(myNpub) } returns listOf(localOnlyFavorite)
        coEvery { nostrPublisher.publishContactList(any()) } returns Result.success(Unit)

        repo.followUser(myNpub, targetNpub)

        assertTrue(insertedSlot.captured.isFavorite)
        assertTrue(!insertedSlot.captured.isLocalOnly)
    }

    private fun entity(
        id: String,
        follower: String,
        following: String,
        isFavorite: Boolean = false,
        petname: String? = null,
        isLocalOnly: Boolean = false
    ) = FollowEntity(
        id = id,
        followerNpub = follower,
        followingNpub = following,
        isFavorite = isFavorite,
        createdAt = 1000L,
        petname = petname,
        isLocalOnly = isLocalOnly
    )
}
