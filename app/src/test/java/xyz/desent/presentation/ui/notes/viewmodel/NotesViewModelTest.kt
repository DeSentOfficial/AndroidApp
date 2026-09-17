package xyz.desent.presentation.ui.notes.viewmodel

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.local.database.dao.FavoriteNoteDao
import xyz.desent.domain.model.PrivateNote
import xyz.desent.domain.usecase.PrivateStorageUseCase
import xyz.desent.presentation.ui.notes.NoteDateGroup

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val useCase = mockk<PrivateStorageUseCase>(relaxed = true)
    private val favoriteDao = mockk<FavoriteNoteDao>(relaxed = true)

    private val notes = MutableStateFlow<List<PrivateNote>>(emptyList())
    private val favoriteIds = MutableStateFlow<List<String>>(emptyList())

    private val nowSeconds = System.currentTimeMillis() / 1000

    private fun note(
        id: String,
        title: String,
        folder: String = "",
        body: String = "",
        ageSeconds: Long = 0
    ) = PrivateNote(
        id = id,
        title = title,
        body = body,
        updatedAt = nowSeconds - ageSeconds,
        folder = folder,
        ownerNpub = OWNER,
        dTag = "desent:note:$id"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { useCase.activeOwnerNpub() } returns OWNER
        every { useCase.observeNotes(OWNER) } returns notes
        coEvery { useCase.subscribeToOwnPrivateStorage() } returns Result.success(Unit)
        every { favoriteDao.observeNoteIds(OWNER) } returns favoriteIds
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = NotesViewModel(useCase, favoriteDao)

    @Test
    fun folderTree_isFlattenedDepthFirstWithCounts() = runTest(dispatcher) {
        notes.value = listOf(
            note("n1", "Roadmap", folder = "Work/Projects"),
            note("n2", "Specs", folder = "Work/Projects"),
            note("n3", "Expenses", folder = "Work/Docs"),
            note("n4", "Gift ideas", folder = "Personal"),
            note("n5", "Scratch")
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(
            listOf(
                FolderSummary("Personal", "Personal", 0, 1),
                FolderSummary("Work", "Work", 0, 3),
                FolderSummary("Work/Docs", "Docs", 1, 1),
                FolderSummary("Work/Projects", "Projects", 1, 2)
            ),
            vm.uiState.value.folders
        )
    }

    @Test
    fun allView_isSortedByDateWithSectionHeaders() = runTest(dispatcher) {
        notes.value = listOf(
            note("old", "Old note", ageSeconds = 30L * 86_400),
            note("fresh", "Fresh note", ageSeconds = 3_600),
            note("older", "Older note", ageSeconds = 20L * 86_400)
        )
        val vm = viewModel()
        advanceUntilIdle()

        val items = vm.uiState.value.items
        assertEquals("fresh", (items[1] as NotesListItem.NoteItem).note.id)
        // Older-but-more-recent (20d) sorts ahead of old (30d), same bucket,
        // without repeating the section header.
        assertEquals("older", (items[3] as NotesListItem.NoteItem).note.id)
        assertEquals(NoteDateGroup.TODAY, (items[0] as NotesListItem.DateHeader).group)
        assertEquals(NoteDateGroup.EARLIER, (items[2] as NotesListItem.DateHeader).group)
        assertEquals("old", (items[4] as NotesListItem.NoteItem).note.id)
        assertEquals(5, items.size)
    }

    @Test
    fun favoritesView_listsOnlyFavorites() = runTest(dispatcher) {
        notes.value = listOf(
            note("n1", "One"),
            note("n2", "Two"),
            note("n3", "Three")
        )
        favoriteIds.value = listOf("n3", "n1")
        val vm = viewModel()
        advanceUntilIdle()

        vm.selectView(NotesView.Favorites)
        advanceUntilIdle()

        val ids = vm.uiState.value.items
            .filterIsInstance<NotesListItem.NoteItem>()
            .map { it.note.id }
        assertEquals(listOf("n1", "n3"), ids)
    }

    @Test
    fun favoriteFlagIsCarriedOntoRows() = runTest(dispatcher) {
        notes.value = listOf(note("n1", "One"), note("n2", "Two"))
        favoriteIds.value = listOf("n2")
        val vm = viewModel()
        advanceUntilIdle()

        val rows = vm.uiState.value.items.filterIsInstance<NotesListItem.NoteItem>()
        assertEquals(listOf(false, true), rows.map { it.isFavorite })
    }

    @Test
    fun folderView_matchesExactPathOnly() = runTest(dispatcher) {
        notes.value = listOf(
            note("root", "Root", folder = "Work"),
            note("nested", "Nested", folder = "Work/Projects"),
            note("other", "Other", folder = "Personal")
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.selectView(NotesView.Folder("Work"))
        advanceUntilIdle()

        val ids = vm.uiState.value.items
            .filterIsInstance<NotesListItem.NoteItem>()
            .map { it.note.id }
        assertEquals(listOf("root"), ids)
    }

    @Test
    fun search_overridesViewAndMatchesTitleBodyAndFolder() = runTest(dispatcher) {
        notes.value = listOf(
            note("n1", "Roadmap", folder = "Work", body = "nothing here"),
            note("n2", "Groceries", folder = "Home", body = "buy roadmap coffee"),
            note("n3", "Spec", folder = "Work/Projects", body = "details")
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.selectView(NotesView.Folder("Work"))
        vm.onSearchChange("roadmap")
        advanceUntilIdle()

        // Folder view would show only n1; the search widens to all folders.
        val ids = vm.uiState.value.items
            .filterIsInstance<NotesListItem.NoteItem>()
            .map { it.note.id }
        assertEquals(listOf("n1", "n2"), ids)
    }

    @Test
    fun noActiveAccount_showsEmptyLoadedState() = runTest(dispatcher) {
        coEvery { useCase.activeOwnerNpub() } returns null
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.items.isEmpty())
    }

    private companion object {
        const val OWNER = "npub1test"
    }
}
