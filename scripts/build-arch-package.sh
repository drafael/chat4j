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
    echo "Requested Arch package version ($version) does not match pom.xml version ($pom_version)." >&2
    exit 1
fi
if [[ "$(uname -s)" != "Linux" ]] || ! command -v makepkg >/dev/null 2>&1; then
    echo "Arch packages must be built on an Arch Linux host with makepkg." >&2
    exit 1
fi

input_dir="$repo_root/target/jpackage-input-arch"
app_image_dir="$repo_root/target/arch-app-image"
build_dir="$repo_root/target/arch-package-build"
archive_name="chat4j-app-image-${version}.tar.gz"

cd "$repo_root"
mvn -B -ntp -DskipTests verify

rm -rf "$input_dir" "$app_image_dir" "$build_dir"
mkdir -p "$input_dir/tools" "$app_image_dir" "$build_dir"
cp "target/chat4j-${version}.jar" "$input_dir/"
cp target/openhtmltopdf-core-*.jar target/openhtmltopdf-pdfbox-*.jar "$input_dir/"
cp target/classes/build.properties "$input_dir/"
cp scripts/chat4j-doctor.sh "$input_dir/tools/"

# jpackage cannot create an Arch package directly. Its portable app-image is the
# package payload; makepkg then owns the Arch filesystem layout and metadata.
jpackage \
    --input "$input_dir" \
    --name chat4j \
    --main-jar "chat4j-${version}.jar" \
    --main-class com.github.drafael.chat4j.App \
    --type app-image \
    --java-options --enable-preview \
    --icon src/main/resources/icons/icon.png \
    --app-version "$version" \
    --dest "$app_image_dir"

tar -czf "$build_dir/$archive_name" -C "$app_image_dir" chat4j
cp packaging/arch/chat4j.desktop "$build_dir/"
cp src/main/resources/icons/icon.png "$build_dir/chat4j.png"

app_image_sha256="$(sha256sum "$build_dir/$archive_name" | awk '{print $1}')"
desktop_sha256="$(sha256sum "$build_dir/chat4j.desktop" | awk '{print $1}')"
icon_sha256="$(sha256sum "$build_dir/chat4j.png" | awk '{print $1}')"

sed \
    -e "s/@CHAT4J_VERSION@/$version/g" \
    -e "s/@APP_IMAGE_SHA256@/$app_image_sha256/g" \
    -e "s/@DESKTOP_SHA256@/$desktop_sha256/g" \
    -e "s/@ICON_SHA256@/$icon_sha256/g" \
    packaging/arch/PKGBUILD.in > "$build_dir/PKGBUILD"

(
    cd "$build_dir"
    LC_ALL=C makepkg --cleanbuild --clean --force --noconfirm --nosign
    mapfile -t packages < <(makepkg --packagelist)
    if [[ "${#packages[@]}" -ne 1 ]] || [[ ! -f "${packages[0]}" ]]; then
        echo "Expected exactly one Arch package from makepkg." >&2
        exit 1
    fi
    "$repo_root/scripts/verify-linux-package.sh" arch "${packages[0]}" "$version"
    echo "Built Arch package: ${packages[0]}"
)
