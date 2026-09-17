package xyz.desent.presentation.ui.calendar

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.NostrCalendar
import xyz.desent.presentation.ui.calendar.viewmodel.CalendarListsViewModel
import xyz.desent.presentation.ui.components.DeleteConfirmationDialog
import xyz.desent.presentation.ui.components.DesentTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarsScreen(
    onNavigateBack: () -> Unit,
    viewModel: CalendarListsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showCreate by remember { mutableStateOf(false) }
    var deleteCalendar by remember { mutableStateOf<NostrCalendar?>(null) }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendars") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "New calendar")
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.calendars.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No calendars yet", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.calendars, key = { it.id }) { cal ->
                    CalendarRow(
                        calendar = cal,
                        onDelete = { deleteCalendar = cal }
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateCalendarDialog(
            onDismiss = { showCreate = false },
            onCreate = { title, color ->
                viewModel.createCalendar(title, color)
                showCreate = false
            }
        )
    }

    deleteCalendar?.let { cal ->
        val count = cal.eventDs.size
        DeleteConfirmationDialog(
            title = "Delete calendar?",
            message = "Delete \"${cal.title}\"? " +
                "This removes the calendar and its $count event" +
                "${if (count == 1) "" else "s"} on all devices.",
            onConfirm = {
                viewModel.deleteCalendar(cal.id)
                deleteCalendar = null
            },
            onDismiss = { deleteCalendar = null }
        )
    }
}

@Composable
private fun CalendarRow(calendar: NostrCalendar, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ColorDot(calendar.color)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    calendar.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val count = calendar.eventDs.size
                Text(
                    "$count event${if (count == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete calendar")
            }
        }
    }
}

@Composable
private fun ColorDot(hex: String?) {
    val color = remember(hex) {
        runCatching { Color(android.graphics.Color.parseColor(hex ?: "#64748b")) }
            .getOrDefault(Color(0xFF64748B))
    }
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun CreateCalendarDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, color: String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(CalendarListsViewModel.COLORS.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New calendar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DesentTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CalendarListsViewModel.COLORS.take(8).forEach { hex ->
                        val c = runCatching {
                            Color(android.graphics.Color.parseColor(hex))
                        }.getOrDefault(Color.Gray)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(c)
                                .clickable { selectedColor = hex },
                        ) {
                            if (hex == selectedColor) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(4.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title, selectedColor) },
                enabled = title.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
