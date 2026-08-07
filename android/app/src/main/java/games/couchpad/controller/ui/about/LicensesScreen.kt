package games.couchpad.controller.ui.about

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import games.couchpad.controller.R
import games.couchpad.controller.ui.components.BackScaffold
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

/**
 * Open-source license list. The library definitions are generated at build time by
 * the AboutLibraries Gradle plugin into `R.raw.aboutlibraries` — there's no runtime
 * dependency scan, so [produceLibraries] just parses the bundled JSON.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
  BackScaffold(title = stringResource(R.string.open_source_licenses), onBack = onBack) { innerPadding ->
    val libraries by produceLibraries(R.raw.aboutlibraries)
    LibrariesContainer(
      libraries = libraries,
      modifier = Modifier.fillMaxSize().padding(innerPadding),
    )
  }
}
