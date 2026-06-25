#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <dmg-path> [app-name]" >&2
  exit 2
fi

dmg_path="$1"
app_name="${2:-Chat4J}"

if [[ ! -f "$dmg_path" ]]; then
  echo "DMG not found: $dmg_path" >&2
  exit 1
fi

mount_point="$(mktemp -d "${TMPDIR:-/tmp}/chat4j-dmg-verify.XXXXXX")"
cleanup() {
  hdiutil detach "$mount_point" -quiet >/dev/null 2>&1 || true
  rmdir "$mount_point" >/dev/null 2>&1 || true
}
trap cleanup EXIT

hdiutil attach -nobrowse -readonly -mountpoint "$mount_point" "$dmg_path" >/dev/null

if [[ ! -d "$mount_point/$app_name.app" ]]; then
  echo "DMG verification failed: missing $app_name.app" >&2
  exit 1
fi

if [[ ! -e "$mount_point/Applications" ]]; then
  echo "DMG verification failed: missing Applications symlink/alias" >&2
  exit 1
fi

if [[ ! -e "$mount_point/.DS_Store" ]]; then
  if [[ "${CHAT4J_REQUIRE_DMG_FINDER_LAYOUT:-false}" == "true" ]]; then
    echo "DMG verification failed: missing Finder layout metadata (.DS_Store)" >&2
    exit 1
  fi
  echo "DMG verification warning: missing Finder layout metadata (.DS_Store); continuing because Finder metadata is not always written in headless packaging environments" >&2
fi

echo "Verified DMG layout: $dmg_path"
