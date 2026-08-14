#!/bin/sh
# Encode a game's 16:9 cover art into both app bundles.
#
#   tools/encode_artwork.sh master1.png [master2.webp ...]
#
# Deliberately NOT a build step. Artwork changes once per game, not once per
# build, so encoding on every build would put libwebp on every dev machine and
# both CI runners to reproduce a file that is already in git — and it still
# wouldn't cover the copies couchpad.games serves, which live in another repo.
# Run this when the art changes; commit what it writes.
#
# 1280x720 is the ceiling both apps decode to (MAX_ART_* in Android's GameUi.kt,
# maxPixelSize in iOS's SharedComponents.swift), so anything larger is bytes the
# download pays for and no screen ever shows. q85 is what the original set was
# encoded at; re-encoding at 1280x720 q85 measured SSIM 0.967-0.982 against a
# clean downscale.
#
# Pass MASTERS, not the shipped copies: each run is another generation of lossy
# re-encode. Icons are not covered — they are square, tiny, and already under
# the ceiling.
set -eu

QUALITY=85
MAX_WIDTH=1280

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
android="$root/android/app/src/main/assets/artwork"
ios="$root/ios/CouchPad/Resources/artwork"

if [ $# -eq 0 ]; then
  cat >&2 <<'USAGE'
usage: tools/encode_artwork.sh MASTER...

Encodes each MASTER to 1280x720 q85 webp into both app bundles, keeping its
basename. Pass masters, not the shipped copies — every run is another
generation of lossy re-encode. Covers only; icons are already under the ceiling.
USAGE
  exit 2
fi
command -v cwebp >/dev/null || { echo "cwebp not found (brew install webp)" >&2; exit 1; }

for master in "$@"; do
  name=$(basename "$master")
  name="${name%.*}.webp"
  # Height 0 = keep the aspect ratio, so a cover that isn't exactly 16:9 is
  # width-capped rather than squashed.
  cwebp -quiet -q "$QUALITY" -resize "$MAX_WIDTH" 0 "$master" -o "$android/$name"
  cp "$android/$name" "$ios/$name"
  # Report what actually landed — the one thing worth eyeballing is that the
  # source was bigger than the ceiling, not smaller (cwebp would upscale it).
  size=$(wc -c < "$android/$name" | tr -d ' ')
  dims=$(webpinfo "$android/$name" | awk '/Width:/{w=$2} /Height:/{h=$2} END{print w"x"h}')
  printf '%-30s %s  %7s bytes\n' "$name" "$dims" "$size"
done

# The two bundles must stay byte-identical (see CLAUDE.md) — copying above is
# what guarantees it, and this is what proves the whole directory still is.
for f in "$android"/*; do
  cmp -s "$f" "$ios/$(basename "$f")" ||
    { echo "MISMATCH: $(basename "$f") differs between the two bundles" >&2; exit 1; }
done
echo "both bundles in sync"
