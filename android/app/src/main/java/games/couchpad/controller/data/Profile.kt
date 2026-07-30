package games.couchpad.controller.data

import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * The player's shared identity — just a name, entered once and handed to every game.
 * Color is deliberately NOT part of the native identity: each game owns its own color
 * (it resolves slot-conflicts), so the launcher stays out of it.
 */
data class Profile(val name: String = "") {
  val isSet: Boolean get() = name.isNotBlank()
}

/**
 * Wholesome-funny fallback identity. Every device gets a random "Adjective Noun"
 * handle on first launch (see [ProfileStore.load]) so players jump straight into a
 * game instead of hitting a name wall — they can still rename or reroll (the 🎲 in
 * ProfileSheet). English-only by design (v1): gamer tags read as English across all
 * our locales, and it sidesteps a per-culture review of 500+ combos. Every word is
 * ≤7 chars so "Adjective Noun" always fits Contract v1's 16-char cpName. Keep this
 * list in sync with the iOS FunnyName in Models.swift.
 */
object FunnyName {
  private val ADJECTIVES = listOf(
    "Sneaky", "Turbo", "Wobbly", "Grumpy", "Sleepy", "Sparkly",
    "Chunky", "Zesty", "Feral", "Mighty", "Salty", "Cosmic",
    "Fluffy", "Rowdy", "Spicy", "Jolly", "Bouncy", "Cheeky",
    "Groovy", "Snazzy", "Wacky", "Zippy", "Plucky", "Silly",
  )
  private val NOUNS = listOf(
    "Otter", "Pigeon", "Waffle", "Noodle", "Muffin", "Goblin",
    "Wizard", "Llama", "Gecko", "Taco", "Yeti", "Panda",
    "Nugget", "Pickle", "Walrus", "Cactus", "Toaster", "Raccoon",
    "Narwhal", "Penguin", "Hamster", "Biscuit", "Wombat", "Falcon",
  )

  /** e.g. "Grumpy Waffle". Always non-blank and ≤16 chars. */
  fun random(): String = "${ADJECTIVES.random()} ${NOUNS.random()}"
}

object ProfileStore {
  private const val PREFS = "cp_profile"
  private const val KEY_NAME = "name"

  /**
   * Get-or-create: returns the stored name, or on first launch mints a [FunnyName]
   * and persists it — so every screen's load() reads the same identity instead of
   * each minting its own.
   */
  fun load(context: Context): Profile {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val stored = p.getString(KEY_NAME, "").orEmpty()
    if (stored.isNotBlank()) return Profile(stored)
    return Profile(FunnyName.random()).also { save(context, it) }
  }

  fun save(context: Context, profile: Profile) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
      putString(KEY_NAME, profile.name)
    }
  }
}

/**
 * Launcher→game identity contract (v1): append the player's name to the join URL so
 * the game can prefill it and skip its own name screen. Live changes are pushed
 * separately via the window.CouchPad.setName() JS bridge (see GameHostScreen).
 * Preserves any existing ?claim and #instance; no-op when there's no name.
 */
fun withProfile(joinUrl: String, profile: Profile): String {
  if (!profile.isSet) return joinUrl
  return joinUrl.toUri().buildUpon()
    .appendQueryParameter("cpv", "1")
    .appendQueryParameter("cpName", profile.name)
    .build().toString()
}
