package xyz.desent.presentation.ui.files

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-off for the END-23 share-intent target: MainActivity stashes URIs
 * received via ACTION_SEND / ACTION_SEND_MULTIPLE here and navigates to the
 * Files screen, whose ViewModel drains the queue into the upload flow.
 */
class PendingUploads {
    private val _uploads = MutableStateFlow<List<Uri>>(emptyList())
    val uploads: StateFlow<List<Uri>> = _uploads.asStateFlow()

    fun set(uris: List<Uri>) {
        _uploads.value = uris
    }

    fun clear() {
        _uploads.value = emptyList()
    }
}
