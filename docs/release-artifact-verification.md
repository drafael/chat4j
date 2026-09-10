# Release artifact verification

Chat4J release artifacts are currently unsigned and not notarized. macOS Gatekeeper and Windows SmartScreen may warn or block the first launch. Download artifacts only from the [official GitHub Releases page](https://github.com/drafael/chat4j/releases) and verify their SHA-256 checksums before bypassing an operating-system warning.

## Verify a checksum

Download `SHA256SUMS.txt` alongside the artifact, then run the command for your operating system from the download directory.

### macOS or Linux

Replace the filename with the artifact you downloaded:

```bash
artifact="Chat4J-<version>.dmg"
awk -v artifact="$artifact" '$2 == artifact { print }' SHA256SUMS.txt | shasum -a 256 -c -
```

A valid artifact reports `OK`. Do not run it if the checksum is missing or does not match.

### Windows PowerShell

Replace the filename with the artifact you downloaded:

```powershell
$artifact = "Chat4J-<version>.msi"
$parts = (Get-Content SHA256SUMS.txt | Where-Object { $_ -match "\s$([regex]::Escape($artifact))$" }) -split '\s+', 2
$actual = (Get-FileHash -Algorithm SHA256 $artifact).Hash.ToLowerInvariant()
if ($actual -ne $parts[0]) { throw "Checksum mismatch: $artifact" }
```

No output means the checksum matched. Do not run the artifact if PowerShell reports a mismatch.

## First launch on macOS

If macOS says Chat4J cannot be opened or is from an unidentified developer:

1. Open **Finder** and locate `Chat4J.app`.
2. Control-click the app and choose **Open**.
3. Click **Open** in the confirmation dialog.

If it remains blocked, open **System Settings → Privacy & Security**, find the Gatekeeper message for Chat4J, and click **Open Anyway**.

Terminal alternative:

```bash
xattr -dr com.apple.quarantine /Applications/Chat4J.app
open /Applications/Chat4J.app
```

Adjust the path if Chat4J is installed elsewhere.

## First launch on Windows

If Windows SmartScreen appears:

1. Click **More info**.
2. Click **Run anyway**.

If Windows marks the downloaded file as blocked:

1. Right-click the `.msi` or `.exe` and choose **Properties**.
2. On the **General** tab, check **Unblock** if present.
3. Click **Apply**, then run the installer again.
