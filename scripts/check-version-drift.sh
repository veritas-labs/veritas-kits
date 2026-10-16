#!/usr/bin/env bash
# Compares the [versions] of this repo's gradle/libs.versions.toml with Vesta's.
# Usage: scripts/check-version-drift.sh [path/to/Vesta]   (default: ../Vesta)
# Exit 1 when a key present in both files has a different value. Keys only in the kits
# (sqldelight, skiko, binaryCompatibilityValidator) are listed for information.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
vesta="${1:-$here/../Vesta}"
versions() { awk '/^\[versions\]/{f=1;next} /^\[/{f=0} f && /^[A-Za-z0-9_.-]+ *= *"/ {gsub(/ /,""); sub(/=/," "); print}' "$1" | tr -d '"' | sort; }
kits="$(versions "$here/gradle/libs.versions.toml")"
app="$(versions "$vesta/gradle/libs.versions.toml")"
drift=0
while read -r key value; do
  other="$(awk -v k="$key" '$1==k{print $2}' <<<"$app")"
  if [ -z "$other" ]; then echo "kits only : $key = $value"
  elif [ "$other" != "$value" ]; then echo "DRIFT     : $key kits=$value vesta=$other"; drift=1
  fi
done <<<"$kits"
[ $drift -eq 0 ] && echo "no drift in shared version keys"
exit $drift
