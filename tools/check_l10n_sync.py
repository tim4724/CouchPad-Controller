#!/usr/bin/env python3
"""Cross-platform localization sync check.

Verifies that Android and iOS ship the same translations for the same texts:

  1. Android: every values-XX/strings.xml has exactly the translatable <string>
     keys and <plurals> names of values/strings.xml (no missing, no extra).
  2. iOS: every Localizable.xcstrings key (unless shouldTranslate=false) has a
     "translated" unit for every locale; same for InfoPlist.xcstrings.
  3. Cross-platform: Android keys and iOS keys are matched via the English
     source text (Android's values/ value IS the iOS key, modulo %1$s vs %@).
     For every matched pair, the translation of each locale must be identical.
     Strings existing on only one platform must be declared in ANDROID_ONLY /
     IOS_ONLY below — anything undeclared fails, so one-sided additions are
     caught.
  4. Plurals (the player-count copy): the <plurals>/substitution KEYS match
     across platforms and every locale is present. The per-CLDR-category forms
     differ, so they're coverage-checked, not value-matched.
  5. games-manifest.json: android/assets and ios/Resources copies are
     byte-identical and purely structural (no per-locale copy leaked in).

Run from anywhere: python3 tools/check_l10n_sync.py
Exit code 0 = in sync, 1 = problems (listed on stdout).
"""

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCALES = ["de", "es", "fr", "it", "ja", "ko", "pt", "ru", "tr", "zh"]

ANDROID_RES = ROOT / "android/app/src/main/res"
IOS_RESOURCES = ROOT / "ios/CouchPad/Resources"
MANIFESTS = [
    ROOT / "android/app/src/main/assets/games-manifest.json",
    IOS_RESOURCES / "games-manifest.json",
]

# Android keys with no iOS counterpart, with the reason why.
ANDROID_ONLY = {
    "back",              # iOS uses the system back affordance
    "open_source_licenses",  # Android-only screen (iOS has no bundled licenses list)
    # CameraX scanner UI: iOS's AVFoundation scanner has its own copy (IOS_ONLY)
    "close_scanner",
    "flashlight_on",
    "flashlight_off",
    "scan_hint",
    "camera_access_needed",
    "camera_access_rationale",
    "allow_camera_access",
}

# iOS keys with no Android counterpart, with the reason why.
IOS_ONLY = {
    # AVFoundation scanner UI / failure modes (Android's CameraX copy differs)
    "Scan the room QR code",
    "The code is on your TV.",
    "Camera permission denied",
    "Camera unavailable",
    "No camera available",
    "Unable to scan with this camera",
    "Unable to use the camera",
    "QR scanning is not supported on this device",
    "Scanner unavailable: %@",
    "Version %@ (%@)",   # version footer: Android shows the version name only
}

problems = []


def problem(section, msg):
    problems.append((section, msg))


# --- Normalization -----------------------------------------------------------

PLACEHOLDER = re.compile(r"%(\d+\$)?[@sd]")


def normalize(s):
    """Make Android and iOS variants of the same text comparable."""
    s = PLACEHOLDER.sub("%s", s)
    return (
        s.replace("’", "'").replace("‘", "'")
        .replace("“", '"').replace("”", '"')
    )


def android_unescape(s):
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        s = s[1:-1]
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), s)


# --- Android -----------------------------------------------------------------

def load_android_strings(path):
    """strings.xml -> {key: value}; skips translatable=\"false\"."""
    out = {}
    for el in ET.parse(path).getroot():
        if el.tag != "string" or el.get("translatable") == "false":
            continue
        out[el.get("name")] = android_unescape("".join(el.itertext()))
    return out


def load_android_plurals(path):
    """strings.xml -> {plurals name}; count copy lives in <plurals>, not <string>."""
    return {el.get("name") for el in ET.parse(path).getroot() if el.tag == "plurals"}


android_en = load_android_strings(ANDROID_RES / "values/strings.xml")
android_plurals_en = load_android_plurals(ANDROID_RES / "values/strings.xml")
android = {}  # locale -> {key: value}
for loc in LOCALES:
    path = ANDROID_RES / f"values-{loc}/strings.xml"
    if not path.exists():
        problem("android", f"missing {path.relative_to(ROOT)}")
        continue
    android[loc] = load_android_strings(path)
    missing = android_en.keys() - android[loc].keys()
    extra = android[loc].keys() - android_en.keys()
    for k in sorted(missing):
        problem("android", f"values-{loc}: missing key '{k}'")
    for k in sorted(extra):
        problem("android", f"values-{loc}: extra key '{k}' (not in values/)")
    plurals = load_android_plurals(path)
    for k in sorted(android_plurals_en - plurals):
        problem("android", f"values-{loc}: missing plurals '{k}'")
    for k in sorted(plurals - android_plurals_en):
        problem("android", f"values-{loc}: extra plurals '{k}' (not in values/)")

# --- iOS ---------------------------------------------------------------------

def load_xcstrings(path):
    """xcstrings -> (strings {key:{loc:value}}, plurals {key:{locales}}); skips
    shouldTranslate=false. Plural/substitution entries (the count copy) are returned
    separately: their per-CLDR-category forms aren't a single string to compare, so
    they're coverage-checked, not value-matched."""
    data = json.loads(path.read_text())
    strings, plurals = {}, {}
    for key, entry in data["strings"].items():
        if entry.get("shouldTranslate") is False:
            continue
        locs = entry.get("localizations", {})
        if any("variations" in u or "substitutions" in u for u in locs.values()):
            plurals[key] = set(locs.keys())
            continue
        units = {}
        for loc, unit in locs.items():
            su = unit.get("stringUnit", {})
            if su.get("state") != "translated":
                problem("ios", f"{path.name}: '{key}' [{loc}] state is "
                               f"'{su.get('state')}', expected 'translated'")
            units[loc] = su.get("value", "")
        strings[key] = units
    return strings, plurals


ios, ios_plurals = load_xcstrings(IOS_RESOURCES / "Localizable.xcstrings")
for key, units in ios.items():
    for loc in LOCALES:
        if loc not in units:
            problem("ios", f"Localizable.xcstrings: '{key}' missing locale '{loc}'")

infoplist, _ = load_xcstrings(IOS_RESOURCES / "InfoPlist.xcstrings")
for key, units in infoplist.items():
    for loc in LOCALES:
        if loc not in units:
            problem("ios", f"InfoPlist.xcstrings: '{key}' missing locale '{loc}'")

# --- Cross-platform ----------------------------------------------------------

ios_by_norm = {normalize(k): k for k in ios}
matched_ios = set()

for key, en_value in sorted(android_en.items()):
    # Symbolic keys shared verbatim across platforms match by key; everything else
    # matches Android's English value against iOS's source-string key.
    ios_key = key if key in ios else ios_by_norm.get(normalize(en_value))
    if ios_key is None:
        if key not in ANDROID_ONLY:
            problem("sync", f"Android '{key}' (\"{en_value}\") has no iOS "
                            f"counterpart and is not declared ANDROID_ONLY")
        continue
    if key in ANDROID_ONLY:
        problem("sync", f"Android '{key}' is declared ANDROID_ONLY but iOS has "
                        f"a matching key '{ios_key}' — remove the declaration")
    matched_ios.add(ios_key)
    for loc in LOCALES:
        a = android.get(loc, {}).get(key)
        i = ios[ios_key].get(loc)
        if a is None or i is None:
            continue  # already reported as missing above
        if normalize(a) != normalize(i):
            problem("sync", f"'{key}' / '{ios_key}' [{loc}] differs:\n"
                            f"      android: {a}\n      ios:     {i}")

for ios_key in sorted(ios.keys() - matched_ios):
    if ios_key not in IOS_ONLY:
        problem("sync", f"iOS '{ios_key}' has no Android counterpart and is "
                        f"not declared IOS_ONLY")
for ios_key in sorted(IOS_ONLY & matched_ios):
    problem("sync", f"iOS '{ios_key}' is declared IOS_ONLY but matched an "
                    f"Android key — remove the declaration")

# --- Plurals (player-count copy) ---------------------------------------------
# Count copy is <plurals> on Android and plural/substitution entries on iOS. The
# per-form strings differ by CLDR category (and by platform format specifier), so
# verify the KEYS match across platforms and every locale is present on iOS, rather
# than comparing values. Android per-locale plurals coverage is checked above.
for key in sorted(ios_plurals):
    for loc in LOCALES:
        if loc not in ios_plurals[key]:
            problem("ios", f"Localizable.xcstrings: plural '{key}' missing locale '{loc}'")
if android_plurals_en != set(ios_plurals):
    problem("sync", f"plural keys differ across platforms — android "
                    f"{sorted(android_plurals_en)} vs ios {sorted(ios_plurals)}")

# --- games-manifest.json -----------------------------------------------------

blobs = [p.read_bytes() for p in MANIFESTS]
if blobs[0] != blobs[1]:
    problem("manifest", "android/assets and ios/Resources copies are not "
                        "byte-identical — copy one over the other")

# The manifest is purely structural — counts and flags are numbers/enums, never
# translated copy (that lives in string resources: shared plurals/strings, not
# per-game keys). A per-locale map in any field means copy leaked back in.
for game in json.loads(blobs[0]).get("games", []):
    gid = game.get("id", "?")
    for field, value in game.items():
        if isinstance(value, dict) and value.keys() & set(LOCALES):
            problem("manifest", f"{gid}.{field}: per-locale map in the manifest — "
                                f"translated copy belongs in string resources")

# --- Report ------------------------------------------------------------------

if problems:
    for section, msg in problems:
        print(f"[{section}] {msg}")
    print(f"\n{len(problems)} problem(s).")
    sys.exit(1)

n_matched = len(matched_ios)
print(f"OK: {len(android_en)} Android keys, {len(ios)} iOS keys, "
      f"{n_matched} matched cross-platform, {len(LOCALES)} locales, "
      f"manifest copies identical.")
