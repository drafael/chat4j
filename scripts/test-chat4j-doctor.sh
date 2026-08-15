#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd -P)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/chat4j-doctor-test.XXXXXX")"
cleanup() {
  rm -rf "$test_root"
}
trap cleanup EXIT

export HOME="$test_root/home"
export XDG_CONFIG_HOME="$test_root/config"
mkdir -p "$HOME" "$XDG_CONFIG_HOME"

fake_shell="$test_root/fake-login-shell"
cat > "$fake_shell" <<'SHELL'
#!/usr/bin/env bash
printf '%s\n' "${TOGETHER_API_KEY:-}" >&1
printf '%s\n' "${TOGETHER_API_KEY:-}" >&2
interactive=0
for argument in "$@"; do
  if [[ "$argument" == "-i" ]]; then
    interactive=1
  fi
done
if [[ "${CHAT4J_DOCTOR_FAIL_INTERACTIVE_PROBE:-0}" == "1" && "$interactive" -eq 1 ]]; then
  exit 7
fi
if [[ "${CHAT4J_DOCTOR_BLOCK_PROBE:-0}" == "1" ]]; then
  printf '%s\n' "$$" > "$CHAT4J_DOCTOR_PROBE_PID_FILE"
  printf '%s\n' "ready" > "$CHAT4J_DOCTOR_PROBE_READY_FIFO"
  trap 'exit 0' TERM INT HUP
  while :; do
    read -r -t 1 _ || true
  done
fi
while [[ $# -gt 0 && "$1" != "-c" ]]; do
  shift
done
if [[ "${1:-}" == "-c" ]]; then
  shift
fi
command="${1:-}"
shift || true
exec /bin/bash -c "$command" "$@"
SHELL
chmod +x "$fake_shell"

secret="secret-do-not-print"
perplexity_secret="second-secret-do-not-print"
export TOGETHER_API_KEY="$secret"
export PERPLEXITY_API_KEY="$perplexity_secret"
export SHELL="$fake_shell"

stdout_file="$test_root/doctor.stdout"
stderr_file="$test_root/doctor.stderr"
set +e
"$repo_root/scripts/chat4j-doctor.sh" --app "$test_root/missing/Chat4J"$'\n'".app" --json --verbose \
  >"$stdout_file" 2>"$stderr_file"
status=$?
set -e
if [[ "$status" -ne 2 ]]; then
  echo "Expected doctor status 2 for a missing app, got $status" >&2
  exit 1
fi

report_dir="$XDG_CONFIG_HOME/chat4j/logs/doctor"
report_file="$(find "$report_dir" -maxdepth 1 -name 'doctor-*.md' -print -quit)"
json_file="$(find "$report_dir" -maxdepth 1 -name 'doctor-*.json' -print -quit)"
if [[ -z "$report_file" || -z "$json_file" ]]; then
  echo "Doctor reports were not created" >&2
  exit 1
fi
if ! grep -q 'TOGETHER_API_KEY' "$report_file"; then
  echo "Together key name was not reported" >&2
  exit 1
fi
if ! grep -q 'PERPLEXITY_API_KEY' "$report_file"; then
  echo "Perplexity key name was not reported" >&2
  exit 1
fi
if ! python3 - "$json_file" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as report:
    json.load(report)
PY
then
  echo "Doctor JSON report is invalid" >&2
  exit 1
fi
for credential in "$secret" "$perplexity_secret"; do
  if grep -R -F -q "$credential" "$stdout_file" "$stderr_file" "$report_dir"; then
    echo "Doctor exposed a credential value" >&2
    exit 1
  fi
done
if find "$XDG_CONFIG_HOME" -type f \
  \( -name 'env-probe-*' -o -name 'doctor-checks-*' -o -name 'codesign-*' -o -name 'spctl-*' -o -name 'xattr-*' -o -name '.doctor-write-test-*' \) \
  | grep -q .; then
  echo "Doctor left temporary probe files behind" >&2
  exit 1
fi

fallback_config="$test_root/fallback-config"
set +e
CHAT4J_DOCTOR_FAIL_INTERACTIVE_PROBE=1 \
  XDG_CONFIG_HOME="$fallback_config" \
  "$repo_root/scripts/chat4j-doctor.sh" --app "$test_root/missing/Chat4J.app" \
  >"$test_root/fallback.stdout" 2>"$test_root/fallback.stderr"
fallback_status=$?
set -e
if [[ "$fallback_status" -ne 2 ]]; then
  echo "Expected fallback probe status 2 for a missing app, got $fallback_status" >&2
  exit 1
fi
fallback_report="$(find "$fallback_config/chat4j/logs/doctor" -maxdepth 1 -name 'doctor-*.md' -print -quit)"
if [[ -z "$fallback_report" ]] || ! grep -q 'Login-only key detection succeeded' "$fallback_report"; then
  echo "Doctor did not use the login-only shell fallback" >&2
  exit 1
fi
if ! grep -q 'TOGETHER_API_KEY' "$fallback_report"; then
  echo "Login-only fallback did not detect provider key names" >&2
  exit 1
fi
for credential in "$secret" "$perplexity_secret"; do
  if grep -R -F -q "$credential" "$test_root/fallback.stdout" "$test_root/fallback.stderr" "$fallback_config"; then
    echo "Login-only fallback exposed a credential value" >&2
    exit 1
  fi
done

probe_pid_file="$test_root/probe.pid"
probe_ready_fifo="$test_root/probe-ready.fifo"
mkfifo "$probe_ready_fifo"
exec 9<>"$probe_ready_fifo"
term_stdout="$test_root/doctor-term.stdout"
term_stderr="$test_root/doctor-term.stderr"
CHAT4J_DOCTOR_BLOCK_PROBE=1 \
  CHAT4J_DOCTOR_PROBE_PID_FILE="$probe_pid_file" \
  CHAT4J_DOCTOR_PROBE_READY_FIFO="$probe_ready_fifo" \
  "$repo_root/scripts/chat4j-doctor.sh" --app "$test_root/missing/Chat4J.app" \
  >"$term_stdout" 2>"$term_stderr" &
doctor_pid=$!

if ! IFS= read -r -t 5 -u 9 probe_ready || [[ "$probe_ready" != "ready" || ! -s "$probe_pid_file" ]]; then
  echo "Blocking probe did not start within five seconds" >&2
  kill -TERM "$doctor_pid" 2>/dev/null || true
  wait "$doctor_pid" || true
  exit 1
fi
exec 9>&-

probe_pid="$(cat "$probe_pid_file")"
kill -TERM "$doctor_pid"
set +e
wait "$doctor_pid"
term_status=$?
set -e
if [[ "$term_status" -ne 143 ]]; then
  echo "Expected TERM status 143, got $term_status" >&2
  exit 1
fi
if kill -0 "$probe_pid" 2>/dev/null; then
  echo "Shell probe survived doctor termination" >&2
  exit 1
fi
if find "$XDG_CONFIG_HOME" -type f \
  \( -name 'env-probe-*' -o -name 'doctor-checks-*' -o -name 'codesign-*' -o -name 'spctl-*' -o -name 'xattr-*' -o -name '.doctor-write-test-*' \) \
  | grep -q .; then
  echo "Doctor left temporary files after TERM" >&2
  exit 1
fi
for credential in "$secret" "$perplexity_secret"; do
  if grep -R -F -q "$credential" "$term_stdout" "$term_stderr" "$report_dir"; then
    echo "Interrupted doctor exposed a credential value" >&2
    exit 1
  fi
done

report_parent_file="$test_root/not-a-directory"
printf '%s\n' "occupied" > "$report_parent_file"
set +e
XDG_CONFIG_HOME="$report_parent_file" \
  "$repo_root/scripts/chat4j-doctor.sh" --app "$test_root/missing/Chat4J.app" \
  >"$test_root/report-dir.stdout" 2>"$test_root/report-dir.stderr"
report_dir_status=$?
set -e
if [[ "$report_dir_status" -ne 2 ]]; then
  echo "Expected report-directory failure status 2, got $report_dir_status" >&2
  exit 1
fi
if ! grep -q 'Could not create Chat4J doctor report directory' "$test_root/report-dir.stderr"; then
  echo "Doctor did not explain the report-directory failure" >&2
  exit 1
fi
if grep -q 'Report written to:' "$test_root/report-dir.stdout"; then
  echo "Doctor falsely reported writing a report" >&2
  exit 1
fi

read_only_config="$test_root/read-only-config"
read_only_report_dir="$read_only_config/chat4j/logs/doctor"
mkdir -p "$read_only_report_dir"
chmod 500 "$read_only_report_dir"
set +e
XDG_CONFIG_HOME="$read_only_config" \
  "$repo_root/scripts/chat4j-doctor.sh" --app "$test_root/missing/Chat4J.app" --json \
  >"$test_root/read-only.stdout" 2>"$test_root/read-only.stderr"
read_only_status=$?
set -e
chmod 700 "$read_only_report_dir"
if [[ "$read_only_status" -ne 2 ]]; then
  echo "Expected non-writable report-directory status 2, got $read_only_status" >&2
  exit 1
fi
if ! grep -q 'Could not write Chat4J doctor report data' "$test_root/read-only.stderr"; then
  echo "Doctor did not explain the non-writable report-directory failure" >&2
  exit 1
fi
if grep -q 'Report written to:' "$test_root/read-only.stdout"; then
  echo "Doctor falsely reported writing into a non-writable directory" >&2
  exit 1
fi

echo "Chat4J doctor hermetic checks passed"
