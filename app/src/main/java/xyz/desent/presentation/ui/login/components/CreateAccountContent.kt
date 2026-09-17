package xyz.desent.presentation.ui.login.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import xyz.desent.domain.model.RegistrationMode
import xyz.desent.presentation.ui.components.AvatarPlaceholder
import xyz.desent.presentation.ui.components.AvatarShape
import xyz.desent.presentation.ui.components.DesentTextField
import xyz.desent.presentation.ui.components.formatSats
import xyz.desent.presentation.ui.login.viewmodel.CreateAccountMode
import xyz.desent.presentation.ui.login.viewmodel.LoginViewModel
import xyz.desent.presentation.ui.login.viewmodel.ReferralValidation
import xyz.desent.presentation.ui.login.viewmodel.UsernameAvailability
import xyz.desent.presentation.theme.Spacing

/**
 * Fields for the account-creation form in the mode chosen on the custody
 * chooser (username & password vs key only). Pure inputs: the submit controls
 * live in [CreateAccountSubmitBar], which the screen pins below the scroll
 * area. Registration-mode `disabled` still shows [RegistrationClosedCard].
 */
@Composable
fun CreateAccountFields(
    viewModel: LoginViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.onProfilePictureSelected(it, context)
        }
    }

    Column(modifier = modifier) {

        // ---- Invite gate (referral mode) / optional field (open mode) ----
        val showInviteField = when (uiState.registrationMode) {
            RegistrationMode.REFERRAL -> true
            RegistrationMode.OPEN -> uiState.inviteFieldVisible
            RegistrationMode.DISABLED -> false
            null -> uiState.inviteFieldVisible
        }

        if (uiState.registrationMode == RegistrationMode.OPEN && !uiState.inviteFieldVisible) {
            TextButton(onClick = { viewModel.onInviteFieldToggle() }) {
                Icon(
                    Icons.Default.CardGiftcard,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text("Have an invite code?")
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
        }

        if (showInviteField) {
            ReferralCodeField(
                viewModel = viewModel,
                isReferralMode = uiState.registrationMode == RegistrationMode.REFERRAL
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
        }

        DesentTextField(
            value = viewModel.displayName,
            onValueChange = { viewModel.onDisplayNameChange(it) },
            label = { Text("Display Name *") },
            isError = viewModel.displayNameError != null,
            enabled = !uiState.isLoading,
            singleLine = true,
            supportingText = viewModel.displayNameError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )

        when (uiState.createAccountMode) {
            CreateAccountMode.KEY_ONLY -> KeyOnlyFields(
                viewModel = viewModel,
                uiState = uiState,
                imagePickerLauncher = { imagePickerLauncher.launch("image/*") }
            )
            CreateAccountMode.USERNAME_PASSWORD -> UsernamePasswordField(
                viewModel = viewModel,
                uiState = uiState
            )
        }
    }
}

/**
 * Submit area for account creation: scoped error text, the 429 rate-limit
 * countdown, and the mode-dependent primary CTA (key-only generates a
 * keypair; username & password creates the custodial account).
 */
@Composable
fun CreateAccountSubmitBar(
    viewModel: LoginViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        // 429 countdown: disable submit until retry_after_seconds elapses.
        var rateLimitRemaining by remember(uiState.rateLimitRetryAt) {
            mutableIntStateOf(remainingSeconds(uiState.rateLimitRetryAt))
        }
        LaunchedEffect(uiState.rateLimitRetryAt) {
            val retryAt = uiState.rateLimitRetryAt ?: return@LaunchedEffect
            while (retryAt > System.currentTimeMillis()) {
                rateLimitRemaining = remainingSeconds(retryAt)
                delay(1000)
            }
            rateLimitRemaining = 0
        }

        FormErrorText(uiState.error)

        if (rateLimitRemaining > 0) {
            Text(
                text = "Too many attempts, try again in ${rateLimitRemaining}s",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
        }

        // Priced short name with Strike on: the submit opens lightning
        // checkout, not a plain register (WEB_FRONT.md §12.1.1 parity).
        val pricedForCheckout = viewModel.availabilityPriceSats > 0 && uiState.paymentsStrikeEnabled

        val (submitLabel, submitEnabled, submitAction) = when (uiState.createAccountMode) {
            CreateAccountMode.KEY_ONLY -> Triple(
                if (pricedForCheckout) {
                    "Pay ${formatSats(viewModel.availabilityPriceSats)} sats & Create Address"
                } else {
                    "Generate Keypair & Create"
                },
                viewModel.isCreateFormValid,
                { viewModel.onCreateAccount(context) }
            )
            CreateAccountMode.USERNAME_PASSWORD -> Triple(
                if (pricedForCheckout) {
                    "Pay ${formatSats(viewModel.availabilityPriceSats)} sats & Create Account"
                } else {
                    "Create Account"
                },
                viewModel.isCreateCustodialFormValid,
                { viewModel.onCreateCustodialAccount(context) }
            )
        }

        CtaButton(
            text = submitLabel,
            enabled = submitEnabled && !uiState.isLoading && rateLimitRemaining <= 0,
            isLoading = uiState.isLoading,
            onClick = submitAction
        )
    }
}

/** About + profile picture + address field for the classic key-only flow. */
@Composable
private fun KeyOnlyFields(
    viewModel: LoginViewModel,
    uiState: xyz.desent.presentation.ui.login.viewmodel.LoginUiState,
    imagePickerLauncher: () -> Unit
) {
    val context = LocalContext.current

    Spacer(modifier = Modifier.height(Spacing.md))

    DesentTextField(
        value = viewModel.about,
        onValueChange = { viewModel.onAboutChange(it) },
        label = { Text("About (optional)") },
        enabled = !uiState.isLoading,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(Spacing.md))

    Column {
        Text(
            text = "Profile Picture (optional)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (viewModel.selectedProfilePictureUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(viewModel.selectedProfilePictureUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Profile picture preview",
                    modifier = Modifier
                        .size(52.dp)
                        .clip(AvatarShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                AvatarPlaceholder(size = 52.dp)
            }

            OutlinedButton(
                onClick = imagePickerLauncher,
                enabled = !uiState.isLoading
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text("Choose Photo")
            }

            if (viewModel.selectedProfilePictureUri != null) {
                IconButton(
                    onClick = { viewModel.onClearProfilePicture() },
                    enabled = !uiState.isLoading
                ) {
                    Icon(Icons.Default.Clear, contentDescription = "Remove")
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(Spacing.sm))

    AddressField(
        viewModel = viewModel,
        isLoading = uiState.isLoading,
        minLengthHint = "1–32 chars: letters, digits, . _ -",
        strikeEnabled = uiState.paymentsStrikeEnabled
    )
}

/** Username + password + confirmation for the custodial flow. */
@Composable
private fun UsernamePasswordField(
    viewModel: LoginViewModel,
    uiState: xyz.desent.presentation.ui.login.viewmodel.LoginUiState
) {
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }

    Spacer(modifier = Modifier.height(Spacing.sm))

    // Suggested available names (GET /api/custodial/suggest), shown until
    // the user starts typing their own.
    if (uiState.suggestedUsernames.isNotEmpty() && viewModel.local.isBlank()) {
        Text(
            text = "Available names",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            uiState.suggestedUsernames.forEach { name ->
                FilterChip(
                    selected = false,
                    onClick = { viewModel.onPickSuggestedUsername(name) },
                    enabled = !uiState.isLoading,
                    label = { Text(name) }
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
    }

    AddressField(
        viewModel = viewModel,
        isLoading = uiState.isLoading,
        minLengthHint = "1–32 chars: letters, digits, . _ - — 8+ free, shorter is a one-time purchase",
        strikeEnabled = uiState.paymentsStrikeEnabled
    )

    Spacer(modifier = Modifier.height(Spacing.md))

    DesentTextField(
        value = viewModel.signupPassword,
        onValueChange = { viewModel.onSignupPasswordChange(it) },
        label = { Text("Password *") },
        enabled = !uiState.isLoading,
        singleLine = true,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = viewModel.signupPassword.isNotEmpty() &&
            viewModel.signupPassword.length < LoginViewModel.CUSTODIAL_PASSWORD_MIN,
        supportingText = {
            if (viewModel.signupPassword.isNotEmpty() &&
                viewModel.signupPassword.length < LoginViewModel.CUSTODIAL_PASSWORD_MIN
            ) {
                Text(
                    "At least ${LoginViewModel.CUSTODIAL_PASSWORD_MIN} characters",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (passwordVisible) "Hide" else "Show"
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(Spacing.sm))

    DesentTextField(
        value = viewModel.signupPasswordConfirm,
        onValueChange = { viewModel.onSignupPasswordConfirmChange(it) },
        label = { Text("Confirm Password *") },
        enabled = !uiState.isLoading,
        singleLine = true,
        visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = viewModel.signupPasswordConfirm.isNotEmpty() &&
            viewModel.signupPasswordConfirm != viewModel.signupPassword,
        supportingText = {
            if (viewModel.signupPasswordConfirm.isNotEmpty() &&
                viewModel.signupPasswordConfirm != viewModel.signupPassword
            ) {
                Text(
                    "Passwords do not match",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        trailingIcon = {
            IconButton(onClick = { confirmVisible = !confirmVisible }) {
                Icon(
                    imageVector = if (confirmVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (confirmVisible) "Hide" else "Show"
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(Spacing.xs))

    Text(
        text = "A keypair is generated on this device and backed up to the " +
            "server as an encrypted blob; the server never sees your keys. " +
            "If you ever forget the password and have no exported key, the " +
            "account is unrecoverable: there is no reset.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Shared address/username field with debounced availability feedback. */
@Composable
private fun AddressField(
    viewModel: LoginViewModel,
    isLoading: Boolean,
    minLengthHint: String,
    strikeEnabled: Boolean
) {
    DesentTextField(
        value = viewModel.local,
        onValueChange = { viewModel.onLocalChange(it) },
        label = { Text("Username *") },
        placeholder = { Text("yourname") },
        singleLine = true,
        enabled = !isLoading,
        isError = viewModel.availability == UsernameAvailability.Taken ||
            viewModel.availability == UsernameAvailability.Invalid,
        suffix = { Text("@desent.xyz") },
        // Supporting text only for live states — the suffix + label already
        // carry the idle context.
        supportingText = {
            when (viewModel.availability) {
                UsernameAvailability.Checking -> Text("Checking availability…")
                UsernameAvailability.Available -> {
                    val price = viewModel.availabilityPriceSats
                    if (price > 0) {
                        Text(
                            if (strikeEnabled) {
                                "Available · ${formatSats(price)} sats one-time (paid in-app via lightning)"
                            } else {
                                "Available · ${formatSats(price)} sats one-time (needs operator approval)"
                            },
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            "Available",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                UsernameAvailability.Taken -> Text(
                    "Already taken",
                    color = MaterialTheme.colorScheme.error
                )
                UsernameAvailability.Invalid -> Text(
                    minLengthHint,
                    color = MaterialTheme.colorScheme.error
                )
                UsernameAvailability.Idle -> {}
            }
        },
        trailingIcon = {
            if (viewModel.availability == UsernameAvailability.Checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            } else if (viewModel.availability == UsernameAvailability.Available) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Single uppercase-forced monospace field for the invite code, with debounced
 * live validation feedback (refs/FromServer/ANDROID_REFERRALS.md §4).
 */
@Composable
private fun ReferralCodeField(viewModel: LoginViewModel, isReferralMode: Boolean) {
    val validation = viewModel.referralValidation

    DesentTextField(
        value = viewModel.referralCode,
        onValueChange = { viewModel.onReferralCodeChange(it) },
        label = { Text(if (isReferralMode) "Invite Code *" else "Invite code (optional)") },
        placeholder = { Text("DS-XXXXXX-XXXXXX", fontFamily = FontFamily.Monospace) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        isError = validation == ReferralValidation.Invalid,
        // The monospace placeholder carries the format; supporting text only
        // reports live validation.
        supportingText = {
            when (validation) {
                ReferralValidation.Checking -> Text("Checking code…")
                ReferralValidation.Valid -> Text(
                    "Valid invite code",
                    color = MaterialTheme.colorScheme.primary
                )
                ReferralValidation.Invalid -> Text(
                    "Invalid or already used",
                    color = MaterialTheme.colorScheme.error
                )
                ReferralValidation.Idle -> {}
            }
        },
        trailingIcon = {
            when (validation) {
                ReferralValidation.Checking -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                ReferralValidation.Valid -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                ReferralValidation.Invalid -> Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                ReferralValidation.Idle -> {}
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/** Mode `disabled`: registration closed, no form reachable. */
@Composable
fun RegistrationClosedCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Registration is closed",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "DeSent is invite-only right now. Check back later or ask an existing member for an invite code.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
