package games.couchpad.controller.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import games.couchpad.controller.R

/**
 * A [Scaffold] with a titled back-arrow [TopAppBar] — the shared shell for the
 * non-immersive About / Licenses / legal-document screens. Uses safeDrawing insets
 * so the bar clears the status bar and content clears the nav bar (unlike the
 * edge-to-edge home/game screens).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackScaffold(
  title: String,
  onBack: () -> Unit,
  content: @Composable (PaddingValues) -> Unit,
) {
  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
          }
        },
      )
    },
    content = content,
  )
}
