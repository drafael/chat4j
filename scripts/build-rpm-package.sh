#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <project-version>" >&2
    exit 2
fi

version="$1"
repo_root="$(cd "$(dirname "$0")/.." && pwd -P)"
pom_version="$(cd "$repo_root" && mvn -q -DforceStdout help:evaluate -Dexpression=project.version)"

if [[ "$version" != "$pom_version" ]]; then
    echo "Requested RPM version ($version) does not match pom.xml version ($pom_version)." >&2
    exit 1
fi
if [[ ! -r /etc/os-release ]]; then
    echo "RPM packages must be built on Fedora." >&2
    exit 1
fi
# shellcheck disable=SC1091
source /etc/os-release
if [[ "${ID:-}" != "fedora" ]] || ! command -v rpmbuild >/dev/null 2>&1; then
    echo "RPM packages must be built on a Fedora host with rpmbuild." >&2
    exit 1
fi
java_major="$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')"
if [[ "$java_major" != "21" ]]; then
    echo "RPM packages require JDK 21, got Java ${java_major:-unknown}." >&2
    exit 1
fi

cd "$repo_root"
rm -f target/dist/*.rpm
mvn -B -ntp -DskipTests \
    -Dchat4j.jpackage.deb.skip=true \
    -Pjpackage-linux,jpackage-on-linux verify

mapfile -t packages < <(find target/dist -maxdepth 1 -type f -name '*.rpm' -print | sort)
if [[ "${#packages[@]}" -ne 1 ]]; then
    echo "Expected exactly one RPM package, found ${#packages[@]}." >&2
    exit 1
fi

scripts/verify-linux-package.sh rpm "${packages[0]}" "$version"
echo "Built RPM package: ${packages[0]}"
