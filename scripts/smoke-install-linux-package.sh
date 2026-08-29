#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]] || [[ "$1" != "rpm" && "$1" != "arch" ]]; then
    echo "Usage: $0 <rpm|arch> <package-path> <project-version>" >&2
    exit 2
fi
if [[ "$(id -u)" -ne 0 ]]; then
    echo "Linux package smoke installation must run as root." >&2
    exit 1
fi

package_type="$1"
package_path="$(realpath "$2")"
version="$3"

if [[ ! -f "$package_path" ]]; then
    echo "Package does not exist: $package_path" >&2
    exit 1
fi

case "$package_type" in
    rpm)
        dnf install -y "$package_path"

        installed_name="$(rpm -q --queryformat '%{NAME}' chat4j)"
        installed_version="$(rpm -q --queryformat '%{VERSION}' chat4j)"
        installed_architecture="$(rpm -q --queryformat '%{ARCH}' chat4j)"
        if [[ "$installed_name" != "chat4j" || "$installed_version" != "$version" || "$installed_architecture" != "x86_64" ]]; then
            echo "Unexpected installed RPM identity: ${installed_name}-${installed_version}.${installed_architecture}." >&2
            exit 1
        fi

        launcher="/opt/chat4j/bin/chat4j"
        desktop_file="/opt/chat4j/lib/chat4j-chat4j.desktop"
        icon="/opt/chat4j/lib/chat4j.png"
        registered_desktop_file="/usr/share/applications/chat4j-chat4j.desktop"
        if [[ ! -x "$launcher" || ! -f "$desktop_file" || ! -f "$icon" || ! -f "$registered_desktop_file" ]]; then
            echo "Installed RPM is missing its launcher, desktop resource, icon, or desktop registration." >&2
            exit 1
        fi
        ;;
    arch)
        pacman -U --noconfirm "$package_path"

        installed_identity="$(pacman -Q chat4j)"
        if [[ "$installed_identity" != "chat4j ${version}-1" ]]; then
            echo "Unexpected installed Arch package identity: $installed_identity." >&2
            exit 1
        fi

        launcher="/opt/chat4j/bin/chat4j"
        desktop_file="/usr/share/applications/chat4j.desktop"
        icon="/usr/share/icons/hicolor/512x512/apps/chat4j.png"
        command_link="/usr/bin/chat4j"
        if [[ ! -x "$launcher" || ! -f "$desktop_file" || ! -f "$icon" || ! -L "$command_link" ]]; then
            echo "Installed Arch package is missing its launcher, desktop resource, icon, or command link." >&2
            exit 1
        fi
        if [[ "$(readlink "$command_link")" != "$launcher" ]]; then
            echo "Installed Arch command link does not target $launcher." >&2
            exit 1
        fi
        ;;
esac

for command in timeout xvfb-run; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Linux package launch smoke requires $command." >&2
        exit 1
    fi
done

smoke_home="$(mktemp -d)"
launch_log="$(mktemp)"
trap 'rm -rf "$smoke_home" "$launch_log"' EXIT

launch_started_at="$(date +%s)"
set +e
HOME="$smoke_home" \
XDG_CACHE_HOME="$smoke_home/.cache" \
XDG_CONFIG_HOME="$smoke_home/.config" \
XDG_DATA_HOME="$smoke_home/.local/share" \
JAVA_TOOL_OPTIONS="-Duser.home=$smoke_home" \
    timeout --kill-after=5s 15s xvfb-run -a "$launcher" >"$launch_log" 2>&1
launch_status=$?
set -e
launch_elapsed_seconds=$(( $(date +%s) - launch_started_at ))

timed_out=false
if [[ "$launch_status" -eq 124 || ( "$launch_status" -eq 137 && "$launch_elapsed_seconds" -ge 15 ) ]]; then
    timed_out=true
elif [[ "$launch_status" -ne 0 ]]; then
    echo "Installed Chat4J launcher exited with status $launch_status before the smoke timeout." >&2
    cat "$launch_log" >&2
    exit 1
fi

if [[ "$timed_out" == true ]]; then
    echo "Installed Chat4J remained running for the 15-second smoke window."
else
    echo "Installed Chat4J launcher exited successfully during the smoke window."
fi

echo "Smoke-installed and launched ${package_type} package: $package_path"
