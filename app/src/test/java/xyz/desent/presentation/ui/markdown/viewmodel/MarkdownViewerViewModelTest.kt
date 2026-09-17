package xyz.desent.presentation.ui.markdown.viewmodel

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.markdown.MarkdownFileIO
import xyz.desent.domain.model.PrivateNote
import xyz.desent.domain.usecase.PrivateStorageUseCase

/**
 * [MarkdownViewerViewModel]: load/edit/save lifecycle for device markdown
 * documents, the read-only fallback (save-a-copy) when the source location
 * denies writes, and the NIP-78 save-as-note flow. The file IO is mocked so
 * no ContentResolver is needed.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MarkdownViewerViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val fileUri = "content://x/documents/primary:Download/notes.md"

    private lateinit var io: MarkdownFileIO
    private lateinit var useCase: PrivateStorageUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        io = mockk()
        useCase = mockk(relaxed = true)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeVm(
        savedState: SavedStateHandle = SavedStateHandle(),
        fileBody: Result<String> = Result.success("# Notes")
    ): MarkdownViewerViewModel {
        coEvery { io.read(any()) } returns fileBody
        return MarkdownViewerViewModel(
            fileUri = fileUri,
            io = io,
            useCase = useCase,
            savedState = savedState
        )
    }

    @Test
    fun `loads file into view state with derived name`() = runTest(mainDispatcher) {
        val vm = makeVm()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals("# Notes", vm.uiState.value.body)
        assertEquals("notes.md", vm.uiState.value.fileName)
        assertEquals(MarkdownViewMode.VIEW, vm.uiState.value.mode)
        assertFalse(vm.uiState.value.isDirty)
        assertNull(vm.uiState.value.loadError)
    }

    @Test
    fun `read failure surfaces load error`() = runTest(mainDispatcher) {
        val vm = makeVm(fileBody = Result.failure(IllegalStateException("gone")))

        assertFalse(vm.uiState.value.isLoading)
        assertEquals("gone", vm.uiState.value.loadError)
    }

    @Test
    fun `retry reloads and clears the error`() = runTest(mainDispatcher) {
        val vm = makeVm(fileBody = Result.failure(IllegalStateException("gone")))
        coEvery { io.read(any()) } returns Result.success("ok")

        vm.retry()

        assertNull(vm.uiState.value.loadError)
        assertEquals("ok", vm.uiState.value.body)
    }

    @Test
    fun `edit marks dirty and cancel reverts to baseline`() = runTest(mainDispatcher) {
        val vm = makeVm()

        vm.enterEdit()
        vm.onBodyChange("# Changed")
        assertTrue(vm.uiState.value.isDirty)

        vm.cancelEdit()

        assertEquals("# Notes", vm.uiState.value.body)
        assertFalse(vm.uiState.value.isDirty)
        assertEquals(MarkdownViewMode.VIEW, vm.uiState.value.mode)
    }

    @Test
    fun `save writes back and returns to view`() = runTest(mainDispatcher) {
        coEvery { io.write(any(), any()) } returns Result.success(Unit)
        val vm = makeVm()

        vm.enterEdit()
        vm.onBodyChange("# Changed")
        vm.save()

        coVerify { io.write(any(), "# Changed") }
        assertFalse(vm.uiState.value.isDirty)
        assertEquals(MarkdownViewMode.VIEW, vm.uiState.value.mode)
        assertEquals("Saved", vm.uiState.value.toast)
    }

    @Test
    fun `save on a read-only location emits save-copy event`() = runTest(mainDispatcher) {
        coEvery { io.write(any(), any()) } returns Result.failure(SecurityException("no write grant"))
        val vm = makeVm()
        // The events flow has no replay: the collector must be active before save.
        val firstEvent = async { vm.events.first() }

        vm.enterEdit()
        vm.onBodyChange("# Changed")
        vm.save()

        withTimeout(1000) {
            assertTrue(firstEvent.await() is MarkdownViewerEvent.LaunchSaveCopy)
        }
        assertTrue(vm.uiState.value.isDirty)
        assertNull(vm.uiState.value.toast)
    }

    @Test
    fun `saveCopyTo writes the copy and clears dirty`() = runTest(mainDispatcher) {
        coEvery { io.write(any(), any()) } returns Result.success(Unit)
        val vm = makeVm()

        vm.enterEdit()
        vm.onBodyChange("# Changed")
        vm.saveCopyTo("content://x/copy.md")

        assertFalse(vm.uiState.value.isDirty)
        assertEquals(MarkdownViewMode.VIEW, vm.uiState.value.mode)
        assertEquals("Copy saved", vm.uiState.value.toast)
    }

    @Test
    fun `saveAsNote creates a nip78 note titled from the file`() = runTest(mainDispatcher) {
        coEvery { useCase.activeOwnerNpub() } returns "npub1me"
        coEvery { useCase.saveNote(any()) } returns Result.success(Unit)
        val vm = makeVm()
        val noteSlot = slot<PrivateNote>()

        vm.saveAsNote()

        coVerify { useCase.saveNote(capture(noteSlot)) }
        assertEquals("notes", noteSlot.captured.title)
        assertEquals("# Notes", noteSlot.captured.body)
        assertEquals("npub1me", noteSlot.captured.ownerNpub)
        assertTrue(noteSlot.captured.dTag.startsWith("desent:note:"))
        assertEquals("Saved to notes", vm.uiState.value.toast)
        assertFalse(vm.uiState.value.isSavingAsNote)
    }

    @Test
    fun `saveAsNote without an account toasts`() = runTest(mainDispatcher) {
        coEvery { useCase.activeOwnerNpub() } returns null
        val vm = makeVm()

        vm.saveAsNote()

        assertEquals("No active account", vm.uiState.value.toast)
        coVerify(exactly = 0) { useCase.saveNote(any()) }
    }

    @Test
    fun `restores body from saved state without touching the file`() = runTest(mainDispatcher) {
        val savedState = SavedStateHandle(mapOf("mdfile:body" to "# Restored"))
        val vm = makeVm(savedState)

        assertEquals("# Restored", vm.uiState.value.body)
        assertFalse(vm.uiState.value.isLoading)
        coVerify(exactly = 0) { io.read(any()) }
    }

    @Test
    fun `fileNameFromUri handles saf ids and file paths`() {
        assertEquals(
            "notes.md",
            MarkdownViewerViewModel.fileNameFromUri("content://x/documents/primary:Download/notes.md")
        )
        assertEquals(
            "README.md",
            MarkdownViewerViewModel.fileNameFromUri("file:///sdcard/Docs/README.md")
        )
        assertEquals(
            "a.md",
            MarkdownViewerViewModel.fileNameFromUri("content://x/y/a.md?openWith=1")
        )
        assertEquals("document.md", MarkdownViewerViewModel.fileNameFromUri("content:"))
    }
}
