package xyz.desent.presentation.ui.email.viewmodel

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.desent.crypto.Bech32Utils
import xyz.desent.data.avatar.FaviconResolver
import xyz.desent.domain.model.ContactProfile
import xyz.desent.domain.model.Email
import xyz.desent.domain.repository.ContactProfileResolver

/**
 * Sender-profile enrichment shared by the email screens. Each distinct
 * sender is resolved through the app-wide [ContactProfileResolver], which is
 * Room-first: cached kind-0 rows render instantly and stale/missing rows
 * refresh from the relays in the background. Because the resolver writes
 * through the same Room `users` / `contact_profile_links` caches the
 * contacts screen reads, pictures resolved here are synced back to the
 * contacts experience automatically.
 *
 * Lookup order per sender:
 *  1. the thread's nostr sender key (DeSent-to-DeSent mail), or
 *  2. the email address itself as a NIP-05 identifier (`user@domain`).
 *
 * Avatar fallback chain (see [avatars]): the resolved kind-0 `picture`,
 * else the sender domain's favicon (Room-cached availability, direct
 * `https://<domain>/favicon.ico`), else null → initials.
 */
class SenderProfileStore(
    private val resolver: ContactProfileResolver,
    private val faviconResolver: FaviconResolver,
    private val scope: CoroutineScope
) {
    /** Resolved profile per sender, keyed by lowercase email; null = none found. */
    private val _profiles = MutableStateFlow<Map<String, ContactProfile?>>(emptyMap())
    val profiles: StateFlow<Map<String, ContactProfile?>> = _profiles.asStateFlow()

    /** Final avatar URL per sender (picture ?: favicon), or null for initials. */
    private val _avatars = MutableStateFlow<Map<String, String?>>(emptyMap())
    val avatars: StateFlow<Map<String, String?>> = _avatars.asStateFlow()

    private val ensured = ConcurrentHashMap.newKeySet<String>()
    private val avatarEnsured = ConcurrentHashMap.newKeySet<String>()

    /** Idempotent per sender; safe to call on every list emission. */
    fun ensure(email: Email) {
        val key = email.senderEmail.trim().lowercase()
        if (key.isEmpty() || !ensured.add(key)) return

        val senderHex = email.threadSenderPubkey
            ?.let { runCatching { Bech32Utils.npubToHex(it) }.getOrNull() }

        if (senderHex != null) {
            observeInto(key) { resolver.observeProfile(senderHex) }
            scope.launch {
                val profile = runCatching { resolver.resolve(senderHex) }.getOrNull()
                if (profile != null) _profiles.update { it + (key to profile) }
            }
        } else {
            observeInto(key) { resolver.observeProfileByIdentifier(key) }
            scope.launch {
                val profile = runCatching { resolver.resolveByIdentifier(key) }.getOrNull()
                if (profile != null) _profiles.update { it + (key to profile) }
            }
        }

        ensureAvatar(key)
    }

    /**
     * Derives the avatar URL reactively: nostr picture wins whenever one
     * resolves (including late), favicon fills the gap, initials otherwise.
     * Nothing here blocks list rendering — values swap in as they arrive.
     */
    private fun ensureAvatar(key: String) {
        if (!avatarEnsured.add(key)) return
        val domain = FaviconResolver.domainOf(key)

        scope.launch {
            val picture = _profiles
                .map { it[key]?.picture?.takeIf { p -> !p.isNullOrBlank() } }
                .distinctUntilChanged()

            if (domain == null) {
                picture.collect { url -> _avatars.update { it + (key to url) } }
                return@launch
            }

            val favicon = MutableStateFlow<String?>(null)
            scope.launch {
                favicon.value = runCatching { faviconResolver.faviconUrlFor(key) }.getOrNull()
            }
            combine(picture, favicon) { p, f -> p ?: f }
                .distinctUntilChanged()
                .collect { url -> _avatars.update { it + (key to url) } }
        }
    }

    /** Room-only flow collection: renders the cache, swaps in refreshed rows. */
    private fun observeInto(key: String, flowProvider: () -> Flow<ContactProfile?>) {
        scope.launch {
            flowProvider().collect { profile ->
                _profiles.update { it + (key to profile) }
            }
        }
    }
}
