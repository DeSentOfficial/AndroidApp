package xyz.desent.presentation.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import xyz.desent.domain.model.RegistrationMode
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.CheckoutSheet
import xyz.desent.presentation.ui.login.components.CreateAccountFields
import xyz.desent.presentation.ui.login.components.CreateAccountSubmitBar
import xyz.desent.presentation.ui.login.components.LoginDialogs
import xyz.desent.presentation.ui.login.components.LoginTopBar
import xyz.desent.presentation.ui.login.components.RegistrationClosedCard
import xyz.desent.presentation.ui.login.viewmodel.LoginViewModel

/**
 * Account-creation form for the mode picked on
 * [CreateAccountChooserScreen]. Fields scroll; the submit CTA stays pinned
 * to the bottom. Mode `disabled` shows the registration-closed card instead
 * of any form.
 */
@Composable
fun CreateAccountFormScreen(
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    // Re-poll the registration mode whenever the signup form re-appears
    // (refs/FromServer/ANDROID_REFERRALS.md §2.1) and warm username ideas.
    LaunchedEffect(Unit) { viewModel.onCreateScreenShown() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        LoginTopBar(onBack = onBack, title = "Create your account")

        if (uiState.registrationMode == RegistrationMode.DISABLED) {
            RegistrationClosedCard(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
            ) {
                CreateAccountFields(viewModel = viewModel)

                Spacer(modifier = Modifier.height(Spacing.lg))
            }

            CreateAccountSubmitBar(
                viewModel = viewModel,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.lg)
            )
        }
    }

    // Lightning checkout for a priced short name (ANDROID_PAYMENTS.md §4):
    // poll while the sheet is visible, stop when it leaves composition.
    uiState.checkout?.let { checkout ->
        CheckoutSheet(
            state = checkout,
            onReMint = { viewModel.reMintInvoice() },
            onDismiss = { viewModel.dismissCheckout() }
        )
        DisposableEffect(checkout.invoice?.id) {
            viewModel.resumePolling()
            onDispose { viewModel.pausePolling() }
        }
    }

    LoginDialogs(viewModel = viewModel, onLoginSuccess = onLoginSuccess)
}
