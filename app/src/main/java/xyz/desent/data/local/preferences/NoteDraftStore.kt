package xyz.desent.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.firstOrNull

private val Context.noteDraftDataStore: DataStore<Preferences> by preferencesDataStore(name = "note_drafts")

/**
 * Persists in-progress note drafts to disk so they survive process death even
 * when the editor's [androidx.lifecycle.SavedStateHandle] is cleared (e.g. the
 * OS kills the process and drops the back-stack entry). Keyed by the note id,
 * or `"new"` for a freshly-created note.
 */
class NoteDraftStore(context: Context) {

    private val dataStore = context.noteDraftDataStore

    suspend fun saveDraft(key: String, json: String) {
        dataStore.edit { it[stringPreferencesKey(key)] = json }
    }

    suspend fun getDraft(key: String): String? =
        dataStore.data.firstOrNull()?.get(stringPreferencesKey(key))

    suspend fun clearDraft(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }
}
