package games.couchpad.controller.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import games.couchpad.controller.BuildConfig
import games.couchpad.controller.R
import games.couchpad.controller.ui.components.BackScaffold

/**
 * The About hub. Lists the legal documents (privacy notice + imprint, kept
 * plainly labeled so the Impressum stays findable) and the open-source licenses.
 * Each row is two taps from home (info icon → here → document), satisfying the
 * "reachable from within the app" requirement for the Impressum.
 */
@Composable
fun AboutScreen(
  onOpenPrivacy: () -> Unit,
  onOpenImprint: () -> Unit,
  onOpenLicenses: () -> Unit,
  onOpenWebsite: () -> Unit,
  onBack: () -> Unit,
) {
  BackScaffold(title = stringResource(R.string.about), onBack = onBack) { innerPadding ->
    Column(Modifier.fillMaxSize().padding(innerPadding)) {
      AboutRow(stringResource(R.string.privacy_policy), onOpenPrivacy)
      HorizontalDivider()
      AboutRow(stringResource(R.string.imprint), onOpenImprint)
      HorizontalDivider()
      AboutRow(stringResource(R.string.open_source_licenses), onOpenLicenses)
      HorizontalDivider()
      // Leaves the app for the system browser, so it gets the external-link
      // glyph instead of the in-app-navigation chevron.
      AboutRow(
        "couchpad.games",
        onOpenWebsite,
        icon = ImageVector.vectorResource(R.drawable.ic_arrow_outward),
      )

      Spacer(Modifier.weight(1f))

      Text(
        stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(20.dp),
      )
    }
  }
}

@Composable
private fun AboutRow(
  label: String,
  onClick: () -> Unit,
  icon: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 20.dp, vertical = 18.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Icon(icon, contentDescription = null)
  }
}
