#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <dmg-path> [app-name]" >&2
  exit 2
fi

dmg_path="$1"
app_name="${2:-Chat4J}"
repo_root="$(cd "$(dirname "$0")/.." && pwd -P)"

if [[ ! -f "$dmg_path" ]]; then
  echo "DMG not found: $dmg_path" >&2
  exit 1
fi

mount_point="$(mktemp -d "${TMPDIR:-/tmp}/chat4j-dmg-verify.XXXXXX")"
mounted=0
cleanup() {
  if [[ "$mounted" -eq 1 ]] && ! hdiutil detach "$mount_point" -quiet >/dev/null 2>&1; then
    sleep 1
    if ! hdiutil detach "$mount_point" -force -quiet >/dev/null 2>&1; then
      echo "DMG verification warning: could not detach $mount_point" >&2
      return
    fi
  fi
  if ! rmdir "$mount_point" >/dev/null 2>&1; then
    echo "DMG verification warning: could not remove mount directory $mount_point" >&2
  fi
}
trap cleanup EXIT

hdiutil attach -nobrowse -readonly -mountpoint "$mount_point" "$dmg_path" >/dev/null
mounted=1

if [[ ! -d "$mount_point/$app_name.app" ]]; then
  echo "DMG verification failed: missing $app_name.app" >&2
  exit 1
fi

applications_item="$mount_point/Applications"
if [[ ! -e "$applications_item" ]]; then
  echo "DMG verification failed: missing Applications symlink/alias" >&2
  exit 1
fi

if [[ -L "$applications_item" ]]; then
  link_target="$(readlink "$applications_item")"
  if [[ "$link_target" != /* ]]; then
    link_target="$(dirname "$applications_item")/$link_target"
  fi
  applications_target="$(cd "$link_target" 2>/dev/null && pwd -P || true)"
else
  applications_target="$(osascript \
    -e 'on run argv' \
    -e 'set hfsPath to (POSIX file (item 1 of argv)) as text' \
    -e 'tell application "Finder"' \
    -e 'set aliasFile to item hfsPath' \
    -e 'set targetItem to original item of aliasFile' \
    -e 'return POSIX path of (targetItem as alias)' \
    -e 'end tell' \
    -e 'end run' \
    "$applications_item" 2>/dev/null || true)"
fi
if [[ "$applications_target" != "/Applications" && "$applications_target" != "/Applications/" ]]; then
  echo "DMG verification failed: Applications item does not resolve to /Applications" >&2
  exit 1
fi

packaged_doctor="$mount_point/$app_name.app/Contents/app/tools/chat4j-doctor.sh"
source_doctor="$repo_root/scripts/chat4j-doctor.sh"
if [[ ! -f "$packaged_doctor" ]]; then
  echo "DMG verification failed: missing Contents/app/tools/chat4j-doctor.sh" >&2
  exit 1
fi
if ! grep -q 'TOGETHER_API_KEY' "$packaged_doctor"; then
  echo "DMG verification failed: packaged doctor does not recognize TOGETHER_API_KEY" >&2
  exit 1
fi
if ! cmp -s "$source_doctor" "$packaged_doctor"; then
  echo "DMG verification failed: packaged doctor differs from scripts/chat4j-doctor.sh" >&2
  exit 1
fi

info_plist="$mount_point/$app_name.app/Contents/Info.plist"
resources_dir="$mount_point/$app_name.app/Contents/Resources"
if ! icon_file="$(plutil -extract CFBundleIconFile raw -o - "$info_plist" 2>/dev/null)"; then
  icon_file=""
fi
if [[ -z "$icon_file" ]]; then
  echo "DMG verification failed: missing CFBundleIconFile in $app_name.app Info.plist" >&2
  exit 1
fi
if [[ ! -f "$resources_dir/$icon_file" && ! -f "$resources_dir/$icon_file.icns" ]]; then
  echo "DMG verification failed: app icon resource not found for CFBundleIconFile=$icon_file" >&2
  exit 1
fi

if ! microphone_usage="$(plutil -extract NSMicrophoneUsageDescription raw -o - "$info_plist" 2>/dev/null)"; then
  microphone_usage=""
fi
expected_microphone_usage="Chat4J uses the microphone only when you press the Speech to Text recording button so your speech can be transcribed into the message composer."
if [[ -z "$microphone_usage" ]]; then
  echo "DMG verification failed: missing NSMicrophoneUsageDescription in $app_name.app Info.plist" >&2
  exit 1
fi
if [[ "$microphone_usage" != "$expected_microphone_usage" ]]; then
  echo "DMG verification failed: unexpected NSMicrophoneUsageDescription: $microphone_usage" >&2
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
