package xyz.desent.wear.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.remote.interactions.RemoteActivityHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import xyz.desent.data.wearsync.WearCalendarEvent
import xyz.desent.wear.WearAppContainer

/**
 * Read-only event reader for one occurrence. Recurring occurrences share a
 * series id, so the nav lookup pairs id + startSec.
 */
@Composable
fun CalendarDetailScreen(appContainer: WearAppContainer, eventId: String, startSec: Long) {
    val calendar by appContainer.calendar.collectAsState()
    val event = remember(calendar, eventId, startSec) {
        calendar?.events?.firstOrNull { it.id == eventId && it.startSec == startSec }
    }

    TimeText()

    ScalingLazyColumn {
        if (event == null) {
            item {
                Text(
                    text = "This event is no longer in the synced snapshot — it may have been " +
                        "edited or deleted on the phone. Resync from Settings.",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            return@ScalingLazyColumn
        }

        item {
            Text(
                text = event.title,
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Text(
                text = eventDateTimeText(event),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (event.location.isNotBlank()) {
            item {
                Text(
                    text = event.location,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (event.description.isNotBlank()) {
            item {
                Text(
                    text = event.description,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            OpenEventOnPhoneChip(event)
        }
    }
}

@Composable
private fun OpenEventOnPhoneChip(event: WearCalendarEvent) {
    val context = LocalContext.current
    MenuChip(
        label = "Open on phone",
        onClick = {
            val epochDay = if (event.allDay) {
                event.startSec / 86_400
            } else {
                Instant.ofEpochSecond(event.startSec)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toEpochDay()
            }
            val uri = Uri.parse("desent://calendar?date=$epochDay")
            runCatching {
                RemoteActivityHelper(context, Runnable::run)
                    .startRemoteActivity(Intent(Intent.ACTION_VIEW, uri))
                Toast.makeText(context, "Opening on phone…", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Phone not reachable", Toast.LENGTH_SHORT).show()
            }
        }
    )
}

/** Full date + time line: "EEE, MMM d · 2:00 PM – 3:00 PM", "EEE, MMM d · All day", or a multi-day ISO range. */
internal fun eventDateTimeText(event: WearCalendarEvent): String {
    val zone = ZoneId.systemDefault()
    val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d")
    return if (event.allDay) {
        val start = LocalDate.ofEpochDay(event.startSec / 86_400)
        val endExclusive = event.endDateIso?.let { LocalDate.parse(it) }
        if (endExclusive != null && endExclusive.isAfter(start.plusDays(1))) {
            "${start.format(dateFmt)} – ${endExclusive.minusDays(1).format(dateFmt)} · All day"
        } else {
            "${start.format(dateFmt)} · All day"
        }
    } else {
        val start = Instant.ofEpochSecond(event.startSec).atZone(zone)
        val timeText = eventTimeText(event)
        "${start.format(dateFmt)} · $timeText"
    }
}
