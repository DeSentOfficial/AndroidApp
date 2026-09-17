package xyz.desent.data.spam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.SpamFilterConfig
import xyz.desent.domain.repository.PrivateStorageRepository
import xyz.desent.domain.repository.SpamFilterRepository

/**
 * Pure resolver for the remote-image policy (see refs/SPAM_FILTER_REFERENCE.md).
 *
 * Remote images (and any other http/https subresource) are blocked for every
 * sender by default. A sender's images load automatically only when the user
 * has explicitly trusted them:
 *
 *  1. the exact address is in `imageAllowedSenders`, or
 *  2. the sender's domain is in `imageAllowedDomains`, or
 *  3. `imagesAllowedForContacts` is on and the exact address is a contact
 *     (`desent:contacts`, itself synced over NIP-78).
 *
 * The curated global spam-list `allowedDomains` deliberately does NOT
 * auto-allow images: that list guards spam false-positives, not image trust.
 */
object RemoteImagePolicy {

    /** Lowercased, trimmed email address. */
    fun normalizeSender(senderEmail: String): String = senderEmail.trim().lowercase()

    /** Lowercased, trimmed domain without a leading `@`. */
    fun normalizeDomain(domain: String): String = domain.trim().lowercase().removePrefix("@")

    /** Domain part of an address ("" when none). */
    fun domainOf(senderEmail: String): String =
        normalizeSender(senderEmail).substringAfterLast("@", "")

    fun isSenderAllowed(
        senderEmail: String,
        config: SpamFilterConfig,
        contactEmails: Set<String> = emptySet()
    ): Boolean {
        if (!config.blockRemoteImages) return true
        val sender = normalizeSender(senderEmail)
        if (sender.isEmpty()) return false // unknown sender — block defensively
        if (config.imageAllowedSenders.any { normalizeSender(it) == sender }) return true
        val domain = sender.substringAfterLast("@", "")
        if (domain.isNotEmpty() && config.imageAllowedDomains.any { normalizeDomain(it) == domain }) return true
        if (config.imagesAllowedForContacts && sender in contactEmails) return true
        return false
    }
}

/**
 * Immutable view of everything the policy needs, exposed to UI as a
 * [StateFlow] so banners/WebView modes react to config edits and contact
 * changes (including remote NIP-78 sync) without per-screen plumbing.
 */
data class RemoteImagePolicySnapshot(
    val config: SpamFilterConfig = SpamFilterConfig(),
    val contactEmails: Set<String> = emptySet()
) {
    fun isBlocked(senderEmail: String): Boolean =
        !RemoteImagePolicy.isSenderAllowed(senderEmail, config, contactEmails)
}

/**
 * Combines the spam-filter config (DataStore, cloud-synced via
 * `desent:spam-settings`) with the encrypted contacts list into a
 * [RemoteImagePolicySnapshot], and applies "always allow" edits through
 * [SpamFilterRepository.setConfig] so they publish to the user's other
 * devices automatically (SpamSettingsSyncCoordinator).
 */
class RemoteImagePolicyState(
    private val spamFilterRepository: SpamFilterRepository,
    privateStorageRepository: PrivateStorageRepository,
    preferencesManager: PreferencesManager,
    private val scope: CoroutineScope
) {
    private val _snapshot = MutableStateFlow(RemoteImagePolicySnapshot())
    val snapshot: StateFlow<RemoteImagePolicySnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            val npub = preferencesManager.npubKey.firstOrNull() ?: return@launch
            combine(
                spamFilterRepository.observeConfig(),
                privateStorageRepository.observeContacts(npub)
            ) { config, contacts ->
                RemoteImagePolicySnapshot(
                    config = config,
                    // Contacts trust matches EVERY email slot, not just the
                    // primary (ANDROID_CONTACTS.md §1 / §9).
                    contactEmails = contacts
                        .flatMap { contact ->
                            contact.allEmails().map { RemoteImagePolicy.normalizeSender(it) }
                        }
                        .filter { it.isNotEmpty() }
                        .toSet()
                )
            }.collect { _snapshot.value = it }
        }
    }

    /** Add the exact sender address to the allowlist (idempotent). */
    fun allowSender(senderEmail: String) = mutateConfig { config ->
        val sender = RemoteImagePolicy.normalizeSender(senderEmail)
        if (sender.isEmpty()) config
        else config.copy(imageAllowedSenders = (config.imageAllowedSenders + sender).distinct().sorted())
    }

    /** Add the sender's domain to the allowlist (idempotent). */
    fun allowDomain(senderEmail: String) = mutateConfig { config ->
        val domain = RemoteImagePolicy.domainOf(senderEmail)
        if (domain.isEmpty() || !domain.contains(".")) config
        else config.copy(imageAllowedDomains = (config.imageAllowedDomains + domain).distinct().sorted())
    }

    private fun mutateConfig(transform: (SpamFilterConfig) -> SpamFilterConfig) {
        scope.launch {
            spamFilterRepository.setConfig(transform(_snapshot.value.config))
        }
    }
}

/** Builds [RemoteImagePolicyState] instances bound to a ViewModel scope. */
class RemoteImagePolicyStateFactory(
    private val spamFilterRepository: SpamFilterRepository,
    private val privateStorageRepository: PrivateStorageRepository,
    private val preferencesManager: PreferencesManager
) {
    fun create(scope: CoroutineScope): RemoteImagePolicyState =
        RemoteImagePolicyState(spamFilterRepository, privateStorageRepository, preferencesManager, scope)
}
