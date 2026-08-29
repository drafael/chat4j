#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd -P)"

python3 - "$repo_root" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
errors = []


def require(condition, message):
    if not condition:
        errors.append(message)


def require_text(text, needle, source):
    require(needle in text, f"{source} must contain {needle!r}")


def workflow_job(workflow, job_id, source):
    match = re.search(
        rf"(?ms)^  {re.escape(job_id)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)",
        workflow,
    )
    require(match is not None, f"{source} must define the {job_id} job")
    return match.group(0) if match is not None else ""


def container_image(job):
    match = re.search(r"(?m)^    container:\s*([^\s#]+)", job)
    return match.group(1) if match is not None else None


def shell_case(script, case_name, source):
    match = re.search(
        rf"(?ms)^    {re.escape(case_name)}\)\n(.*?)^        ;;\n",
        script,
    )
    require(match is not None, f"{source} must define the {case_name} case")
    return match.group(1) if match is not None else ""


def job_needs(job):
    match = re.search(r"(?ms)^    needs:\n((?:      - [^\n]+\n)+)", job)
    return re.findall(r"(?m)^      - (.+)$", match.group(1)) if match is not None else []


pom = ET.parse(root / "pom.xml").getroot()
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
    for execution_id, package_type, skip_property in (
        ("jpackage-linux", "deb", "${chat4j.jpackage.deb.skip}"),
        ("jpackage-linux-rpm", "rpm", "${chat4j.jpackage.rpm.skip}"),
    ):
        execution = executions.get(execution_id)
        require(execution is not None, f"jpackage-linux must define {execution_id}")
        if execution is None:
            continue
        configuration = execution.find("m:configuration", namespace)
        skip = configuration.findtext("m:skip", namespaces=namespace)
        arguments = [
            argument.text or ""
            for argument in configuration.findall("m:arguments/m:argument", namespace)
        ]
        pairs = set(zip(arguments, arguments[1:]))
        require(skip == skip_property, f"{execution_id} must use {skip_property}")
        require(("--type", package_type) in pairs, f"{execution_id} must create --type {package_type}")
        require(("--app-version", "${project.version}") in pairs,
                f"{execution_id} must use the Maven project version")
        require(("--dest", "${project.build.directory}/dist") in pairs,
                f"{execution_id} must write to the shared release dist directory")

linux_activation = profiles.get("jpackage-on-linux")
require(linux_activation is not None, "pom.xml must define jpackage-on-linux")
if linux_activation is not None:
    properties = linux_activation.find("m:properties", namespace)
    require(properties.findtext("m:chat4j.jpackage.deb.skip", namespaces=namespace) == "false",
            "jpackage-on-linux must enable DEB generation by default")
    require(properties.findtext("m:chat4j.jpackage.rpm.skip", namespaces=namespace) == "false",
            "jpackage-on-linux must enable RPM generation by default")

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
    'verify-linux-package.sh" arch',
):
    require_text(arch_builder, needle, "scripts/build-arch-package.sh")

rpm_builder = (root / "scripts/build-rpm-package.sh").read_text()
for needle in (
    '"${ID:-}" != "fedora"',
    '"$java_major" != "21"',
    "-Dchat4j.jpackage.deb.skip=true",
    "-Pjpackage-linux,jpackage-on-linux verify",
    "scripts/verify-linux-package.sh rpm",
):
    require_text(rpm_builder, needle, "scripts/build-rpm-package.sh")

verifier = (root / "scripts/verify-linux-package.sh").read_text()
for needle in (
    "rpm -qp --requires",
    "RPM must declare meaningful runtime dependencies",
    'require_line "$package_files" "/opt/chat4j/bin/chat4j"',
    'require_line "$package_files" "/opt/chat4j/lib/chat4j-chat4j.desktop"',
    'require_line "$package_files" "/opt/chat4j/lib/chat4j.png"',
    'install_scripts="$(rpm -qp --scripts "$package_path")"',
    'grep -F "xdg-desktop-menu install"',
    'package_info="$(bsdtar -xOf "$package_path" .PKGINFO)"',
    '"Exec=/opt/chat4j/bin/chat4j"',
    '"$(readlink "$command_link")" != "/opt/chat4j/bin/chat4j"',
):
    require_text(verifier, needle, "scripts/verify-linux-package.sh")

smoke_installer_path = root / "scripts/smoke-install-linux-package.sh"
require(smoke_installer_path.is_file(), "scripts/smoke-install-linux-package.sh must exist")
smoke_installer = smoke_installer_path.read_text() if smoke_installer_path.is_file() else ""
for needle in (
    'dnf install -y "$package_path"',
    'pacman -U --noconfirm "$package_path"',
    "rpm -q --queryformat '%{NAME}' chat4j",
    'installed_identity="$(pacman -Q chat4j)"',
    'desktop_file="/opt/chat4j/lib/chat4j-chat4j.desktop"',
    'icon="/opt/chat4j/lib/chat4j.png"',
    'local_registered_desktop_file="/usr/local/share/applications/chat4j-chat4j.desktop"',
    'system_registered_desktop_file="/usr/share/applications/chat4j-chat4j.desktop"',
    '( ! -f "$local_registered_desktop_file" && ! -f "$system_registered_desktop_file" )',
    'desktop_file="/usr/share/applications/chat4j.desktop"',
    'icon="/usr/share/icons/hicolor/512x512/apps/chat4j.png"',
    'HOME="$smoke_home"',
    'timeout --kill-after=5s 15s xvfb-run -a "$launcher"',
    '"$launch_status" -eq 124',
    '"$launch_status" -eq 137 && "$launch_elapsed_seconds" -ge 15',
    'elif [[ "$launch_status" -ne 0 ]]',
):
    require_text(smoke_installer, needle, "scripts/smoke-install-linux-package.sh")

installer_source = "scripts/install-linux-package-build-dependencies.sh"
installer = (root / installer_source).read_text()
rpm_installer = shell_case(installer, "rpm", installer_source)
arch_installer = shell_case(installer, "arch", installer_source)
arch_dependencies = set(re.findall(r"'([^']+)'", re.search(
    r"(?ms)^depends=\(\n(.*?)^\)", arch_recipe
).group(1)))
for dependency in arch_dependencies:
    require(re.search(rf"(?<![A-Za-z0-9_.-]){re.escape(dependency)}(?![A-Za-z0-9_.-])", arch_installer) is not None,
            f"dependency installer Arch case must install declared dependency {dependency}")
require_text(arch_installer, "pacman -Syu --needed --noconfirm", "dependency installer Arch case")
require(re.search(r"(?<![A-Za-z0-9_.-])base-devel(?![A-Za-z0-9_.-])", arch_installer) is not None,
        "dependency installer Arch case must install the base-devel toolchain")
require_text(arch_installer, "jdk21-openjdk", "dependency installer Arch case")
require_text(rpm_installer, "rpm-build", "dependency installer RPM case")
require_text(rpm_installer, "xdg-utils", "dependency installer RPM case")
require_text(rpm_installer, "xorg-x11-server-Xvfb", "dependency installer RPM case")
require_text(rpm_installer, "xorg-x11-xauth", "dependency installer RPM case")
require_text(arch_installer, "xorg-server-xvfb", "dependency installer Arch case")
require_text(arch_installer, "xorg-xauth", "dependency installer Arch case")
require("java-21-openjdk-devel" not in rpm_installer,
        "dependency installer RPM case must not request unavailable Fedora 44 java-21-openjdk-devel")
java_home_export = 'echo "JAVA_HOME=/usr/lib/jvm/java-21-openjdk" >> "$GITHUB_ENV"'
java_path_export = 'echo "/usr/lib/jvm/java-21-openjdk/bin" >> "$GITHUB_PATH"'
require_text(arch_installer, java_home_export, "dependency installer Arch case")
require_text(arch_installer, java_path_export, "dependency installer Arch case")
require(installer.count(java_home_export) == 1 and installer.count(java_path_export) == 1,
        "dependency installer must export the distro JDK environment only for Arch")
for environment_token in ("GITHUB_ENV", "GITHUB_PATH", "/usr/lib/jvm/java-21-openjdk"):
    require(installer.count(environment_token) == arch_installer.count(environment_token),
            f"dependency installer must restrict {environment_token} handling to the Arch case")
require("GITHUB_ENV" not in rpm_installer and "GITHUB_PATH" not in rpm_installer,
        "dependency installer RPM case must preserve the setup-java JAVA_HOME and PATH")

release_path = root / ".github/workflows/release.yml"
release_workflow = release_path.read_text()
native_job = workflow_job(release_workflow, "native", str(release_path.relative_to(root)))
rpm_job = workflow_job(release_workflow, "rpm", str(release_path.relative_to(root)))
arch_job = workflow_job(release_workflow, "arch", str(release_path.relative_to(root)))
publish_job = workflow_job(release_workflow, "publish", str(release_path.relative_to(root)))

require_text(native_job, "name: Linux DEB", "release native job")
require_text(native_job, "os: ubuntu-latest", "release native job")
require_text(native_job, "-Dchat4j.jpackage.rpm.skip=true", "release native job")
require_text(native_job, "target/dist/*.deb", "release native job")
require("target/dist/*.rpm" not in native_job, "release native job must not build or upload RPMs on Ubuntu")

setup_java_21 = """uses: actions/setup-java@v5.7.0
        with:
          distribution: temurin
          java-version: '21'
          cache: maven"""
rpm_dependency_command = "scripts/install-linux-package-build-dependencies.sh rpm"

require(container_image(rpm_job) == "fedora:44", "release RPM job must use the supported Fedora 44 image")
require_text(rpm_job, setup_java_21, "release RPM job")
require_text(rpm_job, rpm_dependency_command, "release RPM job")
if setup_java_21 in rpm_job and rpm_dependency_command in rpm_job:
    require(rpm_job.index(setup_java_21) < rpm_job.index(rpm_dependency_command),
            "release RPM job must set up Temurin JDK 21 before installing Fedora dependencies")
require_text(rpm_job, "scripts/build-rpm-package.sh", "release RPM job")
require_text(rpm_job, "target/dist/*.rpm", "release RPM job")
require_text(rpm_job, "if-no-files-found: error", "release RPM job")

release_arch_image = container_image(arch_job)
require(re.fullmatch(r"archlinux:base-devel-[0-9]{8}\.[0-9]+\.[0-9]+", release_arch_image or "") is not None,
        "release Arch job must use an official dated base-devel image")
require_text(arch_job, "scripts/install-linux-package-build-dependencies.sh arch", "release Arch job")
require("actions/setup-java@" not in arch_job,
        "release Arch job must use the distro JDK rather than setup-java")
require_text(arch_job, "runuser -u builder", "release Arch job")
require_text(arch_job, "scripts/build-arch-package.sh", "release Arch job")
require_text(arch_job, "target/arch-package-build/*.pkg.tar.zst", "release Arch job")
require_text(arch_job, "if-no-files-found: error", "release Arch job")

required_publish_jobs = {"metadata", "jar", "native", "rpm", "arch"}
require(set(job_needs(publish_job)) == required_publish_jobs,
        "publish job must atomically require metadata, jar, native, RPM, and Arch jobs")
require_text(publish_job, "Duplicate release asset name:", "release publish job")
require_text(publish_job, "SHA256SUMS.txt", "release publish job")
require_text(publish_job, "fail_on_unmatched_files: true", "release publish job")

smoke_path = root / ".github/workflows/linux-package-smoke.yml"
require(smoke_path.is_file(), ".github/workflows/linux-package-smoke.yml must exist")
smoke_workflow = smoke_path.read_text() if smoke_path.is_file() else ""
smoke_header = smoke_workflow.split("permissions:", 1)[0]
require(re.search(r"(?m)^  pull_request:\n    paths:\n", smoke_header) is not None,
        "Linux package smoke workflow must use pull-request path filters")
require_text(smoke_header, "scripts/smoke-install-linux-package.sh", "Linux package smoke path filters")
require(re.search(r"(?m)^  push:", smoke_header) is None,
        "Linux package smoke workflow must not publish or run on pushes")
require_text(smoke_workflow, "permissions:\n  contents: read", "Linux package smoke workflow")
require("upload-artifact" not in smoke_workflow and "action-gh-release" not in smoke_workflow,
        "Linux package smoke workflow must be non-publishing")

smoke_rpm_job = workflow_job(smoke_workflow, "rpm", str(smoke_path.relative_to(root)))
smoke_arch_job = workflow_job(smoke_workflow, "arch", str(smoke_path.relative_to(root)))
require(container_image(smoke_rpm_job) == container_image(rpm_job),
        "release and smoke RPM jobs must use the same Fedora image")
require_text(smoke_rpm_job, setup_java_21, "smoke RPM job")
require_text(smoke_rpm_job, rpm_dependency_command, "smoke RPM job")
if setup_java_21 in smoke_rpm_job and rpm_dependency_command in smoke_rpm_job:
    require(smoke_rpm_job.index(setup_java_21) < smoke_rpm_job.index(rpm_dependency_command),
            "smoke RPM job must set up Temurin JDK 21 before installing Fedora dependencies")
require(container_image(smoke_arch_job) == release_arch_image,
        "release and smoke Arch jobs must use the same dated image")
require("actions/setup-java@" not in smoke_arch_job,
        "smoke Arch job must use the distro JDK rather than setup-java")
for command, release_job, smoke_job, description in (
    (rpm_dependency_command, rpm_job, smoke_rpm_job, "RPM dependency command"),
    ("scripts/build-rpm-package.sh", rpm_job, smoke_rpm_job, "RPM build command"),
    ("scripts/install-linux-package-build-dependencies.sh arch", arch_job, smoke_arch_job, "Arch dependency command"),
    ("scripts/build-arch-package.sh", arch_job, smoke_arch_job, "Arch build command"),
):
    require(command in release_job and command in smoke_job,
            f"release and smoke jobs must share the {description}")
require_text(smoke_arch_job, "runuser -u builder", "smoke Arch job")
require_text(
    smoke_rpm_job,
    'scripts/smoke-install-linux-package.sh rpm target/dist/*.rpm',
    "smoke RPM job",
)
require_text(
    smoke_arch_job,
    'scripts/smoke-install-linux-package.sh arch target/arch-package-build/*.pkg.tar.zst',
    "smoke Arch job",
)

readme = (root / "README.md").read_text()
require_text(readme, "sudo scripts/install-linux-package-build-dependencies.sh arch", "README.md")
require_text(readme, "Never run the build script or `makepkg` as root", "README.md")
require_text(readme, "Publication is deliberately atomic", "README.md")

ci_workflow = (root / ".github/workflows/ci.yml").read_text()
require_text(ci_workflow, "scripts/test-linux-release-packages.sh", ".github/workflows/ci.yml")

if errors:
    print("Linux release package contract failed:", file=sys.stderr)
    for error in errors:
        print(f"  - {error}", file=sys.stderr)
    sys.exit(1)

print("Linux release package contract passed: distro-native RPM and Arch builds are verified and atomically published.")
PY
