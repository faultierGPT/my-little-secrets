package app.mls.android.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.fragment.app.FragmentActivity

/**
 * Top-level navigation: shows the workspace when unlocked, otherwise the sign-in screen. Surfaces the
 * one-time recovery code after registration as a blocking dialog.
 */
@Composable
fun MlsApp(viewModel: VaultViewModel, activity: FragmentActivity) {
    val state by viewModel.state.collectAsState()

    state.recoveryCode?.let { code ->
        RecoveryDialog(code) { viewModel.acknowledgeRecovery() }
    }

    if (state.unlocked) {
        val canEnableBiometric = state.biometricAvailable && !state.biometricConfigured && state.online
        VaultScreen(
            state = state,
            onSave = viewModel::saveNote,
            onDelete = viewModel::deleteNote,
            onSync = viewModel::sync,
            onLock = viewModel::lock,
            onEnableBiometric = if (canEnableBiometric) ({ viewModel.enableBiometric(activity) }) else null,
        )
    } else {
        AuthScreen(
            state = state,
            onRegister = viewModel::register,
            onLogin = viewModel::login,
            onUnlockOffline = viewModel::unlockOffline,
            onBiometric = { viewModel.biometricUnlock(activity) },
        )
    }
}

@Composable
private fun RecoveryDialog(code: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* must be acknowledged */ },
        title = { Text("Save your recovery code", style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                "This is the ONLY way to recover your notes if you forget your password. We can't reset " +
                    "it for you. Store it somewhere safe:\n\n$code",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("I've saved it") }
        },
    )
}
