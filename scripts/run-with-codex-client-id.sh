#!/usr/bin/env bash
set -euo pipefail

if ! command -v codex >/dev/null 2>&1; then
  echo "'codex' CLI is not installed or not on PATH."
  exit 1
fi

if ! command -v rg >/dev/null 2>&1; then
  echo "'rg' (ripgrep) is required but not installed."
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "'python3' is required but not installed."
  exit 1
fi

login_output="$({
  python3 - <<'PY'
import subprocess

cmd = ["codex", "login"]
try:
    result = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=8,
        check=False,
    )
    print(result.stdout, end="")
except subprocess.TimeoutExpired as e:
    output = e.stdout or ""
    if isinstance(output, bytes):
        output = output.decode("utf-8", errors="ignore")
    print(output, end="")
PY
} 2>&1)"

client_id="$(printf '%s' "$login_output" \
  | rg -o 'client_id=[^& ]+' \
  | head -1 \
  | cut -d= -f2 \
  | python3 -c 'import sys, urllib.parse; print(urllib.parse.unquote(sys.stdin.read().strip()))')"

if [[ -z "$client_id" ]]; then
  echo "Failed to extract Codex OAuth client_id from 'codex login' output."
  echo "Tip: run 'codex login' manually once and check the printed auth URL."
  exit 1
fi

export CHAT4J_CODEX_OAUTH_CLIENT_ID="$client_id"
echo "Using Codex OAuth client ID: $CHAT4J_CODEX_OAUTH_CLIENT_ID"

mvn exec:java
