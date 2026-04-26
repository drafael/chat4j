#!/usr/bin/env bash
set -euo pipefail

if [[ "${CALVER_HOOK_RUNNING:-0}" == "1" ]]; then
  exit 0
fi
export CALVER_HOOK_RUNNING=1

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

buildnumber_file=".buildnumber"

current_year="$(date +%y)"
current_month_raw="$(date +%m)"
current_month="$((10#$current_month_raw))"
current_month_key="${current_year}.${current_month}"

stored_month=""
stored_count=""
if [[ -f "$buildnumber_file" ]]; then
  while IFS='=' read -r key value; do
    case "$key" in
      month) stored_month="$value" ;;
      count) stored_count="$value" ;;
    esac
  done < "$buildnumber_file"
fi

if [[ "$stored_month" == "$current_month_key" ]] && [[ "$stored_count" =~ ^[0-9]+$ ]]; then
  next_count=$((stored_count + 1))
else
  next_count=0
fi

new_version="${current_month_key}.${next_count}"

echo "[calver] Updating project version to ${new_version}"

mvn -q org.codehaus.mojo:versions-maven-plugin:2.17.1:set \
  -DnewVersion="${new_version}" \
  -DgenerateBackupPoms=false \
  -DprocessAllModules=true

printf "month=%s\ncount=%s\n" "$current_month_key" "$next_count" > "$buildnumber_file"

git add pom.xml "$buildnumber_file"
