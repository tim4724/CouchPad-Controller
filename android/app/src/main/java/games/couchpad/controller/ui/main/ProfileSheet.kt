package games.couchpad.controller.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import games.couchpad.controller.R
import games.couchpad.controller.data.FunnyName
import games.couchpad.controller.data.Profile
import games.couchpad.controller.ui.components.KeyboardSheet

/** Below this screen height the keyboard leaves too little room to stack — see [ProfileSheet]. */
private const val COMPACT_HEIGHT_BREAKPOINT = 480

/**
 * Name-only identity sheet. The primary button stays disabled until a non-blank
 * name is entered — you can't persist an empty profile. [title]/[cta] let the
 * join gate reword it.
 *
 * Two layouts: stacked, and — on a screen too short to fit it above the keyboard — the
 * field and button sharing a row with the title dropped. The title is what goes because
 * it's the one piece carrying no information: [ProfileStore] never yields a blank name
 * (it mints one on first run), so the field always opens showing the name in force.
 *
 * Opens with the keyboard up in both. Portrait is content-height and rides on top of
 * the keyboard; landscape fills the screen at the keyboard's width and the keyboard
 * slides in over its lower part, as on iOS, with a close button in the row since there
 * is no backdrop left to tap.
 *
 * [KeyboardSheet], not Material's: see there for why.
 */
@Composable
fun ProfileSheet(
  initial: Profile,
  onDismiss: () -> Unit,
  onSave: (Profile) -> Unit,
  title: String = stringResource(R.string.name),
  cta: String = stringResource(R.string.save),
  // In-game: the host passes the game's theme-color to tint the sheet surface.
  surfaceTint: Color? = null,
) {
  // A TextFieldValue so the cursor starts at the end: the sheet focuses the field
  // itself, and a String field would put it at the start.
  var name by remember { mutableStateOf(TextFieldValue(initial.name, TextRange(initial.name.length))) }

  // Latched at first composition, and that matters: switching layout swaps the field
  // to a different structural parent, which destroys and rebuilds it and takes the
  // focus with it — the keyboard opens and shuts again. (movableContentOf doesn't
  // rescue it; it carries remembered state, not focus.) So this must never change
  // while the sheet is up. Reading LocalConfiguration live would: the game host
  // handles orientation itself rather than being recreated (see AndroidManifest), so
  // a game turning the screen mid-rename would flip this under the player's finger.
  //
  // The breakpoint stands in for "a phone in landscape", the only case short enough
  // that the keyboard crowds out the stacked layout. Tablets keep the roomy one.
  val screenHeightDp = LocalConfiguration.current.screenHeightDp
  val compact = remember { screenHeightDp < COMPACT_HEIGHT_BREAKPOINT }

  // Set by Save; read once the sheet has slid out, so a save and a dismissal both get
  // to play the slide-out before the caller tears the sheet down.
  var saved by remember { mutableStateOf<Profile?>(null) }

  KeyboardSheet(
    fullHeight = compact,
    // The same surface as AppSheet (see there).
    color = surfaceTint ?: MaterialTheme.colorScheme.surfaceContainerHigh,
    onClosed = {
      val profile = saved
      if (profile != null) onSave(profile) else onDismiss()
    },
  ) { close ->
    val save = {
      if (name.text.isNotBlank()) {
        saved = Profile(name.text.trim())
        close()
      }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
      Modifier.fillMaxWidth().padding(
        start = if (compact) 8.dp else 20.dp,
        end = 20.dp,
        top = if (compact) 16.dp else 24.dp,
        bottom = if (compact) 20.dp else 28.dp,
      ),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      if (!compact) Text(title, style = MaterialTheme.typography.titleLarge)

      if (compact) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(onClick = close) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
          }
          NameField(name, { name = it }, save, Modifier.weight(1f).focusRequester(focus))
          SaveButton(cta, name.text.isNotBlank(), save)
        }
      } else {
        NameField(name, { name = it }, save, Modifier.fillMaxWidth().focusRequester(focus))
        SaveButton(cta, name.text.isNotBlank(), save, Modifier.fillMaxWidth())
      }
    }
  }
}

@Composable
private fun NameField(
  name: TextFieldValue,
  onName: (TextFieldValue) -> Unit,
  onSave: () -> Unit,
  modifier: Modifier = Modifier,
) {
  OutlinedTextField(
    value = name,
    onValueChange = { if (it.text.length <= 16) onName(it) },
    singleLine = true,
    // A player name is a proper noun — correcting it is always wrong.
    keyboardOptions = KeyboardOptions(
      capitalization = KeyboardCapitalization.Words,
      autoCorrectEnabled = false,
      imeAction = ImeAction.Done,
    ),
    keyboardActions = KeyboardActions(onDone = { onSave() }),
    trailingIcon = {
      IconButton(onClick = {
        val random = FunnyName.random()
        onName(TextFieldValue(random, TextRange(random.length)))
      }) { Text("🎲") }
    },
    modifier = modifier,
  )
}

@Composable
private fun SaveButton(
  cta: String,
  enabled: Boolean,
  onSave: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Button(
    onClick = onSave,
    enabled = enabled,
    modifier = modifier.height(52.dp),
  ) {
    Text(cta)
  }
}
