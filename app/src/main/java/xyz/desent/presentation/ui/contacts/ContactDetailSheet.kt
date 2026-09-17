package xyz.desent.presentation.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import xyz.desent.domain.model.ContactProfile
import xyz.desent.domain.model.PrivateContact
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Contact detail sheet (ANDROID_CONTACTS.md §4): banner header from the
 * nostr profile `banner` (gradient fallback), overlapping avatar, sectioned
 * rows with Copy actions, Dates with next-occurrence + age + "Add to
 * calendar", and Compose / Edit footer actions. Openable from the contacts
 * list and from calendar anniversary chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailSheet(
    contact: PrivateContact,
    profile: ContactProfile?,
    onCompose: ((String) -> Unit)?,
    onEdit: (() -> Unit)?,
    onAddAnniversaryToCalendar: (String, Long, String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            BannerHeader(contact, profile)

            Column(Modifier.padding(horizontal = 20.dp)) {
                val displayName = contact.name.ifBlank {
                    profile?.displayName?.takeIf { it.isNotBlank() }
                        ?: profile?.name?.takeIf { it.isNotBlank() }
                        ?: contact.primaryEmail.ifBlank { contact.npubOrNull() ?: "Unnamed" }
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val secondary = profile?.displayName?.takeIf { it.isNotBlank() && it != displayName }
                    ?: profile?.name?.takeIf { it.isNotBlank() && it != displayName }
                if (secondary != null) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val subline = listOfNotNull(
                    contact.primaryEmail.takeIf { it.isNotBlank() },
                    profile?.nip05?.takeIf { it.isNotBlank() },
                    contact.phones.firstOrNull()?.value?.takeIf { it.isNotBlank() }
                ).firstOrNull()
                if (subline != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (contact.emails.isNotEmpty()) {
                    SectionTitle("Emails")
                    contact.emails.forEach { email ->
                        CopyRow(
                            label = email.label.takeIf { it.isNotBlank() } ?: "Email",
                            value = email.value,
                            action = if (onCompose != null) "Compose" else null,
                            onAction = { onCompose?.invoke(email.value) },
                            onCopy = { clipboard.setText(AnnotatedString(email.value)) }
                        )
                    }
                }
                if (contact.phones.isNotEmpty()) {
                    SectionTitle("Phones")
                    contact.phones.forEach { phone ->
                        CopyRow(
                            label = phone.label.takeIf { it.isNotBlank() } ?: "Phone",
                            value = phone.value,
                            action = null,
                            onAction = {},
                            onCopy = { clipboard.setText(AnnotatedString(phone.value)) }
                        )
                    }
                }
                if (contact.wallets.isNotEmpty()) {
                    SectionTitle("Wallets")
                    contact.wallets.forEach { wallet ->
                        CopyRow(
                            label = wallet.network.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
                                ?: "Wallet",
                            value = wallet.value,
                            action = null,
                            onAction = {},
                            onCopy = { clipboard.setText(AnnotatedString(wallet.value)) }
                        )
                    }
                }
                if (contact.anniversaries.isNotEmpty()) {
                    SectionTitle("Dates")
                    contact.anniversaries.forEach { anniversary ->
                        val date = anniversary.parsedDate()
                        val next = remember(anniversary.date) { contact.nextAnniversary() }
                        val meta = buildList {
                            if (date != null && next?.date == anniversary.date) {
                                add("Next: ${date.withYear(LocalDate.now().year.let { y ->
                                    if (date.withYear(y).isBefore(LocalDate.now())) y + 1 else y
                                }).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))}")
                            }
                            if (date != null && anniversary.kind == xyz.desent.domain.model.AnniversaryKind.BIRTHDAY) {
                                val age = ChronoUnit.YEARS.between(date, LocalDate.now())
                                if (age > 0) add("Turns ${age + 1}")
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = anniversary.label.takeIf { it.isNotBlank() } ?: "Date",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = listOf(anniversary.date, *meta.toTypedArray()).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (date != null) {
                                TextButtonCompact(
                                    text = "Add to calendar",
                                    onClick = {
                                        val name = contact.name.ifBlank { contact.primaryEmail.substringBefore("@") }
                                        val title = if (anniversary.kind == xyz.desent.domain.model.AnniversaryKind.BIRTHDAY) {
                                            "$name's Birthday"
                                        } else {
                                            "$name — ${anniversary.label.ifBlank { "Anniversary" }}"
                                        }
                                        var nextDate = date.withYear(LocalDate.now().year)
                                        if (nextDate.isBefore(LocalDate.now())) nextDate = date.withYear(LocalDate.now().year + 1)
                                        onAddAnniversaryToCalendar(
                                            title,
                                            nextDate.toEpochDay(),
                                            "Contact anniversary for $name (${contact.primaryEmail})"
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // Nostr section — profile enrichment + linked key.
                val npub = contact.npubOrNull()
                if (npub != null || profile != null) {
                    SectionTitle("Nostr")
                    if (npub != null) {
                        CopyRow(
                            label = "npub",
                            value = npub,
                            action = null,
                            onAction = {},
                            onCopy = { clipboard.setText(AnnotatedString(npub)) }
                        )
                    }
                    listOfNotNull(
                        profile?.about?.takeIf { it.isNotBlank() }?.let { "About" to it },
                        profile?.nip05?.takeIf { it.isNotBlank() }?.let { "NIP-05" to it },
                        profile?.website?.takeIf { it.isNotBlank() }?.let { "Website" to it }
                    ).forEach { (label, value) ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(text = value, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                    }
                }

                contact.notes?.takeIf { it.isNotBlank() }?.let {
                    SectionTitle("Notes")
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { onCompose?.invoke(contact.primaryEmail) },
                        enabled = onCompose != null && contact.primaryEmail.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Compose") }
                    OutlinedButton(
                        onClick = { onEdit?.invoke() },
                        enabled = onEdit != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("Edit") }
                }
            }
        }
    }
}

@Composable
private fun BannerHeader(contact: PrivateContact, profile: ContactProfile?) {
    Box(modifier = Modifier.fillMaxWidth().height(96.dp)) {
        val banner = profile?.banner?.takeIf { it.isNotBlank() }
        if (banner != null) {
            AsyncImage(
                model = banner,
                contentDescription = "Profile banner",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.tertiaryContainer
                            )
                        )
                    )
            )
        }
        Column(modifier = Modifier.align(Alignment.BottomStart).offset(y = 28.dp).padding(start = 20.dp)) {
            ContactAvatar(
                contact = contact,
                pictureUrl = profile?.picture?.takeIf { it.isNotBlank() },
                size = 64.dp
            )
        }
    }
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun CopyRow(
    label: String,
    value: String,
    action: String?,
    onAction: () -> Unit,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (action != null) {
            TextButtonCompact(text = action, onClick = onAction)
        } else {
            TextButtonCompact(text = "Copy", onClick = onCopy)
        }
    }
}

@Composable
private fun TextButtonCompact(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}
