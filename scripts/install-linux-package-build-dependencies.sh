#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]] || [[ "$1" != "rpm" && "$1" != "arch" ]]; then
    echo "Usage: $0 <rpm|arch>" >&2
    exit 2
fi
if [[ "$(id -u)" -ne 0 ]]; then
    echo "Linux package build dependencies must be installed as root." >&2
    exit 1
fi

case "$1" in
    rpm)
        if [[ ! -r /etc/os-release ]]; then
            echo "RPM packages must be built on Fedora." >&2
            exit 1
        fi
        # shellcheck disable=SC1091
        source /etc/os-release
        if [[ "${ID:-}" != "fedora" ]]; then
            echo "RPM packages must be built on Fedora, got: ${ID:-unknown}." >&2
            exit 1
        fi
        dnf install -y \
            alsa-lib findutils freetype glibc gtk3 libX11 libXext libXi \
            libXrender libXtst maven rpm-build webkit2gtk4.1 zlib
        ;;
    arch)
        if [[ ! -r /etc/os-release ]]; then
            echo "Arch packages must be built on Arch Linux." >&2
            exit 1
        fi
        # shellcheck disable=SC1091
        source /etc/os-release
        if [[ "${ID:-}" != "arch" ]]; then
            echo "Arch packages must be built on Arch Linux, got: ${ID:-unknown}." >&2
            exit 1
        fi
        # A full upgrade is required before installing packages on rolling Arch.
        pacman -Syu --needed --noconfirm \
            alsa-lib base-devel freetype2 gcc-libs git glibc gtk3 jdk21-openjdk libx11 \
            libxext libxi libxrender libxtst maven webkit2gtk-4.1 zlib
        if [[ -n "${GITHUB_ENV:-}" && -n "${GITHUB_PATH:-}" ]]; then
            echo "JAVA_HOME=/usr/lib/jvm/java-21-openjdk" >> "$GITHUB_ENV"
            echo "/usr/lib/jvm/java-21-openjdk/bin" >> "$GITHUB_PATH"
        fi
        ;;
esac
