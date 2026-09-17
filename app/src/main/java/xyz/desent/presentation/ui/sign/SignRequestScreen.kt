package xyz.desent.presentation.ui.sign

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.desent.R
import xyz.desent.di.AppContainer
import xyz.desent.domain.model.SignRequest
import xyz.desent.domain.model.SignedEventResponse
import xyz.desent.presentation.composition.LocalBiometricAuthManager
import xyz.desent.presentation.ui.sign.viewmodel.SignRequestViewModel
import xyz.desent.presentation.ui.sign.viewmodel.ValidationResult
import xyz.desent.presentation.theme.Spacing
import android.content.Context
import android.widget.Toast

@Composable
fun SignRequestScreen(
    signRequest: SignRequest?,
    onSignComplete: () -> Unit,
    viewModel: SignRequestViewModel = viewModel()
) {
    var showResult by remember { mutableStateOf<SignedEventResponse?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var validationResult by remember { mutableStateOf<ValidationResult?>(null) }

    val context = LocalContext.current
    val appContainer = remember { AppContainer.getInstance(context) }
    val biometricManager = LocalBiometricAuthManager.current
    val coroutineScope = rememberCoroutineScope()

    val doSign: (SignRequest) -> Unit = { request ->
        isProcessing = true
        viewModel.signEvent(request) { response ->
            isProcessing = false
            showResult = response
        }
    }

    // If biometric-on-signing is enabled, prompt before performing the sign.
    val promptBiometricThenSign: (SignRequest) -> Unit = { request ->
        coroutineScope.launch {
            val requireBiometric = appContainer.preferencesManager
                .isRequireBiometricOnSigningEnabled.first()
            if (!requireBiometric) {
                doSign(request)
                return@launch
            }
            val activity = context as? FragmentActivity
            if (activity == null || !biometricManager.isBiometricAvailable()) {
                // Gate enabled but biometric unavailable -> fall back to direct
                // sign (mirrors the open-gate fallback policy). The user still
                // explicitly tapped Approve.
                Toast.makeText(context, "Biometric unavailable", Toast.LENGTH_SHORT).show()
                doSign(request)
                return@launch
            }
            biometricManager.authenticate(
                activity = activity,
                onSuccess = { coroutineScope.launch { doSign(request) } },
                onError = { Toast.makeText(context, "Authentication required to sign", Toast.LENGTH_SHORT).show() }
            )
        }
    }
    
    LaunchedEffect(signRequest) {
        signRequest?.let {
            validationResult = viewModel.validateRequest(it)
        }
    }
    
    if (signRequest == null) {
        EmptySignRequest()
        return
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(Spacing.md)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = stringResource(R.string.sign_request_title),
            style = MaterialTheme.typography.headlineMedium
        )
        
        AlertCard(
            message = stringResource(R.string.sign_request_message),
            type = AlertType.INFO
        )
        
        when (val result = validationResult) {
            is ValidationResult.RateLimited -> {
                AlertCard(
                    message = "Too many requests. Please wait before trying again.",
                    type = AlertType.ERROR
                )
            }
            is ValidationResult.InvalidCallback -> {
                AlertCard(
                    message = "Invalid callback scheme",
                    type = AlertType.ERROR
                )
            }
            is ValidationResult.Valid -> {
                EventDetailsCard(signRequest)
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    OutlinedButton(
                        onClick = onSignComplete,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.sign_deny))
                    }
                    
                    Button(
                        onClick = {
                            isProcessing = true
                            promptBiometricThenSign(signRequest)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isProcessing
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.sign_approve))
                        }
                    }
                }
            }
            null -> {}
        }
        
        showResult?.let { result ->
            LaunchedEffect(result) {
                Toast.makeText(
                    /* context = */ null,
                    "Event signed successfully",
                    Toast.LENGTH_SHORT
                ).show()
                onSignComplete()
            }
        }
    }
}

@Composable
fun EmptySignRequest() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No sign request",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun EventDetailsCard(request: SignRequest) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            DetailRow("Request from", request.requestingApp)
            DetailRow("Event type", "Kind ${request.unsignedEvent.kind}")
            DetailRow("Content", request.unsignedEvent.content.take(100))
            
            if (request.unsignedEvent.tags.isNotEmpty()) {
                Text(
                    text = "Tags: ${request.unsignedEvent.tags.size} tag(s)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value ?: "N/A",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun AlertCard(
    message: String,
    type: AlertType
) {
    val backgroundColor = when (type) {
        AlertType.INFO -> MaterialTheme.colorScheme.primaryContainer
        AlertType.WARNING -> MaterialTheme.colorScheme.secondaryContainer
        AlertType.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    
    val contentColor = when (type) {
        AlertType.INFO -> MaterialTheme.colorScheme.onPrimaryContainer
        AlertType.WARNING -> MaterialTheme.colorScheme.onSecondaryContainer
        AlertType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor,
            contentColor = contentColor
        )
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(Spacing.md),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

enum class AlertType {
    INFO,
    WARNING,
    ERROR
}
