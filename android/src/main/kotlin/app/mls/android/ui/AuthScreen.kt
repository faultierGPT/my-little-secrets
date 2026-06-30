package app.mls.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Sign-in / create-account screen. The password is read from the field as a {@code CharArray} and
 * handed straight to the view model, which wipes it after use.
 */
@Composable
fun AuthScreen(
    state: UiState,
    onRegister: (String, CharArray) -> Unit,
    onLogin: (String, CharArray) -> Unit,
    onUnlockOffline: (CharArray) -> Unit,
    onBiometric: () -> Unit,
) {
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(state.rememberedEmail) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text("my-little-secrets", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "End-to-end encrypted notes. Only you hold the key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Master password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (register) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                label = { Text("Confirm password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val shownError = localError ?: state.error
        if (shownError != null) {
            Spacer(Modifier.height(8.dp))
            Text(shownError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                localError = validate(register, email, password, confirm)
                if (localError != null) return@Button
                if (register) onRegister(email.trim(), password.toCharArray())
                else onLogin(email.trim(), password.toCharArray())
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp))
            } else {
                Text(if (register) "Create account" else "Sign in")
            }
        }

        if (!register && state.biometricConfigured && state.biometricAvailable) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBiometric, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Unlock with biometrics")
            }
        }
        if (!register && state.hasLocalAccount) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (password.isEmpty()) localError = "Enter your master password to unlock offline."
                    else onUnlockOffline(password.toCharArray())
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Unlock offline")
            }
        }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { register = !register; localError = null }) {
            Text(if (register) "Have an account? Sign in" else "Create a new account")
        }
    }
}

private fun validate(register: Boolean, email: String, password: String, confirm: String): String? = when {
    !email.contains("@") || email.length < 3 -> "Enter a valid email address."
    password.isEmpty() -> "Enter your master password."
    register && password != confirm -> "Passwords don't match."
    register && password.length < 8 -> "Use at least 8 characters."
    else -> null
}
