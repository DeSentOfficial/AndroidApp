package xyz.desent.presentation.ui.email.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.desent.data.EmailBridgeTags
import xyz.desent.domain.model.EmailRecipient

/**
 * Shared editing state for the To/Cc/Bcc chip rows on the compose and reply
 * surfaces (END-01 §3.4): chip lists, per-field typed text, active field,
 * delimiter commits (comma/semicolon — an addr-spec never contains either),
 * cross-line case-insensitive dedupe, addr-spec validation and the
 * 20-envelope-recipient cap (END-03 §6). Pure state machine — no Android, no
 * coroutines beyond the state holder; unit-testable standalone.
 */
class RecipientFieldsController(
    initialTo: List<EmailRecipient> = emptyList(),
    initialCc: List<EmailRecipient> = emptyList()
) {

    data class State(
        val to: List<EmailRecipient> = emptyList(),
        val cc: List<EmailRecipient> = emptyList(),
        val bcc: List<EmailRecipient> = emptyList(),
        /** Uncommitted typed text per field (survives switching lines). */
        val fieldTexts: Map<RecipientField, String> = emptyMap(),
        val activeField: RecipientField = RecipientField.TO,
        val error: String? = null
    ) {
        /** Total envelope recipients (to+cc+bcc) — the END-03 §6 cap input. */
        val recipientCount: Int get() = to.size + cc.size + bcc.size

        /** PGP encrypts to exactly one WKD key — the lock only engages solo (END-06). */
        val singleRecipient: Boolean get() = recipientCount == 1

        /** The one address a PGP send would encrypt to, when [singleRecipient]. */
        val soleRecipientAddress: String?
            get() = when {
                !singleRecipient -> null
                to.isNotEmpty() -> to.first().address
                cc.isNotEmpty() -> cc.first().address
                else -> bcc.first().address
            }
    }

    private val _state = MutableStateFlow(State(to = initialTo, cc = initialCc))
    val state: StateFlow<State> = _state.asStateFlow()

    /** Current state snapshot (StateFlow-style accessor). */
    val value: State get() = _state.value

    /**
     * Fired after every list mutation (not plain typing) — the owning ViewModel
     * feeds the PGP lock ([State.soleRecipientAddress]) from here.
     */
    var onListsChanged: (() -> Unit)? = null

    /** Typed-text observer for the owning ViewModel's suggestion query. */
    var onQueryChanged: ((String) -> Unit)? = null

    /** Focus: switches the active line (committing any valid typed text). */
    fun onFieldFocusChanged(field: RecipientField, focused: Boolean) {
        if (focused) {
            commitFieldText(_state.value.activeField, onlyIfValid = true)
            _state.value = _state.value.copy(activeField = field, error = null)
            onQueryChanged?.invoke(_state.value.fieldTexts[field].orEmpty())
        } else {
            commitFieldText(field, onlyIfValid = true)
        }
    }

    fun onFieldTextChange(field: RecipientField, value: String) {
        // Comma/semicolon commits a chip — the standard mail-client delimiter.
        if (value.endsWith(',') || value.endsWith(';')) {
            val typed = value.substringBeforeLast(',').substringBeforeLast(';').trim()
            if (typed.isNotEmpty() && isValidEmail(typed)) {
                addRecipient(field, EmailRecipient(typed.lowercase()))
                return
            }
        }
        _state.value = _state.value.copy(
            fieldTexts = _state.value.fieldTexts + (field to value),
            activeField = field,
            error = null
        )
        onQueryChanged?.invoke(value)
    }

    /** Commit the typed text of [field] as a chip when it is a valid addr-spec. */
    fun commitFieldText(field: RecipientField, onlyIfValid: Boolean) {
        val state = _state.value
        val typed = state.fieldTexts[field]?.trim().orEmpty()
        if (typed.isEmpty()) return
        if (isValidEmail(typed)) {
            addRecipient(field, EmailRecipient(typed.lowercase()))
        } else if (!onlyIfValid) {
            _state.value = state.copy(error = "Recipient is not a valid email address")
        }
    }

    fun addRecipient(field: RecipientField, recipient: EmailRecipient) {
        val state = _state.value
        val current = when (field) {
            RecipientField.TO -> state.to
            RecipientField.CC -> state.cc
            RecipientField.BCC -> state.bcc
        }
        // Dedupe case-insensitively across all three lines: a second copy of
        // an address is never a new mailbox (the relay meters per envelope
        // recipient — duplicates would double-send).
        val alreadyPresent = (state.to + state.cc + state.bcc)
            .any { it.address.equals(recipient.address, ignoreCase = true) }
        if (!alreadyPresent) {
            _state.value = when (field) {
                RecipientField.TO -> state.copy(to = current + recipient)
                RecipientField.CC -> state.copy(cc = current + recipient)
                RecipientField.BCC -> state.copy(bcc = current + recipient)
            }
        }
        _state.value = _state.value.copy(
            fieldTexts = _state.value.fieldTexts + (field to ""),
            activeField = field
        )
        onQueryChanged?.invoke("")
        onListsChanged?.invoke()
    }

    fun removeRecipient(field: RecipientField, recipient: EmailRecipient) {
        val state = _state.value
        _state.value = when (field) {
            RecipientField.TO -> state.copy(to = state.to - recipient)
            RecipientField.CC -> state.copy(cc = state.cc - recipient)
            RecipientField.BCC -> state.copy(bcc = state.bcc - recipient)
        }
        onListsChanged?.invoke()
    }

    /** Structured send validation (chip shape); failures surface as [State.error]. */
    fun validateForSend(): Boolean {
        val state = _state.value
        if (state.fieldTexts.values.any { it.trim().isNotEmpty() }) {
            _state.value = state.copy(error = "Recipient is not a valid email address")
            return false
        }
        if (state.to.isEmpty()) {
            _state.value = state.copy(error = "At least one To recipient is required")
            return false
        }
        if (state.recipientCount > EmailBridgeTags.MAX_ENVELOPE_RECIPIENTS) {
            _state.value = state.copy(
                error = "Too many recipients (${state.recipientCount}; max ${EmailBridgeTags.MAX_ENVELOPE_RECIPIENTS})"
            )
            return false
        }
        return true
    }

    fun clearError() {
        if (_state.value.error != null) _state.value = _state.value.copy(error = null)
    }

    /** Surface a non-field error (e.g. a send failure) on the same slot. */
    fun setError(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    companion object {
        fun isValidEmail(value: String): Boolean {
            // Lightweight check — the relay validates the real MX/STARTTLS path.
            return Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(value)
        }
    }
}
