package xyz.desent.presentation.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.ContactAnniversary
import xyz.desent.domain.model.ContactEmailAddress
import xyz.desent.domain.model.ContactPhone
import xyz.desent.domain.model.ContactWallet
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.model.PrivateContactSerializer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import xyz.desent.presentation.ui.components.DesentTextField

/**
 * Contact editor (ANDROID_CONTACTS.md §4): repeatable email/phone/wallet/
 * anniversary rows, live npub/nprofile/hex validation, and the round-trip
 * rule — editing mutates the existing entry (unknown-key extras preserved).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditorSheet(
    existing: PrivateContact?,
    onSave: (PrivateContact) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    val emails = remember {
        mutableStateListOf<ContactEmailAddress>().apply {
            addAll(existing?.emails.orEmpty())
            if (isEmpty()) add(ContactEmailAddress())
        }
    }
    val phones = remember {
        mutableStateListOf<ContactPhone>().apply { addAll(existing?.phones.orEmpty()) }
    }
    val wallets = remember {
        mutableStateListOf<ContactWallet>().apply { addAll(existing?.wallets.orEmpty()) }
    }
    val anniversaries = remember {
        mutableStateListOf<ContactAnniversary>().apply { addAll(existing?.anniversaries.orEmpty()) }
    }
    var pubkeyInput by remember { mutableStateOf(existing?.pubkey.orEmpty()) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    var showCancelConfirm by remember { mutableStateOf(false) }
    var datePickTarget by remember { mutableStateOf<Int?>(null) }

    val pubkeyValid = remember(pubkeyInput) {
        pubkeyInput.isBlank() || PrivateContactSerializer.normalizePubkeyInput(pubkeyInput) != null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (existing == null) "Add contact" else "Edit contact",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete contact", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            DesentTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("Emails")
            emails.forEachIndexed { i, email ->
                LabeledValueRow(
                    label = email.label,
                    value = email.value,
                    labelHint = "Label",
                    valueHint = "Email",
                    onLabelChange = { emails[i] = email.copy(label = it) },
                    onValueChange = { emails[i] = email.copy(value = it) },
                    onRemove = { emails.removeAt(i) },
                    canRemove = emails.size > 1
                )
            }
            AddRowButton("Add email") { emails.add(ContactEmailAddress()) }

            SectionLabel("Phones")
            phones.forEachIndexed { i, phone ->
                LabeledValueRow(
                    label = phone.label,
                    value = phone.value,
                    labelHint = "Label",
                    valueHint = "Phone",
                    onLabelChange = { phones[i] = phone.copy(label = it) },
                    onValueChange = { phones[i] = phone.copy(value = it) },
                    onRemove = { phones.removeAt(i) },
                    canRemove = true
                )
            }
            AddRowButton("Add phone") { phones.add(ContactPhone()) }

            SectionLabel("Wallets")
            wallets.forEachIndexed { i, wallet ->
                WalletRow(
                    wallet = wallet,
                    onChange = { wallets[i] = it },
                    onRemove = { wallets.removeAt(i) }
                )
            }
            AddRowButton("Add wallet") { wallets.add(ContactWallet(network = "lightning")) }

            SectionLabel("Dates")
            anniversaries.forEachIndexed { i, anniversary ->
                AnniversaryRow(
                    anniversary = anniversary,
                    onChange = { anniversaries[i] = it },
                    onRemove = { anniversaries.removeAt(i) },
                    onPickDate = { datePickTarget = i }
                )
            }
            AddRowButton("Add date") { anniversaries.add(ContactAnniversary(label = "Birthday", date = "")) }

            SectionLabel("Nostr")
            DesentTextField(
                value = pubkeyInput,
                onValueChange = { pubkeyInput = it },
                label = { Text("npub / nprofile / hex key") },
                singleLine = true,
                isError = !pubkeyValid,
                supportingText = {
                    Text(
                        when {
                            pubkeyInput.isBlank() -> "Links the contact to a nostr profile"
                            pubkeyValid -> "✓ Valid key"
                            else -> "Not a valid npub or hex key"
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("Notes")
            DesentTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    onClick = { showCancelConfirm = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
                androidx.compose.material3.Button(
                    onClick = {
                        val normalizedPubkey = PrivateContactSerializer.normalizePubkeyInput(pubkeyInput)
                        val cleanedEmails = emails.map { it.copy(label = it.label.trim(), value = it.value.trim()) }
                            .filter { it.value.isNotBlank() }
                        val cleanedPhones = phones.map { it.copy(label = it.label.trim(), value = it.value.trim()) }
                            .filter { it.value.isNotBlank() }
                        val cleanedWallets = wallets.map {
                            it.copy(label = it.label.trim(), value = it.value.trim(), network = it.network.trim())
                        }.filter { it.value.isNotBlank() }
                        val cleanedAnniversaries = anniversaries.map { it.copy(label = it.label.trim()) }
                            .filter { it.date.isNotBlank() }
                        val primaryEmail = cleanedEmails.firstOrNull()?.value.orEmpty()
                        onSave(
                            PrivateContact(
                                name = name.trim(),
                                emails = cleanedEmails,
                                phones = cleanedPhones,
                                wallets = cleanedWallets,
                                anniversaries = cleanedAnniversaries,
                                pubkey = normalizedPubkey,
                                domain = if (existing != null && existing.primaryEmail == primaryEmail) {
                                    existing.domain
                                } else {
                                    primaryEmail.substringAfterLast("@", "")
                                },
                                notes = notes.trim().ifBlank { null },
                                // Round-trip rule: unknown keys survive the edit.
                                extras = existing?.extras.orEmpty()
                            )
                        )
                    },
                    enabled = pubkeyValid && (
                        name.isNotBlank() ||
                            emails.any { it.value.isNotBlank() } ||
                            PrivateContactSerializer.normalizePubkeyInput(pubkeyInput) != null
                        ),
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
        }
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this contact will be lost.") },
            confirmButton = {
                TextButton(onClick = { showCancelConfirm = false; onDismiss() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) { Text("Keep editing") }
            }
        )
    }

    datePickTarget?.let { index ->
        val anniversary = anniversaries.getOrNull(index) ?: return@let
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = anniversary.parsedDate()
                ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
                ?: LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { datePickTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        if (index < anniversaries.size) {
                            anniversaries[index] = anniversary.copy(date = date.toString())
                        }
                    }
                    datePickTarget = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { datePickTarget = null }) { Text("Cancel") } }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun LabeledValueRow(
    label: String,
    value: String,
    labelHint: String,
    valueHint: String,
    onLabelChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DesentTextField(
            value = label,
            onValueChange = onLabelChange,
            label = { Text(labelHint) },
            singleLine = true,
            modifier = Modifier.weight(0.35f)
        )
        DesentTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(valueHint) },
            singleLine = true,
            modifier = Modifier.weight(0.65f)
        )
        if (canRemove) {
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun WalletRow(
    wallet: ContactWallet,
    onChange: (ContactWallet) -> Unit,
    onRemove: () -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NetworkSelect(network = wallet.network, onSelected = { onChange(wallet.copy(network = it)) })
            DesentTextField(
                value = wallet.value,
                onValueChange = { onChange(wallet.copy(value = it)) },
                label = { Text("Address") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
            }
        }
        DesentTextField(
            value = wallet.label,
            onValueChange = { onChange(wallet.copy(label = it)) },
            label = { Text("Label (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private val NETWORK_OPTIONS = listOf("bitcoin", "lightning", "ethereum", "liquid", "other")

@Composable
private fun NetworkSelect(network: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = if (network.isNotBlank() && network !in NETWORK_OPTIONS) network else network.ifBlank { "lightning" }
    Column {
        TextButton(onClick = { expanded = true }) {
            Text(current.replaceFirstChar { it.uppercase() })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf(current) + NETWORK_OPTIONS.filter { it != current }).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AnniversaryRow(
    anniversary: ContactAnniversary,
    onChange: (ContactAnniversary) -> Unit,
    onRemove: () -> Unit,
    onPickDate: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DesentTextField(
            value = anniversary.label,
            onValueChange = { onChange(anniversary.copy(label = it)) },
            label = { Text("Label") },
            singleLine = true,
            modifier = Modifier.weight(0.5f)
        )
        DesentTextField(
            value = anniversary.date,
            onValueChange = { onChange(anniversary.copy(date = it.filter { c -> c.isDigit() || c == '-' })) },
            label = { Text("Date") },
            placeholder = { Text("YYYY-MM-DD") },
            singleLine = true,
            modifier = Modifier.weight(0.5f),
            readOnly = false
        )
        IconButton(onClick = onPickDate, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Add, contentDescription = "Pick date", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AddRowButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(4.dp))
        Text(text)
    }
}
