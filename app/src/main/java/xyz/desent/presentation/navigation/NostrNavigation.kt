package xyz.desent.presentation.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import xyz.desent.di.AppContainer
import xyz.desent.presentation.ui.components.AppNavigationBar
import xyz.desent.presentation.ui.login.LoginScreen
import xyz.desent.presentation.ui.email.EmailInboxScreen
import xyz.desent.presentation.ui.email.EmailDetailScreen
import xyz.desent.presentation.ui.email.viewmodel.EmailDetailViewModel
import xyz.desent.presentation.ui.alias.AliasScreen
import xyz.desent.presentation.ui.alias.viewmodel.AliasViewModel
import xyz.desent.presentation.ui.files.FilesScreen
import xyz.desent.presentation.ui.files.viewmodel.FilesViewModel
import xyz.desent.presentation.ui.profile.ProfileEditViewModel
import xyz.desent.presentation.ui.settings.SettingsScreen
import xyz.desent.presentation.ui.components.InAppNotificationContainer
import xyz.desent.presentation.ui.splash.SplashScreen
import xyz.desent.presentation.viewmodel.EmailDetailViewModelFactory
import xyz.desent.presentation.viewmodel.NotificationsViewModelFactory
import xyz.desent.presentation.viewmodel.SecurityAlertsViewModelFactory
import xyz.desent.presentation.viewmodel.SpamDetailViewModelFactory
import xyz.desent.presentation.viewmodel.ViewModelFactory

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Login : Screen("login") {
        fun createRoute() = "login"
        /**
         * The route pattern with the optional `additive` and `ref` (invite code
         * from a desent://register or https://desent.xyz/register link)
         * query parameters.
         */
        const val routeWithArgs = "login?additive={additive}&ref={ref}"
        object Scan : Screen("login/scan") {
            fun createRoute() = "login/scan"
        }
        /** Blink-style custody chooser (custodial vs self-custodial). */
        object Create : Screen("login/create") {
            fun createRoute() = "login/create"
        }
        /** Account-creation form for the mode chosen on [Create]. */
        object CreateDetails : Screen("login/create/details") {
            fun createRoute() = "login/create/details"
        }
        /** Existing-account login (username & password vs nostr key). */
        object Existing : Screen("login/existing") {
            fun createRoute() = "login/existing"
        }
    }
    object Notifications : Screen("notifications") {
        fun createRoute() = "notifications"
    }
    object ProfileEdit : Screen("profile/edit") {
        fun createRoute() = "profile/edit"
    }
    object Settings : Screen("settings") {
        fun createRoute() = "settings"
        object SpamPolicy : Screen("settings/spam-policy") {
            fun createRoute() = "settings/spam-policy"
        }
        object ImagePolicy : Screen("settings/image-policy") {
            fun createRoute() = "settings/image-policy"
        }
        object PgpKey : Screen("settings/pgp") {
            fun createRoute() = "settings/pgp"
        }
        object RollKey : Screen("settings/roll-key") {
            fun createRoute() = "settings/roll-key"
        }
        object Invites : Screen("settings/invites") {
            fun createRoute() = "settings/invites"
        }
        object Agents : Screen("settings/agents") {
            fun createRoute() = "settings/agents"
        }
        // Hub-and-spoke drill-downs from the Settings App tab.
        object Appearance : Screen("settings/appearance") {
            fun createRoute() = "settings/appearance"
        }
        object Privacy : Screen("settings/privacy") {
            fun createRoute() = "settings/privacy"
        }
        object Notifications : Screen("settings/notifications") {
            fun createRoute() = "settings/notifications"
        }
        object Mail : Screen("settings/mail") {
            fun createRoute() = "settings/mail"
        }
        object AntiSpam : Screen("settings/anti-spam") {
            fun createRoute() = "settings/anti-spam"
        }
        object MarkdownFiles : Screen("settings/markdown") {
            fun createRoute() = "settings/markdown"
        }
        object Fanout : Screen("settings/fanout") {
            fun createRoute() = "settings/fanout"
        }
    }
    object Backup : Screen("backup?npub={npub}") {
        /** Route pattern with the optional account-scoping `npub` parameter. */
        const val routeWithArgs = "backup?npub={npub}"
        fun createRoute(npub: String? = null): String =
            if (npub.isNullOrEmpty()) "backup?npub=" else "backup?npub=$npub"
    }
    object Restore : Screen("restore?activate={activate}&fileUri={fileUri}") {
        const val routeWithArgs = "restore?activate={activate}&fileUri={fileUri}"
        fun createRoute(activate: Boolean, fileUri: String? = null): String {
            val encoded = fileUri?.let { android.net.Uri.encode(it) } ?: ""
            return "restore?activate=$activate&fileUri=$encoded"
        }
    }
    /**
     * Markdown document opened from the device (file-manager ACTION_VIEW or
     * the Notes in-app picker). `fileUri` is a URL-encoded content:// or
     * file:// URI.
     */
    object MarkdownViewer : Screen("markdown-viewer?fileUri={fileUri}") {
        const val routeWithArgs = "markdown-viewer?fileUri={fileUri}"
        fun createRoute(fileUri: String): String =
            "markdown-viewer?fileUri=${android.net.Uri.encode(fileUri)}"
    }
    object Files : Screen("files") {
        fun createRoute() = "files"
    }
    object Storage : Screen("storage") {
        fun createRoute() = "storage"
    }
    object Billing : Screen("billing") {
        fun createRoute() = "billing"
    }
    object Notes : Screen("notes") {
        object List : Screen("notes/list") {
            fun createRoute() = "notes/list"
        }
        object Editor : Screen("notes/editor?noteId={noteId}") {
            const val routeWithArgs = "notes/editor?noteId={noteId}"
            fun createRoute(noteId: String? = null) =
                if (noteId.isNullOrEmpty()) "notes/editor?noteId=" else "notes/editor?noteId=$noteId"
        }
    }
    object ContactsBook : Screen("contacts") {
        fun createRoute() = "contacts"
    }
    object Calendar : Screen("calendar") {
        object List : Screen("calendar/list?date={date}") {
            /**
             * Route pattern with the optional `date` (epoch day) used by the
             * calendar widget to deep-link to a specific day.
             */
            const val routeWithArgs = "calendar/list?date={date}"
            fun createRoute(dateEpochDay: Long? = null): String =
                if (dateEpochDay == null) "calendar/list" else "calendar/list?date=$dateEpochDay"
        }
        object Lists : Screen("calendar/lists") {
            fun createRoute() = "calendar/lists"
        }
        object Editor : Screen("calendar/editor?eventId={eventId}") {
            const val routeWithArgs =
                "calendar/editor?eventId={eventId}&title={title}&date={date}&desc={desc}" +
                    "&location={location}&start={start}&end={end}"

             fun createRoute(eventId: String? = null) =
                 if (eventId.isNullOrEmpty()) "calendar/editor?eventId=" else "calendar/editor?eventId=$eventId"

            /**
             * Prefilled route for contact-anniversary "Add to calendar": opens
             * the all-day (kind 31922) editor on [epochDay] with [title].
             */
            fun createPrefilledRoute(
                title: String,
                epochDay: Long,
                description: String,
                location: String = "",
                startSec: Long = 0L,
                endSec: Long = 0L
            ): String {
                val t = android.net.Uri.encode(title)
                val d = android.net.Uri.encode(description)
                val loc = android.net.Uri.encode(location)
                return "calendar/editor?eventId=&title=$t&date=$epochDay&desc=$d" +
                    "&location=$loc&start=$startSec&end=$endSec"
            }
        }
    }
    object Emails : Screen("emails") {
        fun createRoute() = "emails"
        object Inbox : Screen("emails/inbox") {
            fun createRoute() = "emails/inbox"
        }
        object Outbox : Screen("emails/outbox") {
            fun createRoute() = "emails/outbox"
        }
        object Detail : Screen("emails/detail/{emailId}") {
            fun createRoute(emailId: String) = "emails/detail/$emailId"
        }
        object Reply : Screen("emails/reply/{emailId}?draft={draft}&replyAll={replyAll}") {
            fun createRoute(emailId: String, draft: String? = null, replyAll: Boolean = false): String {
                val query = buildList {
                    if (!draft.isNullOrBlank()) add("draft=${android.net.Uri.encode(draft)}")
                    if (replyAll) add("replyAll=true")
                }.joinToString("&")
                return if (query.isEmpty()) {
                    "emails/reply/$emailId"
                } else {
                    "emails/reply/$emailId?$query"
                }
            }
        }
        object Thread : Screen("emails/thread/{threadKey}") {
            fun createRoute(threadKey: String) = "emails/thread/$threadKey"
        }
        object Compose : Screen("emails/compose") {
            /**
             * Route pattern with the optional URL-encoded `to` (recipient)
             * used by the contacts detail sheet's Compose action.
             */
            const val routeWithArgs = "emails/compose?to={to}"
            fun createRoute(to: String? = null): String {
                val encoded = to?.let { android.net.Uri.encode(it) } ?: ""
                return "emails/compose?to=$encoded"
            }
        }
        object Forward : Screen("emails/forward") {
            fun createRoute() = "emails/forward"
        }
        object Aliases : Screen("emails/aliases") {
            fun createRoute() = "emails/aliases"
        }
        object SpamDetail : Screen("emails/spam/{emailId}") {
            fun createRoute(emailId: String) = "emails/spam/$emailId"
        }
        object Security : Screen("emails/security?alertId={alertId}&npub={npub}") {
            /**
             * Route pattern with the optional deep-link `alertId` and the
             * account-scoping `npub` (account switcher drill-in) parameters.
             */
            const val routeWithArgs = "emails/security?alertId={alertId}&npub={npub}"
            fun createRoute(alertId: String? = null, npub: String? = null): String {
                val base =
                    if (alertId.isNullOrEmpty()) "emails/security?alertId=" else "emails/security?alertId=$alertId"
                return if (npub.isNullOrEmpty()) base else "$base&npub=$npub"
            }
        }
    }
    object Nip46 : Screen("nip46") {
        fun createRoute() = "nip46"
        object RemoteSigning : Screen("nip46/remote?npub={npub}") {
            /** Route pattern with the optional account-scoping `npub` parameter. */
            const val routeWithArgs = "nip46/remote?npub={npub}"
            fun createRoute(npub: String? = null): String =
                if (npub.isNullOrEmpty()) "nip46/remote?npub=" else "nip46/remote?npub=$npub"
        }
        object Scan : Screen("nip46/scan") {
            fun createRoute() = "nip46/scan"
        }
        object BunkerCode : Screen("nip46/bunkercode") {
            fun createRoute() = "nip46/bunkercode"
        }
        object Confirm : Screen("nip46/confirm") {
            fun createRoute() = "nip46/confirm"
        }
        object Detail : Screen("nip46/detail/{sessionPubkey}") {
            fun createRoute(sessionPubkey: String) = "nip46/detail/$sessionPubkey"
        }
    }
}

@Composable
fun NostrNavigation(
    navController: NavHostController,
    appContainer: AppContainer,
    pendingWidgetRoute: androidx.compose.runtime.State<String?>? = null,
    onPendingRouteConsumed: () -> Unit = {}
) {
    val factory = ViewModelFactory(appContainer)

    // One activity-scoped NIP-46 VM shared across the pair flow (Scan -> Confirm
    // -> Detail) so state like the scanned pairing URI survives route changes.
    // (viewModel() here resolves against the activity's ViewModelStoreOwner, not
    // a NavBackStackEntry, since it's outside the NavHost.)
    val nip46ViewModel: xyz.desent.presentation.ui.nip46.Nip46ViewModel = viewModel(factory = factory)

    // One activity-scoped Login VM shared across the login and login/scan routes
    // so a scanned key (set via onQrCodeScanned) reaches the form the user sees.
    val loginViewModel: xyz.desent.presentation.ui.login.viewmodel.LoginViewModel = viewModel(factory = factory)

    // Routes that share the bottom navigation bar (the avatar switcher + 4
    // section tabs). Pushed/detail screens (EmailDetail, EditRule, etc.)
    // are excluded so they render full-screen with their own back navigation.
    val mainLevelRoutes = remember {
        setOf(
            Screen.Emails.Inbox.route,
            Screen.Emails.Outbox.route,
            Screen.Files.route,
            Screen.ContactsBook.route,
            Screen.Notes.List.route,
            Screen.Calendar.List.route,
            Screen.Storage.route,
            Screen.Settings.route
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in mainLevelRoutes

    // Consume a widget-pushed navigation target (note / new note / DM) once the
    // user has reached the Inbox (home) route. Waiting for it keeps cold
    // launches — where the nav graph starts at Splash — from racing the splash
    // gate.
    val targetRoute = pendingWidgetRoute?.value
    LaunchedEffect(currentRoute, targetRoute) {
        if (targetRoute != null && currentRoute == Screen.Emails.Inbox.route) {
            navController.navigate(targetRoute) { launchSingleTop = true }
            onPendingRouteConsumed()
        }
    }

    // Unread counts power the badge on the Email tab (and the shield
    // on the avatar tab). Computed at the outer level so every main-level
    // screen sees consistent values without each having to plumb them
    // through individually.
    val unreadCounts by rememberUnreadCounts(appContainer)
    val unreadEmailCount = unreadCounts.first
    val unseenSecurityCount = unreadCounts.second

    // In-app new-email banners: collect arrivals once at the app level so the
    // banner can surface no matter which screen the user is on (the click
    // handler routes into the email thread).
    val inAppNotificationManager = remember { appContainer.inAppNotificationManager }
    LaunchedEffect(Unit) {
        appContainer.nostrEventProcessor.emailArrivals.collect { email ->
            if (email.emailType == xyz.desent.domain.model.EmailType.SYSTEM) return@collect
            if (email.direction == xyz.desent.domain.model.EmailDirection.OUTBOUND) return@collect
            val enabled =
                appContainer.preferencesManager.areInAppNotificationsEnabled.firstOrNull() ?: true
            if (!enabled) return@collect
            val threadKey = email.threadKey
            // Room-only URL (picture via pubkey/NIP-05 link, else cached
            // favicon) — resolved instantly, bytes load async in the banner.
            val avatarUrl = runCatching {
                appContainer.notificationAvatarFactory.avatarUrlFor(email)
            }.getOrNull()
            inAppNotificationManager.showNewEmailNotification(
                senderName = email.displaySender,
                subject = email.subject,
                threadKey = threadKey,
                emailId = email.id,
                avatarUrl = avatarUrl,
                avatarSeed = email.senderEmail
            )
        }
    }

    // Called after an account switch completes. Pops Inbox inclusively so the
    // destination (and therefore its ViewModels) is fully destroyed and
    // recreated for the new account — otherwise ViewModels keep the old npub.
    val onAccountSwitched: () -> Unit = {
        navController.navigate(Screen.Emails.Inbox.route) {
            popUpTo(Screen.Emails.Inbox.route) { inclusive = true }
        }
    }

    // Navigate into one of the main-level tabs. Reuses an existing copy of
    // the destination if present on the back stack so the user doesn't accrue
    // an unbounded pile of tab entries.
    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(Screen.Emails.Inbox.route) { inclusive = false }
            launchSingleTop = true
        }
    }

    val navigateToAddAccount: () -> Unit = {
        navController.navigate(Screen.Login.createRoute() + "?additive=1")
    }

    // contentWindowInsets is zeroed: every destination either supplies its own
    // chrome (M3 Scaffold + TopAppBar, which apply the status-bar inset
    // themselves) or pads itself (login/sign screens). With the default insets
    // here AND a TopAppBar inset inside each screen, the status bar height
    // was applied twice — the blank band above every screen title.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar && currentRoute != null) {
                AppNavigationBar(
                    selectedRoute = currentRoute,
                    unreadEmailCount = unreadEmailCount,
                    unseenSecurityCount = unseenSecurityCount,
                    onNavigateEmails = { navigateToTab(Screen.Emails.Inbox.route) },
                    onNavigateFiles = { navigateToTab(Screen.Files.route) },
                    onNavigateContacts = { navigateToTab(Screen.ContactsBook.route) },
                    onNavigateNotes = { navigateToTab(Screen.Notes.List.route) },
                    onNavigateCalendar = { navigateToTab(Screen.Calendar.List.createRoute()) },
                    onNavigateStorage = { navigateToTab(Screen.Storage.route) },
                    onNavigateSettings = { navigateToTab(Screen.Settings.route) },
                    onNavigateToAddAccount = navigateToAddAccount,
                    onEditAccount = {
                        navController.navigate(Screen.ProfileEdit.createRoute())
                    },
                    onOpenBunkerConnections = { npub ->
                        navController.navigate(Screen.Nip46.RemoteSigning.createRoute(npub))
                    },
                    onOpenSecurityAlerts = { npub ->
                        navController.navigate(Screen.Emails.Security.createRoute(npub = npub))
                    },
                    onOpenBackup = { npub ->
                        navController.navigate(Screen.Backup.createRoute(npub))
                    },
                    onOpenPgpSettings = {
                        navController.navigate(Screen.Settings.PgpKey.createRoute())
                    },
                    onAccountSwitched = onAccountSwitched
                )
            }
        }
    ) { innerPadding ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            // Report the padding just applied as consumed, so nested Scaffolds
            // (every screen) don't re-apply the same system insets to their
            // own content — that double-count showed up as a blank band
            // between screen content and the bottom bar. Destinations rendered
            // without the bottom bar receive zero padding here and thus
            // consume nothing, keeping their own bottom-inset handling intact.
            .consumeWindowInsets(innerPadding),
        contentAlignment = Alignment.Center
    ) {
        NavHost(
            navController = navController,
            startDestination = Screen.Splash.route,
            enterTransition = {
                fadeIn(animationSpec = tween(220, delayMillis = 30)) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(220, delayMillis = 30)
                )
            },
            exitTransition = {
                fadeOut(animationSpec = tween(220))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(220, delayMillis = 30)) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(220, delayMillis = 30)
                )
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(220))
            }
        ) {
        composable(Screen.Splash.route) {
            val viewModel: xyz.desent.presentation.ui.splash.viewmodel.SplashViewModel = viewModel(factory = factory)
            SplashScreen(
                onNavigateToLogin = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                },
                onNavigateToInbox = {
                    navController.navigate(Screen.Emails.Inbox.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                },
                viewModel = viewModel
            )
        }
        
        composable(
            route = Screen.Login.routeWithArgs,
            arguments = listOf(
                navArgument("additive") {
                    type = NavType.StringType
                    defaultValue = "0"
                },
                navArgument("ref") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            ),
            deepLinks = listOf(
                // Invite links: desent://register?ref=CODE and the web wizard URL.
                navDeepLink { uriPattern = "desent://register?ref={ref}" },
                navDeepLink { uriPattern = "https://desent.xyz/register?ref={ref}" }
            )
        ) { backStackEntry ->
            val additive = backStackEntry.arguments?.getString("additive") == "1"
            val referralRef = backStackEntry.arguments?.getString("ref")
                ?.takeIf { it.isNotBlank() }
            LoginScreen(
                additive = additive,
                referralCodeArg = referralRef,
                onLoginSuccess = {
                    if (additive) {
                        // Adding a new account: pop back to Inbox instead of
                        // replacing the Login stack, so the user lands back on
                        // the home screen with the new account active.
                        navController.popBackStack(Screen.Emails.Inbox.route, inclusive = false)
                    } else {
                        navController.navigate(Screen.Emails.Inbox.route) {
                            popUpTo(Screen.Login.createRoute()) { inclusive = true }
                        }
                    }
                },
                onNavigateToCreate = {
                    navController.navigate(Screen.Login.Create.createRoute())
                },
                onNavigateToExisting = {
                    navController.navigate(Screen.Login.Existing.createRoute())
                },
                viewModel = loginViewModel
            )
        }

        // Blink-style custody chooser: custodial vs self-custodial.
        composable(Screen.Login.Create.createRoute()) {
            xyz.desent.presentation.ui.login.CreateAccountChooserScreen(
                onBack = { navController.popBackStack() },
                onContinue = {
                    navController.navigate(Screen.Login.CreateDetails.createRoute())
                },
                viewModel = loginViewModel
            )
        }

        composable(Screen.Login.CreateDetails.createRoute()) {
            xyz.desent.presentation.ui.login.CreateAccountFormScreen(
                onBack = { navController.popBackStack() },
                onLoginSuccess = {
                    navController.navigate(Screen.Emails.Inbox.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                viewModel = loginViewModel
            )
        }

        composable(Screen.Login.Existing.createRoute()) {
            xyz.desent.presentation.ui.login.LoginMethodScreen(
                onBack = { navController.popBackStack() },
                onLoginSuccess = {
                    navController.navigate(Screen.Emails.Inbox.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToQrScanner = {
                    navController.navigate(Screen.Login.Scan.createRoute())
                },
                onNavigateToRestore = {
                    navController.navigate(Screen.Restore.createRoute(activate = true))
                },
                viewModel = loginViewModel
            )
        }

        composable(Screen.Login.Scan.createRoute()) {
            xyz.desent.presentation.ui.shared.QrScannerScreen(
                onScanned = { text ->
                    loginViewModel.onQrCodeScanned(text)
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() }
            )
        }

        composable(Screen.ProfileEdit.createRoute()) {
            val viewModel: ProfileEditViewModel = viewModel(factory = factory)
            xyz.desent.presentation.ui.profile.ProfileEditScreen(
                onNavigateBack = { navController.popBackStack() },
                onLoggedOut = { result ->
                    when (result) {
                        is xyz.desent.domain.repository.LogoutResult.Switched -> {
                            // Account removed; another is now active. Pop Inbox
                            // inclusively so its ViewModels are destroyed and
                            // recreated for the auto-switched account.
                            navController.navigate(Screen.Emails.Inbox.route) {
                                popUpTo(Screen.Emails.Inbox.route) { inclusive = true }
                            }
                        }
                        xyz.desent.domain.repository.LogoutResult.FullyLoggedOut -> {
                            // Last account removed; go to Login.
                            navController.navigate(Screen.Login.createRoute()) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }
                },
                onNavigateToInvites = {
                    navController.navigate(Screen.Settings.Invites.createRoute())
                },
                onNavigateToRollKey = {
                    navController.navigate(Screen.Settings.RollKey.createRoute())
                },
                viewModel = viewModel,
            )
        }

        composable(
            route = Screen.Notifications.route,
            deepLinks = listOf(
                navDeepLink { uriPattern = "desent://notifications" }
            )
        ) {
            val notificationsFactory = NotificationsViewModelFactory(appContainer)
            val viewModel: xyz.desent.presentation.ui.notifications.viewmodel.NotificationsViewModel =
                viewModel(factory = notificationsFactory)
            xyz.desent.presentation.ui.notifications.NotificationsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSecurityAlert = { alertId ->
                    navController.navigate(Screen.Emails.Security.createRoute(alertId = alertId))
                },
                viewModel = viewModel
            )
        }

        composable(Screen.Emails.Inbox.createRoute()) {
            val viewModel: xyz.desent.presentation.ui.email.viewmodel.EmailInboxViewModel = viewModel(factory = factory)
            EmailInboxScreen(
                onNavigateToThread = { threadKey ->
                    navController.navigate(Screen.Emails.Thread.createRoute(threadKey))
                },
                onNavigateToCompose = {
                    navController.navigate(Screen.Emails.Compose.createRoute())
                },
                onNavigateToForward = {
                    navController.navigate(Screen.Emails.Forward.createRoute())
                },
                onNavigateToAliases = {
                    navController.navigate(Screen.Emails.Aliases.createRoute())
                },
                onNavigateToOutbox = {
                    navController.navigate(Screen.Emails.Outbox.createRoute())
                },
                onNavigateToSpamDetail = { emailId ->
                    navController.navigate(Screen.Emails.SpamDetail.createRoute(emailId))
                },
                onNavigateToSpamPolicy = {
                    navController.navigate(Screen.Settings.SpamPolicy.createRoute())
                },
                onNavigateToImagePolicy = {
                    navController.navigate(Screen.Settings.ImagePolicy.createRoute())
                },
                viewModel = viewModel
            )
        }

        composable(Screen.Emails.Outbox.createRoute()) {
            val outboxViewModel: xyz.desent.presentation.ui.email.viewmodel.EmailOutboxViewModel =
                viewModel {
                    xyz.desent.presentation.ui.email.viewmodel.EmailOutboxViewModel(
                        appContainer.emailUseCase,
                        appContainer.preferencesManager
                    )
                }
            xyz.desent.presentation.ui.email.EmailOutboxScreen(
                onNavigateToThread = { threadKey ->
                    navController.navigate(Screen.Emails.Thread.createRoute(threadKey))
                },
                viewModel = outboxViewModel
            )
        }

        composable(Screen.Emails.Forward.createRoute()) {
            val forwardViewModel: xyz.desent.presentation.ui.email.viewmodel.EmailForwardViewModel =
                viewModel {
                    xyz.desent.presentation.ui.email.viewmodel.EmailForwardViewModel(
                        appContainer.emailUseCase,
                        appContainer.preferencesManager,
                        appContainer.mailTransferManager,
                        appContainer.context
                    )
                }
            xyz.desent.presentation.ui.email.EmailForwardScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = forwardViewModel
            )
        }

        composable(
            route = Screen.Emails.SpamDetail.route,
            arguments = listOf(navArgument("emailId") { type = NavType.StringType })
        ) { backStackEntry ->
            val emailId = backStackEntry.arguments?.getString("emailId") ?: return@composable

            val spamDetailFactory = SpamDetailViewModelFactory(emailId, appContainer)
            val viewModel: xyz.desent.presentation.ui.email.viewmodel.SpamDetailViewModel =
                viewModel(factory = spamDetailFactory)

            xyz.desent.presentation.ui.email.SpamDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable(
            route = Screen.Emails.Security.routeWithArgs,
            arguments = listOf(
                navArgument("alertId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("npub") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "desent://security?alertId={alertId}" }
            )
        ) { backStackEntry ->
            val initialAlertId = backStackEntry.arguments?.getString("alertId")?.takeIf { it.isNotBlank() }
            // npub scopes the screen to one account (account switcher
            // drill-in); blank = the active account (plain / deep-link entry).
            val ownerNpub = backStackEntry.arguments?.getString("npub")?.takeIf { it.isNotBlank() }
            val securityFactory = SecurityAlertsViewModelFactory(initialAlertId, ownerNpub, appContainer)
            val viewModel: xyz.desent.presentation.ui.security.viewmodel.SecurityAlertsViewModel =
                viewModel(factory = securityFactory)
            xyz.desent.presentation.ui.security.SecurityAlertsScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable(Screen.Emails.Aliases.createRoute()) {
            val viewModel: AliasViewModel = viewModel(factory = factory)
            AliasScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToBilling = {
                    navController.navigate(Screen.Billing.createRoute())
                },
                viewModel = viewModel
            )
        }

        composable(
            route = Screen.Emails.Detail.route,
            arguments = listOf(navArgument("emailId") { type = NavType.StringType })
        ) { backStackEntry ->
            val emailId = backStackEntry.arguments?.getString("emailId") ?: return@composable

            val emailDetailFactory = EmailDetailViewModelFactory(emailId, appContainer)
            val viewModel: EmailDetailViewModel = viewModel(factory = emailDetailFactory)

            EmailDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToReply = { emailId ->
                    navController.navigate(Screen.Emails.Reply.createRoute(emailId))
                },
                onNavigateToReplyAll = { emailId ->
                    navController.navigate(Screen.Emails.Reply.createRoute(emailId, replyAll = true))
                },
                onAddToCalendar = { title, epochDay, description, location, startSec, endSec ->
                    navController.navigate(
                        Screen.Calendar.Editor.createPrefilledRoute(
                            title, epochDay, description, location, startSec, endSec
                        )
                    )
                },
                viewModel = viewModel
            )
        }

        composable(
            route = Screen.Emails.Reply.route,
            arguments = listOf(
                navArgument("emailId") { type = NavType.StringType },
                navArgument("draft") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("replyAll") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val emailId = backStackEntry.arguments?.getString("emailId") ?: return@composable
            // Quick-reply draft carried over from the thread bar. A non-blank
            // draft gets its own ViewModel instance so it isn't swallowed by
            // an existing reply VM for the same anchor.
            val draft = backStackEntry.arguments?.getString("draft").orEmpty()
            val replyAll = backStackEntry.arguments?.getBoolean("replyAll") == true
            val replyViewModel: xyz.desent.presentation.ui.email.viewmodel.EmailReplyViewModel =
                viewModel(
                    key = buildString {
                        append("reply-$emailId")
                        if (draft.isNotBlank()) append("-${draft.hashCode()}")
                        if (replyAll) append("-all")
                    }
                ) {
                    xyz.desent.presentation.ui.email.viewmodel.EmailReplyViewModel(
                        emailId,
                        appContainer.emailUseCase,
                        pgpKeyRepository = appContainer.pgpKeyManager,
                        pgpFeatureGate = appContainer.pgpFeatureGate,
                        autoEncryptFlow = appContainer.pgpAutoEncryptFlow(),
                        initialDraft = draft,
                        replyAll = replyAll
                    )
                }
            xyz.desent.presentation.ui.email.EmailReplyScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPgpSettings = {
                    navController.navigate(Screen.Settings.PgpKey.createRoute())
                },
                viewModel = replyViewModel
            )
        }

        composable(
            route = Screen.Emails.Thread.route,
            arguments = listOf(navArgument("threadKey") { type = NavType.StringType }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "desent://email?threadKey={threadKey}" }
            )
        ) { backStackEntry ->
            val threadKey = backStackEntry.arguments?.getString("threadKey") ?: return@composable
            val threadViewModel: xyz.desent.presentation.ui.email.viewmodel.EmailThreadViewModel =
                viewModel(key = "thread-$threadKey") {
                    xyz.desent.presentation.ui.email.viewmodel.EmailThreadViewModel(
                        threadKey,
                        appContainer.emailUseCase,
                        appContainer.preferencesManager,
                        appContainer.remoteImagePolicyStateFactory,
                        appContainer.contactProfileResolver,
                    appContainer.faviconResolver,
                    pgpKeyRepository = appContainer.pgpKeyManager,
                    pgpFeatureGate = appContainer.pgpFeatureGate,
                    autoEncryptFlow = appContainer.pgpAutoEncryptFlow(),
                    pgpMimeParser = appContainer.pgpMimeParser
                    )
                }
            xyz.desent.presentation.ui.email.EmailThreadScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPgpSettings = {
                    navController.navigate(Screen.Settings.PgpKey.createRoute())
                },
                onNavigateToReply = { anchorId, draft ->
                    navController.navigate(Screen.Emails.Reply.createRoute(anchorId, draft.ifBlank { null }))
                },
                viewModel = threadViewModel
            )
        }

        composable(
            route = Screen.Emails.Compose.routeWithArgs,
            arguments = listOf(
                navArgument("to") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val prefillTo = backStackEntry.arguments?.getString("to")?.takeIf { it.isNotBlank() }
            val composeViewModel: xyz.desent.presentation.ui.email.viewmodel.EmailComposeViewModel =
                viewModel {
                    xyz.desent.presentation.ui.email.viewmodel.EmailComposeViewModel(
                        appContainer.emailUseCase,
                        prefillRecipient = prefillTo,
                        pgpKeyRepository = appContainer.pgpKeyManager,
                        pgpFeatureGate = appContainer.pgpFeatureGate,
                        autoEncryptFlow = appContainer.pgpAutoEncryptFlow(),
                        privateStorageUseCase = appContainer.privateStorageUseCase,
                        contactProfileResolver = appContainer.contactProfileResolver
                    )
                }
            xyz.desent.presentation.ui.email.EmailComposeScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPgpSettings = {
                    navController.navigate(Screen.Settings.PgpKey.createRoute())
                },
                viewModel = composeViewModel
            )
        }

        composable(Screen.Settings.createRoute()) {
            SettingsScreen(
                onNavigateToAppearance = {
                    navController.navigate(Screen.Settings.Appearance.createRoute())
                },
                onNavigateToPrivacy = {
                    navController.navigate(Screen.Settings.Privacy.createRoute())
                },
                onNavigateToNotifications = {
                    navController.navigate(Screen.Settings.Notifications.createRoute())
                },
                onNavigateToMail = {
                    navController.navigate(Screen.Settings.Mail.createRoute())
                },
                onNavigateToAntiSpam = {
                    navController.navigate(Screen.Settings.AntiSpam.createRoute())
                },
                onNavigateToRollKey = {
                    navController.navigate(Screen.Settings.RollKey.createRoute())
                },
                onNavigateToBackup = {
                    navController.navigate(Screen.Backup.createRoute())
                },
                onNavigateToInvites = {
                    navController.navigate(Screen.Settings.Invites.createRoute())
                },
                onNavigateToAgents = {
                    navController.navigate(Screen.Settings.Agents.createRoute())
                },
                onNavigateToMarkdownFiles = {
                    navController.navigate(Screen.Settings.MarkdownFiles.createRoute())
                }
            )
        }

        // ---- Settings hub spokes ----

        composable(Screen.Settings.Appearance.createRoute()) {
            xyz.desent.presentation.ui.settings.AppearanceSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                preferencesManager = appContainer.preferencesManager
            )
        }

        composable(Screen.Settings.Privacy.createRoute()) {
            val viewModel: xyz.desent.presentation.ui.settings.viewmodel.SettingsViewModel =
                viewModel(factory = factory)
            xyz.desent.presentation.ui.settings.PrivacySettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel,
                securityConfigUseCase = appContainer.securityConfigUseCase,
                preferencesManager = appContainer.preferencesManager
            )
        }

        composable(Screen.Settings.Notifications.createRoute()) {
            xyz.desent.presentation.ui.settings.NotificationsSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                preferencesManager = appContainer.preferencesManager
            )
        }

        composable(Screen.Settings.Mail.createRoute()) {
            // Light FanoutViewModel instance for the Relay Mirroring toggle
            // card (tier-info + dm_fanout config only; the relay-list/health
            // machinery loads in the Fanout destination).
            val fanoutViewModel: xyz.desent.presentation.ui.fanout.viewmodel.FanoutViewModel =
                viewModel {
                    xyz.desent.presentation.ui.fanout.viewmodel.FanoutViewModel(
                        fanoutUseCase = appContainer.fanoutUseCase,
                        aliasUseCase = appContainer.aliasUseCase,
                        paymentsUseCase = appContainer.paymentsUseCase,
                        securityConfigRepository = appContainer.securityConfigRepository,
                        preferencesManager = appContainer.preferencesManager
                    )
                }
            xyz.desent.presentation.ui.settings.MailSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPgpSettings = {
                    navController.navigate(Screen.Settings.PgpKey.createRoute())
                },
                onNavigateToFanout = {
                    navController.navigate(Screen.Settings.Fanout.createRoute())
                },
                onNavigateToBilling = {
                    navController.navigate(Screen.Billing.createRoute())
                },
                mailboxConfigUseCase = appContainer.mailboxConfigUseCase,
                preferencesManager = appContainer.preferencesManager,
                fanoutViewModel = fanoutViewModel,
                pgpFeatureGate = appContainer.pgpFeatureGate,
                relaySyncWatermarks = appContainer.relaySyncWatermarks
            )
        }

        composable(Screen.Settings.AntiSpam.createRoute()) {
            xyz.desent.presentation.ui.settings.AntiSpamSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSpamPolicy = {
                    navController.navigate(Screen.Settings.SpamPolicy.createRoute())
                },
                onNavigateToImagePolicy = {
                    navController.navigate(Screen.Settings.ImagePolicy.createRoute())
                }
            )
        }

        composable(Screen.Settings.MarkdownFiles.createRoute()) {
            xyz.desent.presentation.ui.settings.MarkdownFilesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.Fanout.createRoute()) {
            val fanoutViewModel: xyz.desent.presentation.ui.fanout.viewmodel.FanoutViewModel =
                viewModel {
                    xyz.desent.presentation.ui.fanout.viewmodel.FanoutViewModel(
                        fanoutUseCase = appContainer.fanoutUseCase,
                        aliasUseCase = appContainer.aliasUseCase,
                        paymentsUseCase = appContainer.paymentsUseCase,
                        securityConfigRepository = appContainer.securityConfigRepository,
                        preferencesManager = appContainer.preferencesManager
                    )
                }
            xyz.desent.presentation.ui.fanout.FanoutScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToBilling = {
                    navController.navigate(Screen.Billing.createRoute())
                },
                viewModel = fanoutViewModel
            )
        }

        composable(
            route = Screen.Settings.PgpKey.createRoute(),
            deepLinks = listOf(navDeepLink { uriPattern = "desent://pgp" })
        ) {
            val pgpSettingsViewModel: xyz.desent.presentation.ui.settings.viewmodel.PgpSettingsViewModel =
                viewModel {
                    xyz.desent.presentation.ui.settings.viewmodel.PgpSettingsViewModel(
                        pgpKeyRepository = appContainer.pgpKeyManager,
                        pgpFeatureGate = appContainer.pgpFeatureGate,
                        securityConfigRepository = appContainer.securityConfigRepository,
                        preferencesManager = appContainer.preferencesManager,
                        registrationRepository = appContainer.registrationRepository
                    )
                }
            xyz.desent.presentation.ui.settings.PgpSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = pgpSettingsViewModel
            )
        }

        composable(
            route = Screen.Settings.RollKey.createRoute(),
            deepLinks = listOf(navDeepLink { uriPattern = "desent://roll-key" })
        ) {
            val rollKeyViewModel: xyz.desent.presentation.ui.settings.viewmodel.RollKeyViewModel =
                viewModel {
                    xyz.desent.presentation.ui.settings.viewmodel.RollKeyViewModel(
                        keyRotationUseCase = appContainer.keyRotationUseCase,
                        secureKeyManager = appContainer.secureKeyManager,
                        preferencesManager = appContainer.preferencesManager,
                        accountDao = appContainer.accountDao,
                        aliasUseCase = appContainer.aliasUseCase,
                        paymentsUseCase = appContainer.paymentsUseCase
                    )
                }
            xyz.desent.presentation.ui.settings.RollKeyScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToBilling = {
                    navController.navigate(Screen.Billing.createRoute())
                },
                onRotationFinished = {
                    // The account row / active key changed underneath every
                    // screen — reset the stack to Inbox so all ViewModels
                    // are recreated for the new npub (same pattern as logout
                    // with a switched account).
                    navController.navigate(Screen.Emails.Inbox.route) {
                        popUpTo(Screen.Emails.Inbox.route) { inclusive = true }
                    }
                },
                viewModel = rollKeyViewModel
            )
        }

        composable(
            route = Screen.Settings.SpamPolicy.createRoute(),
            deepLinks = listOf(navDeepLink { uriPattern = "desent://spam-policy" })
        ) {
            val viewModel: xyz.desent.presentation.ui.settings.viewmodel.SpamPolicyViewModel =
                viewModel(factory = factory)
            xyz.desent.presentation.ui.settings.SpamPolicyScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable(
            route = Screen.Settings.ImagePolicy.createRoute(),
            deepLinks = listOf(navDeepLink { uriPattern = "desent://image-policy" })
        ) {
            val viewModel: xyz.desent.presentation.ui.settings.viewmodel.ImagePolicyViewModel =
                viewModel(factory = factory)
            xyz.desent.presentation.ui.settings.ImagePolicyScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable(
            route = Screen.Settings.Invites.createRoute(),
            deepLinks = listOf(navDeepLink { uriPattern = "desent://invites" })
        ) {
            val viewModel: xyz.desent.presentation.ui.invites.viewmodel.InviteCodesViewModel =
                viewModel(factory = factory)
            xyz.desent.presentation.ui.invites.InviteCodesScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable(
            route = Screen.Settings.Agents.createRoute(),
            deepLinks = listOf(navDeepLink { uriPattern = "desent://agents" })
        ) {
            val viewModel: xyz.desent.presentation.ui.agents.viewmodel.AgentsViewModel =
                viewModel(factory = factory)
            xyz.desent.presentation.ui.agents.AgentsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToBilling = {
                    navController.navigate(Screen.Billing.createRoute())
                },
                viewModel = viewModel
            )
        }

        composable(
            route = Screen.Backup.routeWithArgs,
            arguments = listOf(
                navArgument("npub") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            // npub preselects just that account in the wizard (account
            // switcher drill-in); blank = the default select-all behavior.
            val npubArg = backStackEntry.arguments?.getString("npub")?.takeIf { it.isNotBlank() }
            // Destination-scoped VM keyed on the preselection so a prior
            // visit's selections don't bleed into a differently-scoped one.
            val viewModel: xyz.desent.presentation.ui.settings.backup.viewmodel.BackupViewModel =
                viewModel(key = "backup-${npubArg ?: "all"}") {
                    xyz.desent.presentation.ui.settings.backup.viewmodel.BackupViewModel(
                        exportBackupUseCase = appContainer.exportBackupUseCase,
                        accountRepository = appContainer.accountRepository,
                        context = appContainer.context,
                        secureKeyManager = appContainer.secureKeyManager,
                        preselectNpub = npubArg
                    )
                }
            xyz.desent.presentation.ui.settings.backup.BackupScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable(
            route = Screen.Restore.routeWithArgs,
            arguments = listOf(
                androidx.navigation.navArgument("activate") {
                    type = androidx.navigation.NavType.BoolType
                    defaultValue = false
                },
                androidx.navigation.navArgument("fileUri") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val activate = backStackEntry.arguments?.getBoolean("activate") ?: false
            val rawUri = backStackEntry.arguments?.getString("fileUri")?.takeIf { it.isNotBlank() }
            val viewModel: xyz.desent.presentation.ui.settings.backup.viewmodel.RestoreViewModel =
                viewModel(factory = factory)
            xyz.desent.presentation.ui.settings.backup.RestoreScreen(
                onNavigateBack = { navController.popBackStack() },
                activateFirst = activate,
                fileUriArg = rawUri,
                onRestoredAndActive = {
                    navController.navigate(Screen.Emails.Inbox.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                viewModel = viewModel
            )
        }

        composable(Screen.Files.createRoute()) {
            val viewModel: FilesViewModel = viewModel(factory = factory)
            FilesScreen(
                viewModel = viewModel
            )
        }

        composable(Screen.Storage.createRoute()) {
            val viewModel: xyz.desent.presentation.ui.storage.viewmodel.StorageViewModel =
                viewModel(factory = factory)
            xyz.desent.presentation.ui.storage.StorageScreen(
                onNavigateToBilling = {
                    navController.navigate(Screen.Billing.createRoute())
                },
                viewModel = viewModel
            )
        }

        composable(Screen.Billing.createRoute()) {
            val viewModel: xyz.desent.presentation.ui.billing.viewmodel.BillingViewModel =
                viewModel(factory = factory)
            xyz.desent.presentation.ui.billing.BillingScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable(Screen.Notes.List.createRoute()) {
            val viewModel: xyz.desent.presentation.ui.notes.viewmodel.NotesViewModel = viewModel(factory = factory)
            xyz.desent.presentation.ui.notes.NotesScreen(
                onNavigateToEditor = { noteId ->
                    navController.navigate(Screen.Notes.Editor.createRoute(noteId))
                },
                onOpenMarkdownFile = { fileUri ->
                    navController.navigate(Screen.MarkdownViewer.createRoute(fileUri))
                },
                viewModel = viewModel
            )
        }

        composable(
            route = Screen.Notes.Editor.routeWithArgs,
            arguments = listOf(
                navArgument("noteId") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("noteId")
            val noteId = raw?.takeIf { it.isNotBlank() }
            val editorViewModel: xyz.desent.presentation.ui.notes.viewmodel.NoteEditorViewModel =
                viewModel(key = "note-editor-${noteId ?: "new"}") {
                    xyz.desent.presentation.ui.notes.viewmodel.NoteEditorViewModel(
                        useCase = appContainer.privateStorageUseCase,
                        opener = appContainer.noteAttachmentOpener,
                        noteId = noteId,
                        savedState = backStackEntry.savedStateHandle,
                        draftStore = appContainer.noteDraftStore
                    )
                }
            xyz.desent.presentation.ui.notes.NoteEditorScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = editorViewModel
            )
        }

        composable(
            route = Screen.MarkdownViewer.routeWithArgs,
            arguments = listOf(
                navArgument("fileUri") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val fileUri = backStackEntry.arguments?.getString("fileUri").orEmpty()
            val viewerViewModel: xyz.desent.presentation.ui.markdown.viewmodel.MarkdownViewerViewModel =
                viewModel(key = "markdown-viewer-$fileUri") {
                    xyz.desent.presentation.ui.markdown.viewmodel.MarkdownViewerViewModel(
                        fileUri = fileUri,
                        io = appContainer.markdownFileIO,
                        useCase = appContainer.privateStorageUseCase,
                        savedState = backStackEntry.savedStateHandle
                    )
                }
            xyz.desent.presentation.ui.markdown.MarkdownViewerScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewerViewModel
            )
        }

        composable(
            route = Screen.Calendar.List.routeWithArgs,
            arguments = listOf(
                navArgument("date") {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            ),
            // Wear OS companion "Open on phone" handoff: the watch pushes the
            // occurrence's epoch day so the calendar tab scrolls to that day.
            deepLinks = listOf(
                navDeepLink { uriPattern = "desent://calendar?date={date}" }
            )
        ) { backStackEntry ->
            val dateArg = backStackEntry.arguments?.getLong("date") ?: -1L
            val initialDateEpochDay = dateArg.takeIf { it > 0 }
            val viewModel: xyz.desent.presentation.ui.calendar.viewmodel.CalendarViewModel =
                viewModel(factory = factory)
            xyz.desent.presentation.ui.calendar.CalendarScreen(
                onNavigateToEditor = { eventId ->
                    navController.navigate(Screen.Calendar.Editor.createRoute(eventId))
                },
                onNavigateToCalendars = {
                    navController.navigate(Screen.Calendar.Lists.createRoute())
                },
                onAddAnniversaryToCalendar = { title, epochDay, description ->
                    navController.navigate(
                        Screen.Calendar.Editor.createPrefilledRoute(title, epochDay, description)
                    )
                },
                initialDateEpochDay = initialDateEpochDay,
                viewModel = viewModel
            )
        }

        composable(Screen.Calendar.Lists.createRoute()) {
            val viewModel: xyz.desent.presentation.ui.calendar.viewmodel.CalendarListsViewModel =
                viewModel(factory = factory)
            xyz.desent.presentation.ui.calendar.CalendarsScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable(
            route = Screen.Calendar.Editor.routeWithArgs,
            arguments = listOf(
                navArgument("eventId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("date") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("desc") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("location") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("start") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
                navArgument("end") {
                    type = NavType.LongType
                    defaultValue = 0L
                }
            )
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("eventId")
            val eventId = raw?.takeIf { it.isNotBlank() }
            val prefillTitle = backStackEntry.arguments?.getString("title")?.takeIf { it.isNotBlank() }
            val prefillDateEpochDay = backStackEntry.arguments?.getLong("date")?.takeIf { it > 0 }
            val prefillDescription = backStackEntry.arguments?.getString("desc")?.takeIf { it.isNotBlank() }
            val prefillLocation = backStackEntry.arguments?.getString("location").orEmpty()
            val prefillStartSec = backStackEntry.arguments?.getLong("start") ?: 0L
            val prefillEndSec = backStackEntry.arguments?.getLong("end") ?: 0L
            val editorViewModel: xyz.desent.presentation.ui.calendar.viewmodel.CalendarEventEditorViewModel =
                viewModel(key = "cal-editor-${eventId ?: "new"}") {
                    xyz.desent.presentation.ui.calendar.viewmodel.CalendarEventEditorViewModel(
                        useCase = appContainer.calendarUseCase,
                        opener = appContainer.calendarAttachmentOpener,
                        locationResolver = appContainer.locationResolver,
                        eventId = eventId,
                        prefill = if (prefillTitle != null && prefillDateEpochDay != null) {
                            xyz.desent.presentation.ui.calendar.viewmodel.CalendarEventPrefill(
                                title = prefillTitle,
                                epochDay = prefillDateEpochDay,
                                description = prefillDescription.orEmpty(),
                                startSec = prefillStartSec,
                                endSec = prefillEndSec,
                                location = prefillLocation
                            )
                        } else {
                            null
                        }
                    )
                }
            xyz.desent.presentation.ui.calendar.CalendarEventEditorScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = editorViewModel
            )
        }

        composable(Screen.ContactsBook.createRoute()) {
            val viewModel: xyz.desent.presentation.ui.contacts.viewmodel.ContactsViewModel = viewModel(factory = factory)
            xyz.desent.presentation.ui.contacts.ContactsScreen(
                onComposeEmail = { recipient ->
                    navController.navigate(Screen.Emails.Compose.createRoute(recipient))
                },
                onAddAnniversaryToCalendar = { title, epochDay, description ->
                    navController.navigate(
                        Screen.Calendar.Editor.createPrefilledRoute(title, epochDay, description)
                    )
                },
                viewModel = viewModel
            )
        }

        composable(
            route = Screen.Nip46.RemoteSigning.routeWithArgs,
            arguments = listOf(
                navArgument("npub") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            // Scope the shared activity-level VM to the tapped account
            // (account switcher drill-in); blank = the active account. Set on
            // every entry so a previously-viewed account never lingers.
            val npubArg = backStackEntry.arguments?.getString("npub")
            LaunchedEffect(npubArg) {
                nip46ViewModel.setViewedAccount(npubArg?.takeIf { it.isNotBlank() })
            }
            xyz.desent.presentation.ui.nip46.RemoteSigningScreen(
                viewModel = nip46ViewModel,
                onScanQr = { navController.navigate(Screen.Nip46.Scan.createRoute()) },
                onShowBunkerCode = { navController.navigate(Screen.Nip46.BunkerCode.createRoute()) },
                onOpenPairing = { spk -> navController.navigate(Screen.Nip46.Detail.createRoute(spk)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Nip46.BunkerCode.createRoute()) {
            xyz.desent.presentation.ui.nip46.BunkerCodeScreen(
                viewModel = nip46ViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Nip46.Scan.createRoute()) {
            xyz.desent.presentation.ui.shared.QrScannerScreen(
                onScanned = { text ->
                    if (nip46ViewModel.onQrScanned(text)) {
                        navController.navigate(Screen.Nip46.Confirm.createRoute()) {
                            popUpTo(Screen.Nip46.Scan.createRoute()) { inclusive = true }
                        }
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(Screen.Nip46.Confirm.createRoute()) {
            xyz.desent.presentation.ui.nip46.PairConfirmScreen(
                viewModel = nip46ViewModel,
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.Nip46.Detail.route,
            arguments = listOf(navArgument("sessionPubkey") { type = NavType.StringType })
        ) { backStackEntry ->
            val spk = backStackEntry.arguments?.getString("sessionPubkey") ?: return@composable
            xyz.desent.presentation.ui.nip46.PairDetailScreen(
                sessionPubkey = spk,
                viewModel = nip46ViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
        // Global NIP-46 sign-prompt overlay (surfaces from any screen).
        xyz.desent.presentation.ui.nip46.Nip46SignPromptDialog(appContainer.nip46BunkerService)

        // Global in-app new-email banner overlay (surfaces from any screen).
        InAppNotificationContainer(
            notificationManager = inAppNotificationManager,
            onNotificationClick = { data ->
                when (data["type"]) {
                    "new_email" -> data["thread_key"]?.let {
                        navController.navigate(Screen.Emails.Thread.createRoute(it))
                    }
                }
            },
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
    }
}

/**
 * Reads the unread email count and the unseen security-alert count for the
 * active account, reactively. Computed here so the bottom nav badges stay
 * consistent across every main-level screen without each one having to plumb
 * the counts through.
 */
@Composable
@Suppress("ProduceStateDoesNotAssignValue") // assignments live in child coroutines
private fun rememberUnreadCounts(
    appContainer: AppContainer
): androidx.compose.runtime.State<Pair<Int, Int>> {
    val activeNpub by appContainer.sessionManager.activeNpub.collectAsState()
    val npub = activeNpub
    return produceState(initialValue = Pair(0, 0), npub) {
        if (npub == null) {
            value = Pair(0, 0)
            return@produceState
        }
        // Collect each unread-count Flow for as long as this composable is in
        // composition; Room re-emits on table changes.
        kotlinx.coroutines.coroutineScope {
            val email = launch {
                appContainer.emailUseCase.observeUnreadCount(npub).collect { c ->
                    value = value.copy(first = c)
                }
            }
            val security = launch {
                appContainer.securityAlertDao.observeUnseenCount(npub).collect { c ->
                    value = value.copy(second = c)
                }
            }
            try {
                awaitCancellation()
            } finally {
                email.cancel()
                security.cancel()
            }
        }
    }
}
