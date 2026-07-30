package games.couchpad.controller.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import games.couchpad.controller.R
import games.couchpad.controller.data.FunnyName
import games.couchpad.controller.data.Profile
import games.couchpad.controller.ui.components.AppSheet

/**
 * Name-only identity sheet. The primary button stays disabled until a non-blank
 * name is entered — you can't persist an empty profile. [title]/[cta] let the
 * join gate reword it.
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
  var name by remember { mutableStateOf(initial.name) }

  // No auto-focus on purpose: the sheet settles first, the keyboard comes on tap.
  AppSheet(onDismiss = onDismiss, surfaceTint = surfaceTint) {
    Column(
      Modifier.fillMaxWidth().imePadding()
        .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleLarge)
      OutlinedTextField(
        value = name,
        onValueChange = { if (it.length <= 16) name = it },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        trailingIcon = {
          IconButton(onClick = { name = FunnyName.random() }) { Text("🎲") }
        },
        modifier = Modifier.fillMaxWidth(),
      )
      Button(
        onClick = { onSave(Profile(name.trim())) },
        enabled = name.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(52.dp),
      ) {
        Text(cta)
      }
    }
  }
}
