#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]] || [[ "$1" != "rpm" && "$1" != "arch" ]]; then
    echo "Usage: $0 <rpm|arch> <package-path> <project-version>" >&2
    exit 2
fi

package_type="$1"
package_path="$2"
version="$3"

if [[ ! -f "$package_path" ]]; then
    echo "Package does not exist: $package_path" >&2
    exit 1
fi

require_line() {
    local text="$1"
    local expected="$2"
    local description="$3"

    if ! grep -Fqx "$expected" <<< "$text"; then
        echo "Package is missing expected $description: $expected" >&2
        exit 1
    fi
}

case "$package_type" in
    rpm)
        for command in rpm grep; do
            if ! command -v "$command" >/dev/null 2>&1; then
                echo "RPM verification requires $command." >&2
                exit 1
            fi
        done

        name="$(rpm -qp --queryformat '%{NAME}' "$package_path")"
        package_version="$(rpm -qp --queryformat '%{VERSION}' "$package_path")"
        architecture="$(rpm -qp --queryformat '%{ARCH}' "$package_path")"
        license="$(rpm -qp --queryformat '%{LICENSE}' "$package_path")"
        if [[ "$name" != "chat4j" || "$package_version" != "$version" ]]; then
            echo "Unexpected RPM identity: ${name}-${package_version}; expected chat4j-${version}." >&2
            exit 1
        fi
        if [[ "$architecture" != "x86_64" || "$license" != "Apache-2.0" ]]; then
            echo "Unexpected RPM metadata: architecture=${architecture}, license=${license}." >&2
            exit 1
        fi

        package_files="$(rpm -qpl "$package_path")"
        require_line "$package_files" "/opt/chat4j/bin/chat4j" "application launcher"
        require_line "$package_files" "/opt/chat4j/lib/chat4j-chat4j.desktop" "packaged desktop resource"
        require_line "$package_files" "/opt/chat4j/lib/chat4j.png" "packaged application icon"

        install_scripts="$(rpm -qp --scripts "$package_path")"
        if ! grep -F "xdg-desktop-menu install" <<< "$install_scripts" \
            | grep -Fq "/opt/chat4j/lib/chat4j-chat4j.desktop"; then
            echo "RPM install scripts do not register the packaged desktop resource with xdg-desktop-menu." >&2
            exit 1
        fi

        runtime_dependencies="$(rpm -qp --requires "$package_path" \
            | grep -Ev '^(rpmlib\(|/bin/(ba)?sh$)' || true)"
        dependency_count="$(grep -cve '^$' <<< "$runtime_dependencies" || true)"
        if (( dependency_count < 3 )); then
            echo "RPM must declare meaningful runtime dependencies; found ${dependency_count}." >&2
            printf '%s\n' "$runtime_dependencies" >&2
            exit 1
        fi
        if ! grep -Eqi '(alsa-lib|glibc|gtk3|libX11|webkit2gtk|libc\.so|libX11\.so)' \
            <<< "$runtime_dependencies"; then
            echo "RPM dependencies do not include a recognized native runtime library." >&2
            printf '%s\n' "$runtime_dependencies" >&2
            exit 1
        fi
        ;;
    arch)
        for command in bsdtar grep readlink; do
            if ! command -v "$command" >/dev/null 2>&1; then
                echo "Arch verification requires $command." >&2
                exit 1
            fi
        done

        package_info="$(bsdtar -xOf "$package_path" .PKGINFO)"
        require_line "$package_info" "pkgname = chat4j" "package name"
        require_line "$package_info" "pkgver = ${version}-1" "package version"
        require_line "$package_info" "arch = x86_64" "package architecture"
        if ! grep -Fq 'depend = gtk3' <<< "$package_info" \
            || ! grep -Fq 'depend = webkit2gtk-4.1' <<< "$package_info"; then
            echo "Arch package metadata is missing declared GUI runtime dependencies." >&2
            exit 1
        fi

        extract_dir="$(mktemp -d)"
        trap 'rm -rf "$extract_dir"' EXIT
        bsdtar -xf "$package_path" -C "$extract_dir"

        launcher="$extract_dir/opt/chat4j/bin/chat4j"
        desktop_file="$extract_dir/usr/share/applications/chat4j.desktop"
        command_link="$extract_dir/usr/bin/chat4j"
        icon="$extract_dir/usr/share/icons/hicolor/512x512/apps/chat4j.png"
        if [[ ! -x "$launcher" || ! -f "$desktop_file" || ! -L "$command_link" || ! -f "$icon" ]]; then
            echo "Arch package is missing an expected installed path." >&2
            exit 1
        fi
        if [[ "$(readlink "$command_link")" != "/opt/chat4j/bin/chat4j" ]]; then
            echo "Arch command link does not target /opt/chat4j/bin/chat4j." >&2
            exit 1
        fi
        for desktop_entry in \
            "Type=Application" \
            "Name=Chat4J" \
            "Exec=/opt/chat4j/bin/chat4j" \
            "Icon=chat4j" \
            "Terminal=false"; do
            require_line "$(cat "$desktop_file")" "$desktop_entry" "desktop entry"
        done
        ;;
esac

echo "Verified ${package_type} package metadata and contents: $package_path"
