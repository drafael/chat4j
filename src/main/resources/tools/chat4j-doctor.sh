#!/usr/bin/env bash

set -uo pipefail
IFS=$'\n\t'
umask 077

SCRIPT_NAME="chat4j-doctor"
DEFAULT_APP_PATH="/Applications/Chat4J.app"
APP_PATH="$DEFAULT_APP_PATH"
STRICT_MODE=0
JSON_MODE=0
VERBOSE=0

WARN_COUNT=0
CRITICAL_COUNT=0
SECURITY_BASELINE_OK=0

REPORT_DIR="${XDG_CONFIG_HOME:-${HOME}/.config}/chat4j/logs/doctor"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
REPORT_FILE="${REPORT_DIR}/doctor-${TIMESTAMP}.md"
JSON_FILE="${REPORT_DIR}/doctor-${TIMESTAMP}.json"
JSON_LINES_FILE=""

KNOWN_PROVIDER_KEYS=(
  "ANTHROPIC_API_KEY"
  "OPENAI_API_KEY"
  "OPENROUTER_API_KEY"
  "GROQ_API_KEY"
  "DEEPSEEK_API_KEY"
  "MISTRAL_API_KEY"
  "XAI_API_KEY"
  "GEMINI_API_KEY"
  "GOOGLEAI_API_KEY"
  "GOOGLE_AI_API_KEY"
)

usage() {
  cat <<EOF
Usage: ${SCRIPT_NAME} [options]

Options:
  --app <path>      Path to Chat4J.app (default: ${DEFAULT_APP_PATH})
  --strict          Require build identity metadata (appleTeamId/appleBundleId)
  --json            Write JSON report next to markdown report
  --verbose         Print command output snippets for passing checks
  -h, --help        Show this help

Exit codes:
  0 = all checks passed
  1 = warnings only
  2 = critical startup/security blockers detected
EOF
}

trim() {
  local value="${1:-}"
  value="${value#${value%%[![:space:]]*}}"
  value="${value%${value##*[![:space:]]}}"
  printf '%s' "$value"
}

one_line() {
  local value
  value="$(printf '%s' "${1:-}" | tr '\n' ' ' | tr '\r' ' ')"
  value="$(trim "$value")"
  if [ "${#value}" -gt 220 ]; then
    value="${value:0:217}..."
  fi
  printf '%s' "$value"
}

json_escape() {
  printf '%s' "${1:-}" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

append_report() {
  printf '%s\n' "$1" >> "$REPORT_FILE"
}

record_check() {
  local check_name="$1"
  local severity="$2"
  local detail="$3"
  local remediation="${4:-}"
  local safe_detail
  safe_detail="$(one_line "$detail")"

  case "$severity" in
    PASS)
      printf '[PASS] %s - %s\n' "$check_name" "$safe_detail"
      ;;
    WARN)
      WARN_COUNT=$((WARN_COUNT + 1))
      printf '[WARN] %s - %s\n' "$check_name" "$safe_detail"
      ;;
    CRITICAL)
      CRITICAL_COUNT=$((CRITICAL_COUNT + 1))
      printf '[CRITICAL] %s - %s\n' "$check_name" "$safe_detail"
      ;;
    *)
      WARN_COUNT=$((WARN_COUNT + 1))
      printf '[WARN] %s - %s\n' "$check_name" "$safe_detail"
      severity="WARN"
      ;;
  esac

  append_report "### ${check_name}"
  append_report "- Severity: ${severity}"
  append_report "- Detail: ${safe_detail}"
  if [ -n "$remediation" ]; then
    append_report "- Remediation: ${remediation}"
  fi
  append_report ""

  if [ "$JSON_MODE" -eq 1 ]; then
    local escaped_name escaped_severity escaped_detail escaped_remediation
    escaped_name="$(json_escape "$check_name")"
    escaped_severity="$(json_escape "$severity")"
    escaped_detail="$(json_escape "$safe_detail")"
    escaped_remediation="$(json_escape "$(one_line "$remediation")")"

    printf '{"check":"%s","severity":"%s","detail":"%s","remediation":"%s"}\n' \
      "$escaped_name" "$escaped_severity" "$escaped_detail" "$escaped_remediation" >> "$JSON_LINES_FILE"
  fi
}

read_property() {
  local key="$1"
  local file="$2"
  if [ ! -f "$file" ]; then
    printf ''
    return 0
  fi

  awk -F= -v k="$key" '$1 == k {sub(/^[^=]*=/, ""); print; exit}' "$file"
}

canonical_path() {
  local candidate="$1"
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$candidate" <<'PY'
import os
import sys
print(os.path.realpath(sys.argv[1]))
PY
    return 0
  fi

  local parent
  parent="$(cd "$(dirname "$candidate")" >/dev/null 2>&1 && pwd -P)" || return 1
  printf '%s/%s\n' "$parent" "$(basename "$candidate")"
}

run_command_capture() {
  local output_file="$1"
  shift

  "$@" >"$output_file" 2>&1
  return $?
}

run_env_probe() {
  local shell_path="$1"
  local stdout_file="$2"
  local stderr_file="$3"
  local timeout_seconds=5
  local elapsed_seconds=0
  local timed_out=0

  "$shell_path" -l -i -c env >"$stdout_file" 2>"$stderr_file" &
  local probe_pid=$!

  while kill -0 "$probe_pid" 2>/dev/null; do
    if [ "$elapsed_seconds" -ge "$timeout_seconds" ]; then
      timed_out=1
      kill "$probe_pid" 2>/dev/null || true
      sleep 1
      kill -9 "$probe_pid" 2>/dev/null || true
      break
    fi
    sleep 1
    elapsed_seconds=$((elapsed_seconds + 1))
  done

  local probe_status=0
  if [ "$timed_out" -eq 1 ]; then
    wait "$probe_pid" 2>/dev/null || true
    probe_status=124
  else
    wait "$probe_pid"
    probe_status=$?
  fi

  printf '%s;%s\n' "$probe_status" "$timed_out"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --app)
      shift
      if [ "$#" -eq 0 ]; then
        echo "Missing value for --app"
        usage
        exit 2
      fi
      APP_PATH="$1"
      ;;
    --strict)
      STRICT_MODE=1
      ;;
    --json)
      JSON_MODE=1
      ;;
    --verbose)
      VERBOSE=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1"
      usage
      exit 2
      ;;
  esac
  shift
done

mkdir -p "$REPORT_DIR"
if [ "$JSON_MODE" -eq 1 ]; then
  JSON_LINES_FILE="$(mktemp "${REPORT_DIR}/doctor-checks-XXXXXX")"
fi

APP_PATH="$(canonical_path "$APP_PATH")"

{
  echo "# Chat4J Doctor Report"
  echo ""
  echo "- Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  echo "- App path: ${APP_PATH}"
  echo "- Strict mode: ${STRICT_MODE}"
  echo ""
  echo "## Check Results"
  echo ""
} > "$REPORT_FILE"

if [ ! -d "$APP_PATH" ]; then
  record_check "App bundle exists" "CRITICAL" "App bundle not found at ${APP_PATH}" "Verify the app path and reinstall Chat4J from a trusted source."
else
  record_check "App bundle exists" "PASS" "Found app bundle at ${APP_PATH}"
fi

LAUNCHER_PATH="${APP_PATH}/Contents/MacOS/Chat4J"
if [ -x "$LAUNCHER_PATH" ]; then
  record_check "Launcher executable" "PASS" "Launcher is executable: ${LAUNCHER_PATH}"
else
  record_check "Launcher executable" "CRITICAL" "Launcher missing or not executable: ${LAUNCHER_PATH}" "Reinstall Chat4J from a trusted release artifact."
fi

BUILD_PROPERTIES_PATH="${APP_PATH}/Contents/app/classes/build.properties"
if [ -f "$BUILD_PROPERTIES_PATH" ]; then
  record_check "Build metadata file" "PASS" "Found build metadata at ${BUILD_PROPERTIES_PATH}"
else
  record_check "Build metadata file" "WARN" "Build metadata not found at ${BUILD_PROPERTIES_PATH}" "Identity checks will run in compatibility mode."
fi

CODESIGN_VERIFY_OUTPUT="$(mktemp "${REPORT_DIR}/codesign-verify-XXXXXX")"
if run_command_capture "$CODESIGN_VERIFY_OUTPUT" /usr/bin/codesign --verify --deep --strict --verbose=2 "$APP_PATH"; then
  record_check "Code signature integrity" "PASS" "codesign --verify succeeded"
else
  record_check "Code signature integrity" "CRITICAL" "codesign verify failed: $(one_line "$(cat "$CODESIGN_VERIFY_OUTPUT")")" "Do not bypass security checks. Reinstall a trusted signed build."
fi

CODESIGN_DETAILS_OUTPUT="$(mktemp "${REPORT_DIR}/codesign-details-XXXXXX")"
if run_command_capture "$CODESIGN_DETAILS_OUTPUT" /usr/bin/codesign -dv --verbose=4 "$APP_PATH"; then
  ACTUAL_TEAM_ID="$(awk -F= '/^TeamIdentifier=/ {print $2; exit}' "$CODESIGN_DETAILS_OUTPUT")"
  ACTUAL_BUNDLE_ID="$(awk -F= '/^Identifier=/ {print $2; exit}' "$CODESIGN_DETAILS_OUTPUT")"
  ACTUAL_SIGNER_CN="$(awk -F= '/^Authority=Developer ID Application:/ {sub(/^Authority=/, ""); print; exit}' "$CODESIGN_DETAILS_OUTPUT")"
  record_check "Signer details" "PASS" "TeamIdentifier=${ACTUAL_TEAM_ID:-unknown}, Identifier=${ACTUAL_BUNDLE_ID:-unknown}"
else
  record_check "Signer details" "WARN" "Could not read detailed signer metadata: $(one_line "$(cat "$CODESIGN_DETAILS_OUTPUT")")"
  ACTUAL_TEAM_ID=""
  ACTUAL_BUNDLE_ID=""
  ACTUAL_SIGNER_CN=""
fi

EXPECTED_TEAM_ID="$(trim "$(read_property "appleTeamId" "$BUILD_PROPERTIES_PATH")")"
EXPECTED_BUNDLE_ID="$(trim "$(read_property "appleBundleId" "$BUILD_PROPERTIES_PATH")")"
EXPECTED_SIGNER_CN="$(trim "$(read_property "appleSignerCn" "$BUILD_PROPERTIES_PATH")")"

if [ "$STRICT_MODE" -eq 1 ]; then
  if [ -z "$EXPECTED_TEAM_ID" ] || [ -z "$EXPECTED_BUNDLE_ID" ]; then
    record_check "Expected identity metadata" "CRITICAL" "Strict mode requires appleTeamId and appleBundleId in build.properties" "Use a release build with embedded signing metadata."
  else
    record_check "Expected identity metadata" "PASS" "Strict metadata is present"
  fi
else
  if [ -z "$EXPECTED_TEAM_ID" ] || [ -z "$EXPECTED_BUNDLE_ID" ]; then
    record_check "Expected identity metadata" "WARN" "Unverifiable build identity: appleTeamId/appleBundleId metadata is missing" "Use strict mode for release validation."
  else
    record_check "Expected identity metadata" "PASS" "Expected team and bundle identifiers are present"
  fi
fi

if [ -n "$EXPECTED_TEAM_ID" ] && [ -n "$ACTUAL_TEAM_ID" ]; then
  if [ "$EXPECTED_TEAM_ID" = "$ACTUAL_TEAM_ID" ]; then
    record_check "Team identifier match" "PASS" "Actual TeamIdentifier matches expected value"
  else
    record_check "Team identifier match" "CRITICAL" "Expected TeamIdentifier ${EXPECTED_TEAM_ID} but found ${ACTUAL_TEAM_ID}" "Do not trust this bundle. Reinstall from a trusted source."
  fi
fi

if [ -n "$EXPECTED_BUNDLE_ID" ] && [ -n "$ACTUAL_BUNDLE_ID" ]; then
  if [ "$EXPECTED_BUNDLE_ID" = "$ACTUAL_BUNDLE_ID" ]; then
    record_check "Bundle identifier match" "PASS" "Actual bundle identifier matches expected value"
  else
    record_check "Bundle identifier match" "CRITICAL" "Expected bundle identifier ${EXPECTED_BUNDLE_ID} but found ${ACTUAL_BUNDLE_ID}" "Do not trust this bundle. Reinstall from a trusted source."
  fi
fi

if [ -n "$EXPECTED_SIGNER_CN" ] && [ -n "$ACTUAL_SIGNER_CN" ]; then
  if [ "$EXPECTED_SIGNER_CN" = "$ACTUAL_SIGNER_CN" ]; then
    record_check "Signer CN match" "PASS" "Actual signer CN matches expected value"
  else
    record_check "Signer CN match" "WARN" "Expected signer CN ${EXPECTED_SIGNER_CN} but found ${ACTUAL_SIGNER_CN}" "Review signing profile differences for this build channel."
  fi
fi

SPCTL_OUTPUT="$(mktemp "${REPORT_DIR}/spctl-XXXXXX")"
if run_command_capture "$SPCTL_OUTPUT" /usr/sbin/spctl --assess --verbose=4 "$APP_PATH"; then
  record_check "Gatekeeper assessment" "PASS" "spctl assessment accepted"
else
  record_check "Gatekeeper assessment" "CRITICAL" "spctl rejected app: $(one_line "$(cat "$SPCTL_OUTPUT")")" "Reinstall a notarized build signed by a trusted developer identity."
fi

if [ "$CRITICAL_COUNT" -eq 0 ]; then
  SECURITY_BASELINE_OK=1
fi

XATTR_OUTPUT="$(mktemp "${REPORT_DIR}/xattr-XXXXXX")"
if run_command_capture "$XATTR_OUTPUT" /usr/bin/xattr -l "$APP_PATH"; then
  if grep -q "com.apple.quarantine" "$XATTR_OUTPUT"; then
    if [ "$SECURITY_BASELINE_OK" -eq 1 ]; then
      record_check \
        "Quarantine attribute" \
        "WARN" \
        "com.apple.quarantine attribute is present" \
        "If this is a trusted build, remove only this app quarantine: xattr -dr com.apple.quarantine \"${APP_PATH}\""
    else
      record_check \
        "Quarantine attribute" \
        "CRITICAL" \
        "quarantine present while signature or Gatekeeper checks failed" \
        "Do not remove quarantine. Resolve signature/Gatekeeper failures first."
    fi
  else
    record_check "Quarantine attribute" "PASS" "No quarantine attribute found"
  fi
else
  record_check "Quarantine attribute" "WARN" "Unable to inspect xattr metadata: $(one_line "$(cat "$XATTR_OUTPUT")")"
fi

if mkdir -p "${XDG_CONFIG_HOME:-${HOME}/.config}/chat4j/logs" 2>/dev/null; then
  WRITE_TEST_FILE="${XDG_CONFIG_HOME:-${HOME}/.config}/chat4j/logs/.doctor-write-test-${TIMESTAMP}"
  if touch "$WRITE_TEST_FILE" 2>/dev/null; then
    rm -f "$WRITE_TEST_FILE"
    record_check "Writable log directory" "PASS" "Chat4J log directory is writable"
  else
    record_check "Writable log directory" "WARN" "Cannot write to Chat4J log directory" "Fix permissions on ${XDG_CONFIG_HOME:-${HOME}/.config}/chat4j/logs"
  fi
else
  record_check "Writable log directory" "WARN" "Cannot create Chat4J log directory" "Fix permissions on ${XDG_CONFIG_HOME:-${HOME}/.config}/chat4j"
fi

ENV_STDOUT_FILE="$(mktemp "${REPORT_DIR}/env-probe-out-XXXXXX")"
ENV_STDERR_FILE="$(mktemp "${REPORT_DIR}/env-probe-err-XXXXXX")"
ENV_SHELL="${SHELL:-/bin/zsh}"
if [ ! -x "$ENV_SHELL" ]; then
  ENV_SHELL="/bin/zsh"
fi

ENV_PROBE_RESULT="$(run_env_probe "$ENV_SHELL" "$ENV_STDOUT_FILE" "$ENV_STDERR_FILE")"
ENV_PROBE_STATUS="${ENV_PROBE_RESULT%%;*}"
ENV_PROBE_TIMED_OUT="${ENV_PROBE_RESULT##*;}"

if [ "$ENV_PROBE_TIMED_OUT" = "1" ]; then
  record_check "Shell environment probe" "WARN" "${ENV_SHELL} -l -i -c env timed out after 5s" "Simplify shell startup scripts and avoid interactive blocking commands."
elif [ "$ENV_PROBE_STATUS" != "0" ]; then
  record_check "Shell environment probe" "WARN" "Shell env probe exited with code ${ENV_PROBE_STATUS}: $(one_line "$(cat "$ENV_STDERR_FILE")")"
else
  PRESENT_KEYS=()
  for key in "${KNOWN_PROVIDER_KEYS[@]}"; do
    if grep -q "^${key}=" "$ENV_STDOUT_FILE"; then
      PRESENT_KEYS+=("$key")
    fi
  done

  if [ "${#PRESENT_KEYS[@]}" -eq 0 ]; then
    record_check "Provider env key detection" "WARN" "No known provider API key names found in shell environment output" "Check exports in your shell profile (values are intentionally not printed)."
  else
    record_check "Provider env key detection" "PASS" "Detected provider key names: ${PRESENT_KEYS[*]}"
  fi
fi

append_report "## Summary"
append_report "- Critical findings: ${CRITICAL_COUNT}"
append_report "- Warnings: ${WARN_COUNT}"
append_report "- Exit code: $( [ "$CRITICAL_COUNT" -gt 0 ] && echo 2 || ( [ "$WARN_COUNT" -gt 0 ] && echo 1 || echo 0 ) )"
append_report ""
append_report "## Security notes"
append_report "- This tool never reads or prints credential values."
append_report "- If signature or Gatekeeper checks fail, do not remove quarantine attributes."
append_report ""

if [ "$JSON_MODE" -eq 1 ]; then
  {
    echo "{"
    echo "  \"generatedAt\": \"$(date -u +"%Y-%m-%dT%H:%M:%SZ")\"," 
    echo "  \"appPath\": \"$(json_escape "$APP_PATH")\"," 
    echo "  \"strictMode\": ${STRICT_MODE},"
    echo "  \"criticalCount\": ${CRITICAL_COUNT},"
    echo "  \"warningCount\": ${WARN_COUNT},"
    echo "  \"checks\": ["
    if [ -n "$JSON_LINES_FILE" ] && [ -s "$JSON_LINES_FILE" ]; then
      awk 'NR==1{printf "    %s", $0; next} {printf ",\n    %s", $0} END{printf "\n"}' "$JSON_LINES_FILE"
    fi
    echo "  ]"
    echo "}"
  } > "$JSON_FILE"
fi

if [ "$VERBOSE" -eq 1 ]; then
  echo ""
  echo "Detailed command output snippets:" 
  echo "- codesign verify: $(one_line "$(cat "$CODESIGN_VERIFY_OUTPUT")")"
  echo "- spctl assess: $(one_line "$(cat "$SPCTL_OUTPUT")")"
  echo "- env probe stderr: $(one_line "$(cat "$ENV_STDERR_FILE")")"
fi

rm -f \
  "$CODESIGN_VERIFY_OUTPUT" \
  "$CODESIGN_DETAILS_OUTPUT" \
  "$SPCTL_OUTPUT" \
  "$XATTR_OUTPUT" \
  "$ENV_STDOUT_FILE" \
  "$ENV_STDERR_FILE"

if [ -n "$JSON_LINES_FILE" ]; then
  rm -f "$JSON_LINES_FILE"
fi

echo ""
echo "Report written to: ${REPORT_FILE}"
if [ "$JSON_MODE" -eq 1 ]; then
  echo "JSON report written to: ${JSON_FILE}"
fi

if [ "$CRITICAL_COUNT" -gt 0 ]; then
  exit 2
fi

if [ "$WARN_COUNT" -gt 0 ]; then
  exit 1
fi

exit 0
