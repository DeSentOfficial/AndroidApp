package xyz.desent.presentation.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.desent.widget.CalendarMonthCell
import xyz.desent.widget.buildMonthCells
import xyz.desent.widget.firstDayOfWeek
import xyz.desent.widget.weekdayLabels
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthTitleFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val cellDescriptionFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())

/**
 * The overall month calendar shown above the agenda on the calendar screen.
 * Same model as the home-screen widget's month view: a 6x7 grid built by
 * [buildMonthCells] (today circled, out-of-month days dimmed, an event dot
 * per day with entries), ‹ › chevrons to browse months, and a Today reset
 * that appears while browsing away.
 */
@Composable
fun MonthCalendarView(
    anchorMonth: LocalDate,
    selectedDay: Long?,
    eventDayCounts: Map<Long, Int>,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onTodayClick: () -> Unit,
    onDayClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val fdow = firstDayOfWeek()
    val today = LocalDate.now()
    val cells = remember(anchorMonth, eventDayCounts, today) {
        buildMonthCells(anchorMonth, today, fdow, eventDayCounts)
    }
    val labels = remember(fdow) { weekdayLabels(fdow) }
    val isCurrentMonth = anchorMonth.year == today.year && anchorMonth.month == today.month

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                text = anchorMonth.format(monthTitleFormatter),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onNextMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }
        if (!isCurrentMonth) {
            TextButton(
                onClick = onTodayClick,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("Today") }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    MonthCell(
                        cell = cell,
                        isSelected = cell.epochDay == selectedDay,
                        onClick = { onDayClick(cell.epochDay) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    cell: CalendarMonthCell,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = remember(cell.epochDay, cell.eventCount) { cellDescription(cell) }
    Box(
        modifier
            .aspectRatio(1f)
            .heightIn(max = 44.dp)
            .clip(CircleShape)
            .background(
                when {
                    cell.isToday -> MaterialTheme.colorScheme.primary
                    isSelected -> MaterialTheme.colorScheme.secondaryContainer
                    else -> Color.Transparent
                }
            )
            .then(
                if (isSelected && !cell.isToday) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                }
            )
            .selectable(selected = isSelected, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = cell.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (cell.inMonth) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                cell.isToday -> MaterialTheme.colorScheme.onPrimary
                cell.inMonth -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        if (cell.eventCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (cell.isToday) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.primary
                    )
            )
        }
    }
}

private fun cellDescription(cell: CalendarMonthCell): String {
    val date = LocalDate.ofEpochDay(cell.epochDay).format(cellDescriptionFormatter)
    return when (cell.eventCount) {
        0 -> date
        1 -> "$date, 1 event"
        else -> "$date, ${cell.eventCount} events"
    }
}
