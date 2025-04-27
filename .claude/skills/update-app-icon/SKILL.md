---
name: update-app-icon
description: Process and integrate app icons for the Java Swing desktop app with proper macOS dock appearance. Use when the user wants to change/update the app icon, has a new icon source image, or the icon appears broken in the dock (wrong size, no transparency, checkerboard artifacts).
allowed-tools: Read, Bash, Glob, Grep, Write, Edit
---

# Update App Icon

Process and integrate app icons for Chat4J with proper macOS-native appearance.

## Context

- Java 21 Swing app using FlatLaf
- Icons live in `src/main/resources/icons/` (icon.png, icon.ico, icon.icns)
- Source icon is `icon-1024.png` in project root
- Icon is loaded in `src/main/java/com/github/drafael/chat4j/MainFrame.java` via `setIconImage()` and `Taskbar.setIconImage()`
- jpackage profiles in `pom.xml` reference icons per platform (--icon flag)
- Full developer guide at `docs/icon-creation-guide.md`

## Processing Pipeline

When given a source image (AI-generated or otherwise), apply these steps in order:

### 1. Fix Fake Transparency

AI generators produce fake checkerboard transparency (gray pixels, not real alpha). Always verify first:

```bash
identify -verbose icon-1024.png | grep -E "Type:|Channels:"
```

If alpha is missing or fake, flood-fill all transparent-looking areas with solid white:

```bash
magick icon-1024.png \
  -alpha off \
  -fuzz 30% -fill white \
  -draw "color 0,0 floodfill" \
  -draw "color 1023,0 floodfill" \
  -draw "color 0,1023 floodfill" \
  -draw "color 1023,1023 floodfill" \
  -draw "color 512,400 floodfill" \
  PNG32:icon-1024.png
```

Adjust the interior flood-fill coordinate (512,400) based on artwork layout.

### 2. Apply Squircle Mask

```bash
magick icon-1024.png \
  -alpha set \
  \( -size 1024x1024 xc:black -fill white -draw "roundrectangle 0,0,1023,1023,180,180" \) \
  -compose CopyOpacity -composite \
  PNG32:icon-1024.png
```

### 3. Scale to Match Native macOS Icons

840px on a 1024 canvas matches native apps (Things, Telegram, Notes):

```bash
magick \
  -size 1024x1024 xc:none \
  \( icon-1024.png -trim +repage -resize 840x840 \) \
  -gravity center -composite \
  PNG32:icon-1024.png
```

### 4. Generate All Sizes

**Always use `PNG32:` prefix** to preserve alpha. **Never use `sips`** — it strips alpha.

```bash
# PNG
magick icon-1024.png -resize 512x512 PNG32:src/main/resources/icons/icon.png

# ICO
magick icon-1024.png -define icon:auto-resize=16,32,48,256 src/main/resources/icons/icon.ico

# ICNS
mkdir -p target/icon.iconset
for pair in "16 icon_16x16" "32 icon_16x16@2x" "32 icon_32x32" "64 icon_32x32@2x" "128 icon_128x128" "256 icon_128x128@2x" "256 icon_256x256" "512 icon_256x256@2x" "512 icon_512x512" "1024 icon_512x512@2x"; do
  size=$(echo $pair | cut -d' ' -f1)
  name=$(echo $pair | cut -d' ' -f2)
  magick icon-1024.png -resize ${size}x${size} PNG32:target/icon.iconset/${name}.png
done
iconutil -c icns target/icon.iconset -o src/main/resources/icons/icon.icns
rm -rf target/icon.iconset
```

### 5. Verify

```bash
# Must show TrueColorAlpha + 4 channels
identify -verbose src/main/resources/icons/icon.png | grep -E "Type:|Channels:"

# Corner must be transparent (black = alpha 0)
magick src/main/resources/icons/icon.png -crop 1x1+0+0 -alpha extract txt:- | tail -1

# Center must be opaque (white = alpha 255)
magick src/main/resources/icons/icon.png -crop 1x1+256+256 -alpha extract txt:- | tail -1
```

## Key Rules

- **AI generators always fake transparency** — never trust checkerboard patterns, always verify with `identify` and fix with flood-fill + mask
- **`sips` strips alpha channels** — always use `magick` with `PNG32:` prefix
- **macOS dock sizing**: native icons use ~82% of the 1024 canvas (840px). Without padding, Java app icons appear larger than system apps
- **`Taskbar.setIconImage()`** is required for macOS dock icon when running via `mvn exec:java` (in-process execution)
- **`setIconImage()`** alone only sets the window title bar icon, not the dock icon
- **jpackage `--icon`** needs platform-specific formats: .icns (mac), .ico (win), .png (linux)
