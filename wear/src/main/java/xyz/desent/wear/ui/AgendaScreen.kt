package xyz.desent.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import xyz.desent.data.wearsync.WearCalendar
import xyz.desent.data.wearsync.WearCalendarEvent
import xyz.desent.wear.WearAppContainer

/**
 * Watch agenda: the phone's expanded today+14d occurrence list grouped by
 * day, with contact anniversaries interleaved on their day. Read-only —
 * tap opens the detail screen; "Open on phone" hands off from there.
 */
@Composable
fun AgendaScreen(
    appContainer: WearAppContainer,
    onOpenEvent: (id: String, startSec: Long) -> Unit
) {
    val calendar by appContainer.calendar.collectAsState()
    val zone = ZoneId.systemDefault()

    TimeText()

    ScalingLazyColumn {
        if (calendar == null || (calendar?.events.isNullOrEmpty() && calendar?.anniversaries.isNullOrEmpty())) {
            item {
                Text(
                    text = if (calendar == null) {
                        "No calendar synced yet. Open DeSent on your phone, then Resync in Settings."
                    } else {
                        "Nothing scheduled for the next two weeks."
                    },
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (calendar == null) {
                item {
                    MenuChip(label = "Resync now", onClick = { appContainer.requestCalendarFromPhone() })
                }
            }
            return@ScalingLazyColumn
        }

        val cal = calendar!!
        val today = LocalDate.now(zone)
        val byDay: Map<Long, List<WearCalendarEvent>> = cal.events
            .groupBy { it.startDay(zone) }
        val anniversariesByDay: Map<Long, List<xyz.desent.data.wearsync.WearAnniversary>> =
            cal.anniversaries.groupBy { it.epochDay }

        val days = (byDay.keys + anniversariesByDay.keys).sorted()
        days.forEach { day ->
            item(key = "day:$day") {
                Text(
                    text = dayLabel(day, today),
                    style = MaterialTheme.typography.caption1,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.primary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            items(byDay[day].orEmpty(), key = { "event:${it.id}:${it.startSec}" }) { event ->
                EventRow(event = event, onClick = { onOpenEvent(event.id, event.startSec) })
            }
            items(anniversariesByDay[day].orEmpty(), key = { "anniv:${it.key}" }) { anniversary ->
                Chip(
                    onClick = { },
                    label = {
                        Column {
                            Text(
                                text = anniversary.name,
                                style = MaterialTheme.typography.body2,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = anniversary.label,
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSurfaceVariant
                            )
                        }
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun EventRow(event: WearCalendarEvent, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = {
            Column {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(eventTimeText(event), event.location.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Start day for grouping — ISO dates for all-day (no zone math), zone-converted for timed. */
internal fun WearCalendarEvent.startDay(zone: ZoneId): Long =
    if (allDay) startSec / 86_400 else Instant.ofEpochSecond(startSec).atZone(zone).toLocalDate().toEpochDay()

/** "h:mm a" or "h:mm a – h:mm a"; all-day mirrors the phone's labels. */
internal fun eventTimeText(event: WearCalendarEvent): String {
    if (event.allDay) {
        val endIso = event.endDateIso
        val startIso = event.startDateIso
        val multiDay = endIso != null && startIso != null && endIso > startIso
        return if (multiDay) "All day (multi-day)" else "All day"
    }
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochSecond(event.startSec).atZone(zone)
    val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
    return event.endSec?.let { end ->
        val endTime = Instant.ofEpochSecond(end).atZone(zone)
        if (endTime.toLocalDate() == start.toLocalDate()) {
            "${start.format(timeFmt)} – ${endTime.format(timeFmt)}"
        } else {
            start.format(timeFmt)
        }
    } ?: start.format(timeFmt)
}

private val dayFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

internal fun dayLabel(epochDay: Long, today: LocalDate): String = when (epochDay) {
    today.toEpochDay() -> "Today"
    today.toEpochDay() + 1 -> "Tomorrow"
    else -> LocalDate.ofEpochDay(epochDay).format(dayFormatter)
}
