package xyz.desent.presentation.ui.calendar

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.desent.domain.model.AttachmentMeta
import xyz.desent.domain.model.NostrCalendarRsvp
import xyz.desent.domain.model.RecurFreq
import xyz.desent.domain.model.RsvpStatus
import xyz.desent.domain.repository.ResolvedLocation
import xyz.desent.domain.util.GeohashUtils
import xyz.desent.presentation.ui.calendar.viewmodel.CalendarAttachmentOpenEvent
import xyz.desent.presentation.ui.calendar.viewmodel.CalendarEventEditorUiState
import xyz.desent.presentation.ui.calendar.viewmodel.CalendarEventEditorViewModel
import xyz.desent.presentation.ui.calendar.viewmodel.RecurEndType
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.util.Calendar
import java.util.Date
import java.util.Locale
import xyz.desent.presentation.ui.components.DesentTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarEventEditorScreen(
    onNavigateBack: () -> Unit,
    viewModel: CalendarEventEditorViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showShare by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onNavigateBack()
    }

    // Hand decrypted attachments to the system viewer.
    LaunchedEffect(Unit) {
        viewModel.openEvents.collect { ev: CalendarAttachmentOpenEvent ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(ev.uri, ev.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(intent) }
                .onFailure {
                    Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
                }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val name = uri.lastPathSegment ?: "attachment"
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: return@launch
            viewModel.addAttachment(bytes, mime, name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.eventId == null) "New event" else "Edit event") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showShare = true }) {
                        Icon(Icons.Default.Share, contentDescription = "Share event")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.save() },
                content = { Icon(Icons.Default.Check, contentDescription = "Save") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DesentTextField(
                value = uiState.title,
                onValueChange = viewModel::updateTitle,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("All-day")
                Switch(checked = uiState.allDay, onCheckedChange = viewModel::updateAllDay)
            }

            DateTimePickerRow(
                label = "Start",
                epochMs = uiState.startMs,
                allDay = uiState.allDay,
                hideYear = uiState.hidesAnchorYear,
                onPick = { ms -> ms?.let { viewModel.updateStart(it) } }
            )

            if (!uiState.hidesAnchorYear || uiState.endMs != null || !uiState.allDay) {
                DateTimePickerRow(
                    label = "End",
                    epochMs = uiState.endMs,
                    allDay = uiState.allDay,
                    allowClear = true,
                    hideYear = uiState.hidesAnchorYear,
                    onPick = viewModel::updateEnd
                )
            }

            RecurrenceSection(
                state = uiState,
                onFreqChange = viewModel::updateRecurFreq,
                onIntervalChange = viewModel::updateRecurInterval,
                onToggleWeeklyDay = viewModel::toggleRecurWeeklyDay,
                onUseNthWeekdayChange = viewModel::setUseNthWeekday,
                onNthWeekdayChange = viewModel::setNthWeekday,
                onNthOrdinalChange = viewModel::setNthOrdinal,
                onEndTypeChange = viewModel::setRecurEndType,
                onUntilChange = viewModel::setRecurUntil,
                onCountChange = viewModel::updateRecurCount
            )

            LocationSection(
                state = uiState,
                onLocationChange = viewModel::updateLocation,
                onResolve = viewModel::resolveLocation,
                onSelectCandidate = viewModel::selectCandidate,
                onDismissCandidates = viewModel::dismissCandidates,
                onClearResolved = viewModel::clearResolvedLocation
            )

            DesentTextField(
                value = uiState.description,
                onValueChange = viewModel::updateDescription,
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            CalendarDropdown(
                selected = uiState.calendarD,
                calendars = uiState.availableCalendars,
                onSelect = viewModel::setCalendarD
            )

            AttachmentsSection(
                attachments = uiState.attachments,
                isUploading = uiState.isUploading,
                onPick = { picker.launch("*/*") },
                onRemove = viewModel::removeAttachment,
                onOpen = viewModel::openAttachment
            )

            RsvpSection(
                canRespond = uiState.canRespond,
                rsvps = uiState.rsvps,
                onRespond = viewModel::sendRsvp
            )

            if (uiState.isSaving) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Saving…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (showShare) {
        ShareEventDialog(
            shareCount = uiState.shares.size,
            onDismiss = { showShare = false },
            onShare = { recipient, role ->
                viewModel.shareEvent(recipient, role)
                showShare = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RecurrenceSection(
    state: CalendarEventEditorUiState,
    onFreqChange: (RecurFreq?) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onToggleWeeklyDay: (DayOfWeek) -> Unit,
    onUseNthWeekdayChange: (Boolean) -> Unit,
    onNthWeekdayChange: (DayOfWeek) -> Unit,
    onNthOrdinalChange: (Int) -> Unit,
    onEndTypeChange: (RecurEndType) -> Unit,
    onUntilChange: (Long) -> Unit,
    onCountChange: (Int) -> Unit
) {
    val freq = state.recurFreq
    val freqLabel = when (freq) {
        null -> "Never"
        RecurFreq.DAILY -> "Daily"
        RecurFreq.WEEKLY -> "Weekly"
        RecurFreq.MONTHLY -> "Monthly"
        RecurFreq.YEARLY -> "Yearly"
    }
    val unitLabel = when (freq) {
        RecurFreq.DAILY -> "day(s)"
        RecurFreq.WEEKLY -> "week(s)"
        RecurFreq.MONTHLY -> "month(s)"
        RecurFreq.YEARLY -> "year(s)"
        null -> ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var freqExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = freqExpanded, onExpandedChange = { freqExpanded = it }) {
            DesentTextField(
                value = freqLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Repeats") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = freqExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(expanded = freqExpanded, onDismissRequest = { freqExpanded = false }) {
                DropdownMenuItem(text = { Text("Never") }, onClick = { onFreqChange(null); freqExpanded = false })
                listOf(
                    RecurFreq.DAILY to "Daily",
                    RecurFreq.WEEKLY to "Weekly",
                    RecurFreq.MONTHLY to "Monthly",
                    RecurFreq.YEARLY to "Yearly"
                ).forEach { (f, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onFreqChange(f); freqExpanded = false })
                }
            }
        }

        if (freq != null) {
            // Interval stepper
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Every", style = MaterialTheme.typography.bodyLarge)
                IconButton(onClick = { onIntervalChange(-1) }, enabled = state.recurInterval > 1) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease interval")
                }
                Text(
                    state.recurInterval.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(onClick = { onIntervalChange(1) }, enabled = state.recurInterval < 99) {
                    Icon(Icons.Default.Add, contentDescription = "Increase interval")
                }
                Text(unitLabel, style = MaterialTheme.typography.bodyLarge)
            }

            if (freq == RecurFreq.WEEKLY) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.values().forEach { day ->
                        FilterChip(
                            selected = day in state.recurWeeklyDays,
                            onClick = { onToggleWeeklyDay(day) },
                            label = { Text(day.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())) }
                        )
                    }
                }
                if (state.recurWeeklyDays.isEmpty()) {
                    Text(
                        "On the start date's weekday",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (freq == RecurFreq.MONTHLY || freq == RecurFreq.YEARLY) {
                val anchorDay = remember(state.startMs) {
                    java.time.Instant.ofEpochMilli(state.startMs)
                        .atZone(java.time.ZoneId.systemDefault()).dayOfMonth
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !state.useNthWeekday,
                        onClick = { onUseNthWeekdayChange(false) },
                        label = { Text("Day $anchorDay") }
                    )
                    FilterChip(
                        selected = state.useNthWeekday,
                        onClick = { onUseNthWeekdayChange(true) },
                        label = { Text("Nth weekday") }
                    )
                }
                if (state.useNthWeekday) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NthOrdinalDropdown(
                            ordinal = state.nthOrdinal,
                            onChange = onNthOrdinalChange
                        )
                        WeekdayDropdown(
                            selected = state.nthWeekday,
                            onChange = onNthWeekdayChange
                        )
                    }
                }
            }

            // End condition
            Text("Ends", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.recurEndType == RecurEndType.NEVER,
                    onClick = { onEndTypeChange(RecurEndType.NEVER) },
                    label = { Text("Never") }
                )
                FilterChip(
                    selected = state.recurEndType == RecurEndType.UNTIL,
                    onClick = { onEndTypeChange(RecurEndType.UNTIL) },
                    label = { Text("On date") }
                )
                FilterChip(
                    selected = state.recurEndType == RecurEndType.COUNT,
                    onClick = { onEndTypeChange(RecurEndType.COUNT) },
                    label = { Text("After") }
                )
            }
            when (state.recurEndType) {
                RecurEndType.UNTIL -> UntilDatePickerRow(
                    untilMs = state.recurUntilMs,
                    onChange = onUntilChange
                )
                RecurEndType.COUNT -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = { onCountChange(-1) }, enabled = state.recurCount > 1) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease occurrence count")
                    }
                    Text(
                        "${state.recurCount} time${if (state.recurCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(88.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    IconButton(onClick = { onCountChange(1) }, enabled = state.recurCount < 999) {
                        Icon(Icons.Default.Add, contentDescription = "Increase occurrence count")
                    }
                }
                RecurEndType.NEVER -> if (state.hidesAnchorYear) {
                    Text(
                        "Repeats yearly — the year is ignored",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NthOrdinalDropdown(ordinal: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(0 to "Every", 1 to "First", 2 to "Second", 3 to "Third", 4 to "Fourth", -1 to "Last")
    val selected = options.firstOrNull { it.first == ordinal } ?: options[1]
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        DesentTextField(
            value = selected.second,
            onValueChange = {},
            readOnly = true,
            label = { Text("Which") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .width(140.dp)
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onChange(value); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekdayDropdown(selected: DayOfWeek, onChange: (DayOfWeek) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = remember(selected) {
        selected.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        DesentTextField(
            value = displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Weekday") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .width(140.dp)
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DayOfWeek.values().forEach { day ->
                DropdownMenuItem(
                    text = { Text(day.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())) },
                    onClick = { onChange(day); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun UntilDatePickerRow(untilMs: Long?, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    val displayFormat = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }
    val displayText = remember(untilMs) { untilMs?.let { displayFormat.format(Date(it)) } ?: "Pick a date" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            val base = untilMs ?: System.currentTimeMillis()
            val c = Calendar.getInstance().apply { timeInMillis = base }
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    val out = Calendar.getInstance().apply {
                        set(year, month, day, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onChange(out.timeInMillis)
                },
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH),
                c.get(Calendar.DAY_OF_MONTH)
            ).show()
        }) { Text(displayText) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareEventDialog(
    shareCount: Int,
    onDismiss: () -> Unit,
    onShare: (recipientNpub: String, role: String) -> Unit
) {
    var recipient by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("viewer") }
    val roles = listOf("viewer", "attendee", "organizer")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share event") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DesentTextField(
                    value = recipient,
                    onValueChange = { recipient = it },
                    label = { Text("Recipient npub / hex pubkey") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Role", style = MaterialTheme.typography.labelLarge)
                Row {
                    roles.forEach { r ->
                        FilterChip(
                            selected = role == r,
                            onClick = { role = r },
                            label = { Text(r.replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                if (shareCount > 0) {
                    Text(
                        "Already shared with $shareCount recipient${if (shareCount == 1) "" else "s"}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onShare(recipient, role) },
                enabled = recipient.isNotBlank()
            ) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DateTimePickerRow(
    label: String,
    epochMs: Long?,
    allDay: Boolean,
    allowClear: Boolean = false,
    hideYear: Boolean = false,
    onPick: (Long?) -> Unit
) {
    val context = LocalContext.current
    val displayFormat = remember(allDay, hideYear) {
        SimpleDateFormat(
            if (allDay) {
                if (hideYear) "EEE, MMM d" else "EEE, MMM d, yyyy"
            } else {
                if (hideYear) "EEE, MMM d · h:mm a" else "EEE, MMM d, yyyy · h:mm a"
            },
            Locale.getDefault()
        )
    }
    val displayText = remember(epochMs, allDay) {
        epochMs?.let { displayFormat.format(Date(it)) } ?: "—"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(72.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val base = epochMs ?: System.currentTimeMillis()
                val c = Calendar.getInstance().apply { timeInMillis = base }
                DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        val out = Calendar.getInstance().apply {
                            set(year, month, day)
                            if (!allDay) {
                                set(Calendar.HOUR_OF_DAY, c.get(Calendar.HOUR_OF_DAY))
                                set(Calendar.MINUTE, c.get(Calendar.MINUTE))
                            } else {
                                set(Calendar.HOUR_OF_DAY, 0)
                                set(Calendar.MINUTE, 0)
                            }
                        }
                        if (allDay) {
                            onPick(out.timeInMillis)
                        } else {
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    out.set(Calendar.HOUR_OF_DAY, hour)
                                    out.set(Calendar.MINUTE, minute)
                                    onPick(out.timeInMillis)
                                },
                                c.get(Calendar.HOUR_OF_DAY),
                                c.get(Calendar.MINUTE),
                                false
                            ).show()
                        }
                    },
                    c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH),
                    c.get(Calendar.DAY_OF_MONTH)
                ).show()
            }) {
                Text(displayText)
            }
            if (allowClear && epochMs != null) {
                TextButton(onClick = { onPick(null) }) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun RsvpSection(
    canRespond: Boolean,
    rsvps: List<NostrCalendarRsvp>,
    onRespond: (RsvpStatus) -> Unit
) {
    if (!canRespond && rsvps.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("RSVP", style = MaterialTheme.typography.bodyLarge)
        if (canRespond) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(RsvpStatus.ACCEPTED to "Accept", RsvpStatus.TENTATIVE to "Tentative", RsvpStatus.DECLINED to "Decline")
                    .forEach { (status, label) ->
                        OutlinedButton(onClick = { onRespond(status) }) { Text(label) }
                    }
            }
        }
        if (rsvps.isNotEmpty()) {
            val counts = rsvps.groupingBy { it.status }.eachCount()
            val summary = buildString {
                RsvpStatus.values().forEach { st ->
                    counts[st]?.let { append("${st.wire}: $it  ") }
                }
            }
            Text(
                "Responses — $summary",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDropdown(
    selected: String?,
    calendars: List<xyz.desent.domain.model.NostrCalendar>,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTitle = calendars.firstOrNull { it.dTag == selected }?.title ?: "None"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        DesentTextField(
            value = selectedTitle,
            onValueChange = {},
            readOnly = true,
            label = { Text("Calendar") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = {
                onSelect(null); expanded = false
            })
            calendars.forEach { cal ->
                DropdownMenuItem(text = { Text(cal.title) }, onClick = {
                    onSelect(cal.dTag); expanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentsSection(
    attachments: List<AttachmentMeta>,
    isUploading: Boolean,
    onPick: () -> Unit,
    onRemove: (AttachmentMeta) -> Unit,
    onOpen: (AttachmentMeta) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Attachments", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (isUploading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onPick) {
                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }
        if (attachments.isEmpty()) {
            Text(
                "No attachments",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                attachments.forEach { meta ->
                    AssistChip(
                        onClick = { onOpen(meta) },
                        label = { Text(meta.filename.ifBlank { meta.sha256.take(8) }, maxLines = 1) },
                        leadingIcon = {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove attachment",
                                modifier = Modifier.size(16.dp).clickable { onRemove(meta) }
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * Location entry + geohash resolution. The trailing search icon resolves the
 * typed text (pasted "lat, lon" pairs resolve offline via
 * [GeohashUtils.parseCoordinates]); picking a candidate replaces the text
 * with the canonical address and stores the 7-char geohash, which rides the
 * encrypted payload (refs/CALENDAR_PROTOCOL.md) and never appears as a
 * plaintext tag on the outer event.
 */
@Composable
private fun LocationSection(
    state: CalendarEventEditorUiState,
    onLocationChange: (String) -> Unit,
    onResolve: () -> Unit,
    onSelectCandidate: (ResolvedLocation) -> Unit,
    onDismissCandidates: () -> Unit,
    onClearResolved: () -> Unit
) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DesentTextField(
            value = state.location,
            onValueChange = onLocationChange,
            label = { Text("Location") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = if (state.geohash != null) {
                { Icon(Icons.Default.Place, contentDescription = null) }
            } else {
                null
            },
            trailingIcon = {
                if (state.isResolving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onResolve) {
                        Icon(Icons.Default.Search, contentDescription = "Resolve location")
                    }
                }
            },
            supportingText = if (state.geohash != null) {
                { Text("Location resolved (geohash ${state.geohash})") }
            } else {
                null
            }
        )

        if (state.resolveCandidates.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    state.resolveCandidates.forEachIndexed { index, candidate ->
                        if (index > 0) HorizontalDivider()
                        ListItem(
                            headlineContent = {
                                Text(
                                    candidate.displayName,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(
                                    String.format(
                                        Locale.US,
                                        "%.5f, %.5f",
                                        candidate.point.latitude,
                                        candidate.point.longitude
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingContent = { Icon(Icons.Default.Place, contentDescription = null) },
                            modifier = Modifier.clickable { onSelectCandidate(candidate) }
                        )
                    }
                }
            }
            TextButton(onClick = onDismissCandidates) { Text("Dismiss suggestions") }
        }

        if (state.geohash != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { openInMaps(context, state.geohash) }) {
                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Open in maps")
                }
                TextButton(onClick = onClearResolved) { Text("Clear pin") }
            }
        }
    }
}

/** Decodes [geohash] and hands it to whatever maps app handles `geo:`. */
private fun openInMaps(context: android.content.Context, geohash: String) {
    val point = GeohashUtils.decode(geohash) ?: return
    val uri = Uri.parse("geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { Toast.makeText(context, "No maps app installed", Toast.LENGTH_SHORT).show() }
}
