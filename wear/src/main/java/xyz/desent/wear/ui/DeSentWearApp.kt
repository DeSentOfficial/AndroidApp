package xyz.desent.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import xyz.desent.wear.WearAppContainer
import xyz.desent.wear.ui.theme.DeSentWearTheme

/** Root of the watch UI: DeSent theme (phone-synced mode) + swipe-dismiss nav. */
@Composable
fun DeSentWearApp(
    appContainer: WearAppContainer,
    initialDestination: String? = null
) {
    val navController = rememberSwipeDismissableNavController()
    val themeMode by appContainer.themeMode.collectAsState()

    // Deep launch (e.g. the bunker notification) routes straight to a screen.
    LaunchedEffect(initialDestination) {
        if (initialDestination != null) navController.navigate(initialDestination)
    }

    DeSentWearTheme(themeMode = themeMode) {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = "home"
        ) {
            composable("home") {
                HomeScreen(
                    appContainer = appContainer,
                    onNavigate = { navController.navigate(it) }
                )
            }
            composable("inbox") {
                InboxScreen(
                    appContainer = appContainer,
                    isSpam = false,
                    onOpenEmail = { id -> navController.navigate("email/$id") }
                )
            }
            composable("spam") {
                InboxScreen(
                    appContainer = appContainer,
                    isSpam = true,
                    onOpenEmail = { id -> navController.navigate("email/$id") }
                )
            }
            composable("agenda") {
                AgendaScreen(
                    appContainer = appContainer,
                    onOpenEvent = { id, startSec -> navController.navigate("calevent/$id/$startSec") }
                )
            }
            composable(
                route = "calevent/{id}/{startSec}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("startSec") { type = NavType.LongType }
                )
            ) { backStackEntry ->
                CalendarDetailScreen(
                    appContainer = appContainer,
                    eventId = backStackEntry.arguments?.getString("id").orEmpty(),
                    startSec = backStackEntry.arguments?.getLong("startSec") ?: 0L
                )
            }
            composable(
                route = "email/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { backStackEntry ->
                EmailDetailScreen(
                    appContainer = appContainer,
                    emailId = backStackEntry.arguments?.getString("id").orEmpty()
                )
            }
            composable("bunker") {
                BunkerScreen(appContainer = appContainer)
            }
            composable("settings") {
                SettingsScreen(appContainer = appContainer)
            }
        }
    }
}
