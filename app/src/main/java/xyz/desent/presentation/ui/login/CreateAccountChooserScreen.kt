package xyz.desent.presentation.ui.login

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.RegistrationMode
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.login.components.CtaButton
import xyz.desent.presentation.ui.login.components.LoginTopBar
import xyz.desent.presentation.ui.login.components.RegistrationClosedCard
import xyz.desent.presentation.ui.login.components.SelectableOptionCard
import xyz.desent.presentation.ui.login.viewmodel.CreateAccountMode
import xyz.desent.presentation.ui.login.viewmodel.LoginViewModel

/**
 * Blink-style custody chooser: the user picks how the account is created
 * before reaching the form. Custodial maps to
 * [CreateAccountMode.USERNAME_PASSWORD], self-custodial to
 * [CreateAccountMode.KEY_ONLY]; "Continue" stays disabled until a card is
 * picked, then commits the mode to the shared [LoginViewModel].
 */
@Composable
fun CreateAccountChooserScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    viewModel: LoginViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    var selected by remember { mutableStateOf<CreateAccountMode?>(null) }

    // Re-poll the registration mode whenever the signup flow re-appears
    // (refs/FromServer/ANDROID_REFERRALS.md §2.1) and warm username ideas.
    LaunchedEffect(Unit) { viewModel.onCreateScreenShown() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        LoginTopBar(onBack = onBack)

        if (uiState.registrationMode == RegistrationMode.DISABLED) {
            RegistrationClosedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            )
        } else {
            Text(
                text = "Please choose your preferred type of DeSent account.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(horizontal = Spacing.lg)
            ) {
                SelectableOptionCard(
                    title = "Custodial",
                    description = "Pick a username & password. We hold an " +
                        "encrypted copy of your key for you.",
                    icon = Icons.Default.Cloud,
                    selected = selected == CreateAccountMode.USERNAME_PASSWORD,
                    enabled = !uiState.isLoading,
                    onClick = { selected = CreateAccountMode.USERNAME_PASSWORD },
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(12.dp))

                SelectableOptionCard(
                    title = "Self-custodial",
                    description = "Keys are generated on your device and " +
                        "backed up to the server as encrypted blobs. The " +
                        "server never sees them.",
                    icon = Icons.Default.Key,
                    selected = selected == CreateAccountMode.KEY_ONLY,
                    enabled = !uiState.isLoading,
                    onClick = { selected = CreateAccountMode.KEY_ONLY },
                    modifier = Modifier.weight(1f)
                )
            }

            Box(modifier = Modifier.weight(1f))

            CtaButton(
                text = "Continue",
                enabled = selected != null && !uiState.isLoading,
                onClick = {
                    viewModel.onCreateAccountModeChange(selected!!)
                    onContinue()
                },
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl)
            )
        }
    }
}
