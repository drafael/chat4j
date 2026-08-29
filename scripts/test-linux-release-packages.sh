#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd -P)"

python3 - "$repo_root" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
errors = []


def require(condition, message):
    if not condition:
        errors.append(message)


def require_text(text, needle, source):
    require(needle in text, f"{source} must contain {needle!r}")


pom_path = root / "pom.xml"
pom = ET.parse(pom_path).getroot()
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
profiles = {
    profile.findtext("m:id", namespaces=namespace): profile
    for profile in pom.findall("m:profiles/m:profile", namespace)
}
linux_profile = profiles.get("jpackage-linux")
require(linux_profile is not None, "pom.xml must define the jpackage-linux profile")

if linux_profile is not None:
    executions = {
        execution.findtext("m:id", namespaces=namespace): execution
        for execution in linux_profile.findall(".//m:execution", namespace)
    }
    for execution_id, package_type in (
        ("jpackage-linux", "deb"),
        ("jpackage-linux-rpm", "rpm"),
    ):
        execution = executions.get(execution_id)
        require(execution is not None, f"jpackage-linux must define {execution_id}")
        if execution is None:
            continue
        arguments = [
            argument.text or ""
            for argument in execution.findall("m:configuration/m:arguments/m:argument", namespace)
        ]
        pairs = list(zip(arguments, arguments[1:]))
        require(("--type", package_type) in pairs, f"{execution_id} must create --type {package_type}")
        require(("--app-version", "${project.version}") in pairs,
                f"{execution_id} must use the Maven project version")
        require(("--dest", "${project.build.directory}/dist") in pairs,
                f"{execution_id} must write to the shared release dist directory")

arch_recipe = (root / "packaging/arch/PKGBUILD.in").read_text()
for needle in (
    "pkgver=@CHAT4J_VERSION@",
    "arch=('x86_64')",
    "package()",
    "@APP_IMAGE_SHA256@",
    "@DESKTOP_SHA256@",
    "@ICON_SHA256@",
    'ln -s /opt/chat4j/bin/chat4j "${pkgdir}/usr/bin/chat4j"',
):
    require_text(arch_recipe, needle, "packaging/arch/PKGBUILD.in")

arch_builder = (root / "scripts/build-arch-package.sh").read_text()
for needle in (
    "--type app-image",
    'if [[ "$version" != "$pom_version" ]]',
    "packaging/arch/PKGBUILD.in",
    "makepkg --cleanbuild --clean --force --noconfirm --nosign",
):
    require_text(arch_builder, needle, "scripts/build-arch-package.sh")

release_workflow = (root / ".github/workflows/release.yml").read_text()
for needle in (
    "target/dist/*.deb",
    "target/dist/*.rpm",
    "fakeroot dpkg-dev rpm",
    "container: archlinux:base-devel",
    "scripts/build-arch-package.sh",
    "target/arch-package-build/*.pkg.tar.zst",
    "- arch",
    "Duplicate release asset name:",
    "SHA256SUMS.txt",
):
    require_text(release_workflow, needle, ".github/workflows/release.yml")

ci_workflow = (root / ".github/workflows/ci.yml").read_text()
require_text(ci_workflow, "scripts/test-linux-release-packages.sh", ".github/workflows/ci.yml")

if errors:
    print("Linux release package contract failed:", file=sys.stderr)
    for error in errors:
        print(f"  - {error}", file=sys.stderr)
    sys.exit(1)

print("Linux release package contract passed: RPM and Arch artifacts are configured and published.")
PY
