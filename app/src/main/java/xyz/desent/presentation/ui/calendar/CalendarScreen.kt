package xyz.desent.presentation.ui.calendar

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.presentation.ui.calendar.viewmodel.CalendarViewModel
import xyz.desent.presentation.ui.components.DeleteConfirmationDialog
import xyz.desent.presentation.ui.components.AccountBarTitle
import xyz.desent.presentation.ui.components.DesentLogoMark
import xyz.desent.widget.buildSpanDayCounts
import xyz.desent.widget.firstDayOfWeek
import xyz.desent.widget.monthGridStart
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onNavigateToEditor: (String?) -> Unit,
    onNavigateToCalendars: () -> Unit,
    onAddAnniversaryToCalendar: (title: String, epochDay: Long, description: String) -> Unit,
    viewModel: CalendarViewModel,
    initialDateEpochDay: Long? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val fdow = remember { firstDayOfWeek() }

    // Month-calendar toggle + navigation. [anchorMonthEpochDay] is kept on
    // the first of the month so ‹ › stepping can never drift day-of-month.
    // The grid is on by default; the top-bar toggle turns it off.
    var showMonthGrid by rememberSaveable { mutableStateOf(true) }
    var anchorMonthEpochDay by rememberSaveable {
        mutableStateOf(
            LocalDate.ofEpochDay(initialDateEpochDay ?: LocalDate.now().toEpochDay())
                .withDayOfMonth(1).toEpochDay()
        )
    }
    var selectedDay by rememberSaveable { mutableStateOf(initialDateEpochDay ?: -1L) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteEvent by remember { mutableStateOf<NostrCalendarEvent?>(null) }

    // Requested scroll target (calendar-widget deep link or a month-grid day
    // tap): scroll the agenda to the first day header at/after that day once
    // items are loaded.
    var pendingScrollDay by remember { mutableStateOf<Long?>(initialDateEpochDay) }
    LaunchedEffect(pendingScrollDay, uiState.isLoading) {
        val target = pendingScrollDay ?: return@LaunchedEffect
        if (uiState.isLoading) return@LaunchedEffect
        val index = uiState.items.indexOfFirst { item ->
            item is CalendarListItem.DayHeader && item.epochDay >= target
        }
        if (index >= 0) listState.animateScrollToItem(index)
        pendingScrollDay = null
    }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { AccountBarTitle() },
                navigationIcon = {
                    DesentLogoMark()
                },
                actions = {
                    IconButton(onClick = { showMonthGrid = !showMonthGrid }) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = if (showMonthGrid) "Hide month calendar" else "Show month calendar",
                            tint = if (showMonthGrid) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Manage calendars") },
                                leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onNavigateToCalendars()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.refresh()
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNavigateToEditor(null) }) {
                Icon(Icons.Default.Add, contentDescription = "New event")
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (showMonthGrid) {
                    val anchor = LocalDate.ofEpochDay(anchorMonthEpochDay)
                    // Dots come from the same expanded events that feed the
                    // agenda below, so grid and list can never disagree.
                    val dayCounts = remember(uiState.events, anchorMonthEpochDay, fdow) {
                        buildSpanDayCounts(
                            spans = uiState.events.map { it.gridDaySpan(ZoneId.systemDefault()) },
                            rangeStart = monthGridStart(anchor, fdow),
                            days = 42
                        )
                    }
                    MonthCalendarView(
                        anchorMonth = anchor,
                        selectedDay = selectedDay.takeIf { it >= 0 },
                        eventDayCounts = dayCounts,
                        onPrevMonth = {
                            anchorMonthEpochDay = anchor.minusMonths(1).withDayOfMonth(1).toEpochDay()
                        },
                        onNextMonth = {
                            anchorMonthEpochDay = anchor.plusMonths(1).withDayOfMonth(1).toEpochDay()
                        },
                        onTodayClick = {
                            val now = LocalDate.now()
                            anchorMonthEpochDay = now.withDayOfMonth(1).toEpochDay()
                            selectedDay = now.toEpochDay()
                            pendingScrollDay = now.toEpochDay()
                        },
                        onDayClick = { epochDay ->
                            selectedDay = epochDay
                            val tapped = LocalDate.ofEpochDay(epochDay)
                            if (tapped.year != anchor.year || tapped.month != anchor.month) {
                                anchorMonthEpochDay = tapped.withDayOfMonth(1).toEpochDay()
                            }
                            pendingScrollDay = epochDay
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                }
                if (uiState.items.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No events yet", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(uiState.items, key = { item ->
                            when (item) {
                                is CalendarListItem.DayHeader -> "day:${item.epochDay}"
                                // Occurrences of a recurring series share the
                                // event id; startSec disambiguates them.
                                is CalendarListItem.EventItem -> "event:${item.event.id}:${item.event.startSec}"
                                is CalendarListItem.AnniversaryItem ->
                                    "anniv:${item.contact.pubkey ?: item.contact.primaryEmail}:${item.anniversaryLabel}"
                            }
                        }) { item ->
                            when (item) {
                                is CalendarListItem.DayHeader -> DayHeaderRow(item.label)
                                is CalendarListItem.EventItem -> EventRow(
                                    event = item.event,
                                    onClick = { onNavigateToEditor(item.event.id) },
                                    onDelete = { deleteEvent = item.event }
                                )
                                is CalendarListItem.AnniversaryItem -> AnniversaryRow(
                                    item = item,
                                    onClick = { viewModel.openContact(item.contact) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Contact detail opened from an anniversary chip — openable from any tab
    // that renders derived chips (ANDROID_CONTACTS.md §4/§7). Compose/Edit
    // live in the Contacts tab; null disables them here.
    uiState.detailContact?.let { contact ->
        xyz.desent.presentation.ui.contacts.ContactDetailSheet(
            contact = contact,
            profile = uiState.detailProfile,
            onCompose = null,
            onEdit = null,
            onAddAnniversaryToCalendar = onAddAnniversaryToCalendar,
            onDismiss = { viewModel.dismissContact() }
        )
    }

    deleteEvent?.let { event ->
        DeleteConfirmationDialog(
            title = "Delete event?",
            message = "Delete \"${event.title}\"? " +
                "This removes the event on all devices.",
            onConfirm = {
                viewModel.deleteEvent(event.id)
                deleteEvent = null
            },
            onDismiss = { deleteEvent = null }
        )
    }
}

@Composable
private fun DayHeaderRow(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
    )
}

/**
 * Contact-derived anniversary row — the agenda-view counterpart of the web
 * month-grid chip: pink-tinted container with a dashed outline feel, glyph
 * (🎂 birthday / 💍 anniversary / 🔔 other) + contact name. Nothing is
 * stored; tapping opens the contact detail sheet.
 */
@Composable
private fun AnniversaryRow(
    item: xyz.desent.presentation.ui.calendar.CalendarListItem.AnniversaryItem,
    onClick: () -> Unit
) {
    val contact = item.contact
    val glyph = when (contact.nextAnniversary()?.kind) {
        xyz.desent.domain.model.AnniversaryKind.BIRTHDAY -> "🎂"
        xyz.desent.domain.model.AnniversaryKind.ANNIVERSARY -> "💍"
        else -> "🔔"
    }
    val name = contact.name.ifBlank { contact.primaryEmail.substringBefore("@") }
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.small,
        color = androidx.compose.ui.graphics.Color(0x1FF48FB1), // pink 12% tint
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = androidx.compose.ui.graphics.Color(0x66F48FB1) // pink 40%
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = glyph, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$name · ${item.anniversaryLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EventRow(
    event: NostrCalendarEvent,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val timeText = remember(event.id, event.allDay, event.startSec, event.endSec) {
        formatEventTime(event)
    }
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title.ifBlank { "(untitled)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (timeText != null) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!event.location.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (event.geohash != null) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                        Text(
                            text = event.location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete event")
            }
        }
    }
}

private fun formatEventTime(event: NostrCalendarEvent): String? {
    if (event.allDay) {
        val singleDay = event.endSec == null ||
            (event.endSec - event.startSec) <= 86_400L
        return if (singleDay) "All day" else "All day (multi-day)"
    }
    val zone = ZoneId.systemDefault()
    val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    val start = Instant.ofEpochSecond(event.startSec).atZone(zone)
    val end = event.endSec?.let { Instant.ofEpochSecond(it).atZone(zone) }
    return if (end == null) {
        start.format(timeFmt)
    } else {
        "${start.format(timeFmt)} – ${end.format(timeFmt)}"
    }
}
