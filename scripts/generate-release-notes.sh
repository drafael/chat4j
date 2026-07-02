#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <tag> <output-file> [owner/repo]" >&2
  exit 2
fi

tag="$1"
output_file="$2"
repository="${3:-}"

if ! git rev-parse --verify --quiet "${tag}^{commit}" >/dev/null; then
  echo "Release tag does not exist: ${tag}" >&2
  exit 1
fi

previous_tag=""
while IFS= read -r candidate; do
  if [[ "$candidate" != "$tag" ]]; then
    previous_tag="$candidate"
    break
  fi
done < <(git tag --merged "$tag" --list 'v[0-9]*' --sort=-v:refname)

range="$tag"
if [[ -n "$previous_tag" ]]; then
  range="${previous_tag}..${tag}"
fi

commit_count="$(git rev-list --count --no-merges "$range")"

mkdir -p "$(dirname "$output_file")"
{
  echo "## What's Changed"
  echo

  if [[ "$commit_count" -eq 0 ]]; then
    echo "- No commit changes found."
  else
    git log --reverse --no-merges --pretty=format:'- %s (%h)' "$range"
    echo
  fi

  echo
  if [[ -n "$repository" && -n "$previous_tag" ]]; then
    echo "**Full Changelog**: https://github.com/${repository}/compare/${previous_tag}...${tag}"
  elif [[ -n "$repository" ]]; then
    echo "**Full Changelog**: https://github.com/${repository}/commits/${tag}"
  fi
} > "$output_file.tmp"

mv "$output_file.tmp" "$output_file"
