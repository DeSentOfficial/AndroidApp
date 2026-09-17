package xyz.desent.presentation.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.login.components.LoginDialogs
import xyz.desent.presentation.ui.login.components.LoginMethodFields
import xyz.desent.presentation.ui.login.components.LoginMethodSubmitBar
import xyz.desent.presentation.ui.login.components.LoginTopBar
import xyz.desent.presentation.ui.login.components.SelectableOptionCard
import xyz.desent.presentation.ui.login.viewmodel.ExistingLoginMode
import xyz.desent.presentation.ui.login.viewmodel.LoginViewModel

/**
 * Existing-account login: method cards (username & password vs nostr key)
 * over the matching form, with the submit area pinned to the bottom next to
 * the backup-restore entry point. Replaces the old "Login to Existing
 * Account" accordion section.
 */
@Composable
fun LoginMethodScreen(
    onBack: () -> Unit,
    onNavigateToQrScanner: () -> Unit,
    onNavigateToRestore: () -> Unit,
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isLoginSuccess) {
        if (uiState.isLoginSuccess) {
            onLoginSuccess()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        LoginTopBar(onBack = onBack, title = "Log in to DeSent")

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = Spacing.xs)
            ) {
                SelectableOptionCard(
                    title = "Username & password",
                    description = "Use your DeSent name and password.",
                    icon = Icons.Default.Person,
                    selected = uiState.existingLoginMode == ExistingLoginMode.PASSWORD,
                    enabled = !uiState.isLoading,
                    onClick = { viewModel.onExistingLoginModeChange(ExistingLoginMode.PASSWORD) },
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(12.dp))

                SelectableOptionCard(
                    title = "Nostr key",
                    description = "Paste or scan your nsec or ncryptsec key.",
                    icon = Icons.Default.Key,
                    selected = uiState.existingLoginMode == ExistingLoginMode.KEY,
                    enabled = !uiState.isLoading,
                    onClick = { viewModel.onExistingLoginModeChange(ExistingLoginMode.KEY) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            LoginMethodFields(
                viewModel = viewModel,
                onScanQr = onNavigateToQrScanner,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(Spacing.lg))
        }

        LoginMethodSubmitBar(
            viewModel = viewModel,
            onRestore = onNavigateToRestore,
            modifier = Modifier
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg)
        )
    }

    LoginDialogs(viewModel = viewModel, onLoginSuccess = onLoginSuccess)
}
