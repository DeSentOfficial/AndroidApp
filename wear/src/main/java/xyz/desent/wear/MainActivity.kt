package xyz.desent.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import xyz.desent.wear.ui.DeSentWearApp

class MainActivity : ComponentActivity() {

    // ComponentActivity host: the fragment-version constraint of this lint
    // rule does not apply here.
    @android.annotation.SuppressLint("InvalidFragmentVersionForActivityResult")
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or not — notify() no-ops safely */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // New-mail notifications need the runtime grant on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            DeSentWearApp(
                appContainer = DeSentWearApplication.appContainer,
                initialDestination = intent.getStringExtra(WearAppContainer.EXTRA_NAVIGATE)
            )
        }
    }
}
