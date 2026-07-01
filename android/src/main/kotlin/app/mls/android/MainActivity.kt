package app.mls.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import app.mls.android.ui.MlsApp
import app.mls.android.ui.VaultViewModel
import app.mls.android.ui.theme.MlsTheme

/**
 * Single-activity Compose host. Extends [FragmentActivity] because BiometricPrompt requires it.
 *
 * Security posture:
 *  - {@code FLAG_SECURE} blocks screenshots and hides the app's content in the recents/overview
 *    thumbnail and from screen recording.
 *  - The vault locks (wiping the in-memory account key) whenever the app is backgrounded ([onStop]).
 */
class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels {
        VaultViewModel.factory(applicationContext, getString(R.string.server_url), cloudflareAccessHeaders())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            MlsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MlsApp(viewModel, this)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-lock when the app leaves the foreground; a fresh unlock is required to return.
        viewModel.lock()
    }

    private fun cloudflareAccessHeaders(): Map<String, String> {
        val clientId = BuildConfig.CF_ACCESS_CLIENT_ID
        val clientSecret = BuildConfig.CF_ACCESS_CLIENT_SECRET
        return if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
            mapOf(
                "CF-Access-Client-Id" to clientId,
                "CF-Access-Client-Secret" to clientSecret,
            )
        } else {
            emptyMap()
        }
    }
}
