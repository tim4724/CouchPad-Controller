package games.couchpad.controller.ui.components

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import games.couchpad.controller.R

/**
 * Deny a hardened top-level WebView any local-file access — belt-and-suspenders for
 * the remote content it hosts (the game controller, the legal pages). Kept in one
 * place so both hosts stay locked down identically.
 */
fun WebView.denyLocalFileAccess() {
  settings.allowFileAccess = false
  settings.allowContentAccess = false
  @Suppress("DEPRECATION")
  settings.allowFileAccessFromFileURLs = false
  @Suppress("DEPRECATION")
  settings.allowUniversalAccessFromFileURLs = false
}

/**
 * Opaque "couldn't reach the server / try again" cover shown over a dead WebView
 * (host unreachable) — same copy and shape in the game host and the legal doc viewer.
 * [background] differs: the surface over a live game page, the screen background as a
 * standalone state; the text color follows it.
 */
@Composable
fun ServerUnreachableRetry(
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
  background: Color = MaterialTheme.colorScheme.background,
) {
  Column(
    modifier
      .fillMaxSize()
      .background(background)
      .padding(horizontal = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      // Short form — the Retry button already says "try again".
      stringResource(R.string.error_server_unreachable_short),
      style = MaterialTheme.typography.bodyLarge,
      color = contentColorFor(background),
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onRetry, modifier = Modifier.height(52.dp)) {
      Text(stringResource(R.string.action_retry), style = MaterialTheme.typography.titleMedium)
    }
  }
}
