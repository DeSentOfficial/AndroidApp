package xyz.desent

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import xyz.desent.data.local.preferences.ThemeMode
import xyz.desent.presentation.composition.LocalBiometricAuthManager
import xyz.desent.presentation.deeplink.DeepLinkHandler
import xyz.desent.presentation.deeplink.DeSentUri
import xyz.desent.presentation.deeplink.MarkdownFileIntents
import xyz.desent.presentation.deeplink.NostrUri
import xyz.desent.presentation.lock.AppLockGate
import xyz.desent.presentation.lock.LockScreen
import xyz.desent.presentation.navigation.NostrNavigation
import xyz.desent.presentation.navigation.Screen
import xyz.desent.presentation.theme.DeSentTheme
import xyz.desent.widget.WidgetNavContract

class MainActivity : AppCompatActivity() {
    
    private val TAG = "MainActivity"

    /**
     * A navigation target pushed by a home-screen widget tap (open note / new
     * note / calendar). Consumed inside [NostrNavigation] once the user reaches
     * the Main route, so cold launches wait for Splash→Main (or Splash→Login→Main)
     * instead of racing the splash gate.
     */
    private val pendingWidgetRoute = mutableStateOf<String?>(null)

    /**
     * The composition-owned NavController (the NavHost stays mounted for the
     * activity's whole lifetime), so [onNewIntent] can navigate directly on
     * warm-start view intents instead of waiting for the Main route.
     */
    private var navControllerRef: androidx.navigation.NavController? = null

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "POST_NOTIFICATIONS granted=$granted")
        }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the core SplashScreen (Android 12+) first so the starting
        // window hands off cleanly to Compose. Must run before super.onCreate.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Opt into edge-to-edge explicitly. Targeting API 36 forces it on
        // Android 15+ anyway; doing it here keeps every OS version on one
        // layout path (the root Scaffold supplies the system-bar insets) and
        // lets us own the system-bar icon appearance below.
        enableEdgeToEdge()

        maybeRequestNotificationPermission()
        
        val appContainer = DeSentApplication.appContainer

        // Blank the recents preview and block screenshots while a lock gate is
        // active. The NavHost now stays mounted behind the lock overlay, so this
        // prevents the underlying content from leaking into the task thumbnail.
        lifecycleScope.launch {
            appContainer.appLockController.gateMode.collect { gate ->
                if (gate != AppLockGate.NONE) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        
        setContent {
            val themeMode by appContainer.preferencesManager.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val gateMode by appContainer.appLockController.gateMode.collectAsState()
            val isLocked by appContainer.appLockController.isLocked.collectAsState()

            // Sync XML resource night-qualifiers (logo colors, widget background)
            // to the app's ThemeMode. Without this, values-night/ only tracks the
            // system setting and ignores a manual LIGHT/DARK override.
            SideEffect {
                AppCompatDelegate.setDefaultNightMode(
                    when (themeMode) {
                        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
                // Transparent system bars in edge-to-edge: pick icon contrast
                // from the effective theme, not the bare system default, or a
                // LIGHT theme under a DARK system setting gets white-on-white
                // status bar icons.
                val lightTheme = when (themeMode) {
                    ThemeMode.LIGHT -> true
                    ThemeMode.DARK -> false
                    ThemeMode.SYSTEM ->
                        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
                            Configuration.UI_MODE_NIGHT_YES
                }
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightTheme
                    isAppearanceLightNavigationBars = lightTheme
                }
            }

            DeSentTheme(themeMode = themeMode) {
                CompositionLocalProvider(
                    LocalBiometricAuthManager provides appContainer.biometricAuthManager
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        // The NavHost stays mounted at all times; the LockScreen is
                        // rendered on top when locked. This preserves the
                        // NavController, its back stack, and every nav-scoped
                        // ViewModel (e.g. an in-progress note draft) across
                        // background → resume → re-auth cycles.
                        Box(modifier = Modifier.fillMaxSize()) {
                            val navController = rememberNavController()
                            navControllerRef = navController
                            NostrNavigation(navController, appContainer, pendingWidgetRoute) {
                                pendingWidgetRoute.value = null
                            }

                            // Fire once per Activity instance (also avoids re-handling
                            // the deep link on every recomposition/lock toggle).
                            LaunchedEffect(Unit) {
                                if (savedInstanceState == null) {
                                    handleDeepLink(intent, navController)
                                    handleShareIntent(intent, coldStart = true)
                                    handleWidgetIntent(intent)
                                }
                            }

                            if (gateMode != AppLockGate.NONE && isLocked) {
                                LockScreen(
                                    gateMode = gateMode,
                                    biometricManager = appContainer.biometricAuthManager,
                                    preferencesManager = appContainer.preferencesManager,
                                    onUnlocked = { appContainer.appLockController.unlock() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun handleDeepLink(intent: Intent?, navController: androidx.navigation.NavController) {
        val uri = intent?.data ?: return

        // Encrypted backup file opened via the .desentbackup file association →
        // go straight to the Restore flow (activate-first, since this is the
        // post-reinstall recovery path). popUpTo(0) clears Splash/Login so the
        // splash gate can't race this navigation.
        if (uri.toString().endsWith(".desentbackup", ignoreCase = true)) {
            Log.d(TAG, "Opening backup file: $uri")
            navController.navigate(
                Screen.Restore.createRoute(activate = true, fileUri = uri.toString())
            ) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
            return
        }

        // Markdown document opened via the file association → deferred through
        // the pending-route mechanism so it lands after Splash/Login complete
        // (and after the lock overlay, which gates whatever is underneath).
        if (intent.action == Intent.ACTION_VIEW &&
            MarkdownFileIntents.isMarkdownDocument(uri.toString(), intent.type)
        ) {
            Log.d(TAG, "Opening markdown file: $uri")
            pendingWidgetRoute.value = Screen.MarkdownViewer.createRoute(uri.toString())
            return
        }

        Log.d(TAG, "Handling deep link: $uri")
        
        try {
            val (nostrUri, desentUri) = DeepLinkHandler.parseUri(uri)
            
            when (nostrUri) {
                is NostrUri.Profile -> {
                    Log.d(TAG, "Opening profile: ${nostrUri.npub}")
                }
                is NostrUri.Event -> {
                    Log.d(TAG, "Opening event: ${nostrUri.noteId}")
                }
                is NostrUri.Address -> {
                    Log.d(TAG, "Opening address: ${nostrUri.identifier}")
                }
                null -> {}
            }
            
            when (desentUri) {
                is DeSentUri.Profile -> {
                    Log.d(TAG, "Opening profile screen")
                }
                is DeSentUri.Settings -> {
                    Log.d(TAG, "Opening settings screen")
                }
                is DeSentUri.Sign -> {
                    Log.d(TAG, "Opening sign request from: ${desentUri.callback}")
                }
                null -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle deep link: $uri", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
        handleMarkdownViewIntent(intent)
        handleShareIntent(intent, coldStart = false)
    }

    /**
     * Share-to-upload target (END-23 user files): files shared from any app
     * are stashed in [xyz.desent.presentation.ui.files.PendingUploads] and the
     * Files screen drains them into the encrypted upload flow. Cold starts
     * defer navigation through the pending-route mechanism (like markdown
     * files) so Splash/Login complete first; warm starts navigate directly.
     */
    private fun handleShareIntent(intent: Intent?, coldStart: Boolean) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = if (intent.action == Intent.ACTION_SEND) {
            listOfNotNull(@Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
        if (uris.isEmpty()) return
        Log.d(TAG, "Share target: uploading ${uris.size} file(s) via Files screen")
        DeSentApplication.appContainer.pendingUploads.set(uris)
        if (coldStart) {
            pendingWidgetRoute.value = Screen.Files.route
        } else {
            navControllerRef?.navigate(Screen.Files.route) { launchSingleTop = true }
        }
    }

    /**
     * Warm start: a markdown ACTION_VIEW arrived while the activity was
     * already running (user tapped a .md file from a file manager). Navigate
     * straight to the viewer when the nav graph is past Splash/Login;
     * otherwise defer via the pending-route mechanism so the gate flows
     * finish first.
     */
    private fun handleMarkdownViewIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        if (!MarkdownFileIntents.isMarkdownDocument(uri.toString(), intent.type)) return

        val route = Screen.MarkdownViewer.createRoute(uri.toString())
        val nav = navControllerRef
        val current = nav?.currentDestination?.route
        if (nav != null && current != null &&
            current != Screen.Splash.route && !current.startsWith("login")
        ) {
            nav.navigate(route) { launchSingleTop = true }
        } else {
            pendingWidgetRoute.value = route
        }
    }

    /**
     * Translate a widget tap (note / new-note / calendar) into a pending
     * navigation route. The actual navigate() is deferred until the user is on
     * the Main route — see [NostrNavigation]'s consumer. This keeps cold starts
     * (widget tap on a killed process) correct: Splash runs, then once Main is
     * reached the pending route fires. Returns without doing anything if the
     * intent carries no widget extras.
     */
    private fun handleWidgetIntent(intent: Intent?) {
        intent ?: return
        val hasWidgetExtra =
            intent.hasExtra(WidgetNavContract.EXTRA_NOTE_ID) ||
                intent.hasExtra(WidgetNavContract.EXTRA_NEW_NOTE) ||
                intent.hasExtra(WidgetNavContract.EXTRA_EVENT_ID) ||
                intent.hasExtra(WidgetNavContract.EXTRA_DATE_EPOCH_DAY)
        if (!hasWidgetExtra) return

        val noteId = intent.getStringExtra(WidgetNavContract.EXTRA_NOTE_ID)
        val newNote = intent.getBooleanExtra(WidgetNavContract.EXTRA_NEW_NOTE, false)
        val eventId = intent.getStringExtra(WidgetNavContract.EXTRA_EVENT_ID)
        val dateEpochDay = intent.getLongExtra(WidgetNavContract.EXTRA_DATE_EPOCH_DAY, -1L)
            .takeIf { it >= 0 }

        pendingWidgetRoute.value = when {
            newNote -> Screen.Notes.Editor.createRoute(null)
            noteId != null -> Screen.Notes.Editor.createRoute(noteId)
            eventId != null -> Screen.Calendar.Editor.createRoute(eventId)
            dateEpochDay != null -> Screen.Calendar.List.createRoute(dateEpochDay)
            else -> null
        }
        Log.d(TAG, "Widget intent queued: $pendingWidgetRoute")
    }
    
    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    override fun onResume() {
        super.onResume()
        appContainer.appLockController.onForegrounded()
        // Re-establish relay sockets when returning to the foreground. Idempotent
        // (connectToRelay skips already-connected relays); recovers a socket that
        // was dropped/killed while backgrounded so subscriptions/requests resume.
        ioScope.launch {
            runCatching { appContainer.relayRepository.connectToPersistentRelays() }
                .onFailure { android.util.Log.w(TAG, "onResume relay reconnect failed: ${it.message}") }
            // Re-send the gift-wrap subscription on foregrounding too — idempotent
            // (stable sub IDs) and self-heals the case where the socket reconnected
            // but the relay dropped the subscription server-side while backgrounded.
            runCatching { appContainer.nostrRepository.subscribeToGiftWraps() }
                .onFailure { android.util.Log.w(TAG, "onResume gift-wrap re-subscribe failed: ${it.message}") }
        }
    }

    override fun onPause() {
        super.onPause()
        appContainer.appLockController.onBackgrounded()
    }

    /**
     * Request POST_NOTIFICATIONS on Android 13+ if not already granted. The
     * system-tray dispatcher (email / follower notifications) is inert
     * without it.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
    }
}

val AppCompatActivity.appContainer
    get() = DeSentApplication.appContainer
