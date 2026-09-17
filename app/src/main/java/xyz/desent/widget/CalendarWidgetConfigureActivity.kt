package xyz.desent.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.desent.DeSentApplication
import xyz.desent.R
import xyz.desent.presentation.theme.DeSentTheme

/**
 * Shown when the user adds the calendar widget (and again via long-press →
 * Reconfigure, since the provider declares `widgetFeatures="reconfigurable"`).
 * Picks the initial view; day/week/month can also be switched on the widget
 * itself at any time.
 */
class CalendarWidgetConfigureActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        appWidgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val widgetDataHelper = (applicationContext as DeSentApplication).widgetDataHelper
        val preselected = widgetDataHelper.getCalendarView(this, appWidgetId)

        setContent {
            var selected by remember { mutableStateOf(preselected) }

            DeSentTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text(stringResource(R.string.widget_calendar_title)) },
                        text = {
                            Column {
                                ModeRow(
                                    label = stringResource(R.string.widget_calendar_view_day),
                                    selected = selected == CalendarWidgetView.DAY,
                                    onClick = { selected = CalendarWidgetView.DAY }
                                )
                                Spacer(Modifier.height(4.dp))
                                ModeRow(
                                    label = stringResource(R.string.widget_calendar_view_week),
                                    selected = selected == CalendarWidgetView.WEEK,
                                    onClick = { selected = CalendarWidgetView.WEEK }
                                )
                                Spacer(Modifier.height(4.dp))
                                ModeRow(
                                    label = stringResource(R.string.widget_calendar_view_month),
                                    selected = selected == CalendarWidgetView.MONTH,
                                    onClick = { selected = CalendarWidgetView.MONTH }
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                widgetDataHelper.setCalendarView(this@CalendarWidgetConfigureActivity, appWidgetId, selected)
                                widgetDataHelper.setCalendarAnchor(this@CalendarWidgetConfigureActivity, appWidgetId, null)
                                val manager = AppWidgetManager.getInstance(this@CalendarWidgetConfigureActivity)
                                updateCalendarAppWidget(this@CalendarWidgetConfigureActivity, manager, appWidgetId)
                                val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                setResult(RESULT_OK, resultValue)
                                finish()
                            }) { Text(stringResource(R.string.widget_save)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { finish() }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
