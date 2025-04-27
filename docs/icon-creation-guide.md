# Chat4J Icon Creation Guide

A step-by-step guide for creating and integrating a macOS-native-looking app icon for a Java Swing desktop application.

## Prerequisites

- **ImageMagick 7+** (`brew install imagemagick`)
- **iconutil** (built into macOS)
- Access to an AI image generator (DALL-E, Midjourney, ChatGPT, etc.)

## Step 1: Generate the Icon Artwork

Generate **only the artwork** (no background shape) using an AI image generator.

### Prompt

> Minimal flat icon, a rounded speech bubble outline containing a small coffee cup silhouette, dark gray bubble and black cup, transparent background, no background shape, no rounded rectangle, no shadow, no border, no gradients, no text, square canvas, centered, PNG with alpha transparency, flat 2D only

### Important Notes on AI-Generated "Transparency"

AI image generators almost always produce **fake transparency** — they render a gray/white checkerboard pattern as actual pixels instead of real alpha transparency. You **must** fix this in post-processing (Step 2).

Save the generated image as `icon-1024.png` in the project root.

## Step 2: Fix Fake Transparency

AI generators bake a checkerboard pattern into the image instead of using a real alpha channel. Verify with:

```bash
identify -verbose icon-1024.png | grep -E "Type:|Channels:"
```

If you see `Type: TrueColor` and `Channels: 3` (or `Palette` with no alpha), transparency is fake.

### Fix: Flood-Fill Corners with White

Replace the fake checkerboard with solid white so we can apply a proper mask later:

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

The `-draw "color 512,400 floodfill"` line fills any fake-transparent area **inside** the artwork (e.g., inside a speech bubble). Adjust coordinates if your artwork has different interior transparent regions.

### Verify

```bash
magick icon-1024.png -crop 1x1+0+0 txt:- | tail -1
# Should show: #FFFFFF white
```

## Step 3: Apply a macOS Squircle Mask

macOS app icons use a "squircle" (continuous corner curve) shape. Java apps don't get this automatically — you must bake it into the icon.

### Create the Mask and Apply

```bash
magick icon-1024.png \
  -alpha set \
  \( -size 1024x1024 xc:black -fill white -draw "roundrectangle 0,0,1023,1023,180,180" \) \
  -compose CopyOpacity -composite \
  PNG32:icon-1024.png
```

### Verify Real Alpha

```bash
# Corner should be transparent (alpha = 0, shown as black)
magick icon-1024.png -crop 1x1+0+0 -alpha extract txt:- | tail -1
# Expected: #000000 black

# Center should be opaque (alpha = 255, shown as white)
magick icon-1024.png -crop 1x1+512+512 -alpha extract txt:- | tail -1
# Expected: #FFFFFF white
```

## Step 4: Scale to Match Native macOS Icon Size

Native macOS icons have padding between the squircle and canvas edge. Without this, your icon will appear larger than system apps in the dock.

### Scale to ~82% (840px on 1024 canvas)

```bash
magick \
  -size 1024x1024 xc:none \
  \( icon-1024.png -trim +repage -resize 840x840 \) \
  -gravity center -composite \
  PNG32:icon-1024.png
```

### Size Reference

| Scale | px on 1024 | Result |
|-------|-----------|--------|
| 90%   | 920       | Too large — visibly bigger than native icons |
| 88%   | 900       | Slightly large |
| 84%   | 860       | Close but still slightly large |
| **82%** | **840** | **Matches native macOS icons (Things, Telegram, Notes)** |
| 80%   | 820       | Slightly small |

## Step 5: Generate All Required Sizes

### PNG for Java `setIconImage()`

```bash
magick icon-1024.png -resize 512x512 PNG32:src/main/resources/icons/icon.png
```

**Critical:** Always use the `PNG32:` prefix to force 4-channel output (RGBA). Without it, ImageMagick may strip the alpha channel.

### ICO for Windows

```bash
magick icon-1024.png \
  -define icon:auto-resize=16,32,48,256 \
  src/main/resources/icons/icon.ico
```

### ICNS for macOS

```bash
mkdir -p target/icon.iconset

for pair in \
  "16 icon_16x16" \
  "32 icon_16x16@2x" \
  "32 icon_32x32" \
  "64 icon_32x32@2x" \
  "128 icon_128x128" \
  "256 icon_128x128@2x" \
  "256 icon_256x256" \
  "512 icon_256x256@2x" \
  "512 icon_512x512" \
  "1024 icon_512x512@2x"; do
  size=$(echo $pair | cut -d' ' -f1)
  name=$(echo $pair | cut -d' ' -f2)
  magick icon-1024.png -resize ${size}x${size} PNG32:target/icon.iconset/${name}.png
done

iconutil -c icns target/icon.iconset -o src/main/resources/icons/icon.icns
rm -rf target/icon.iconset
```

**Do not use `sips`** for resizing — it strips the alpha channel.

## Step 6: Wire the Icon into the Java App

### MainFrame.java

```java
// After setDefaultCloseOperation / setMinimumSize
var iconImage = new ImageIcon(getClass().getResource("/icons/icon.png")).getImage();
setIconImage(iconImage);
if (Taskbar.isTaskbarSupported()) {
    var taskbar = Taskbar.getTaskbar();
    if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
        taskbar.setIconImage(iconImage);
    }
}
```

- `setIconImage()` sets the window/taskbar icon
- `Taskbar.setIconImage()` (Java 9+) sets the **macOS dock icon** — required for `mvn exec:java` since it runs in-process

### pom.xml — jpackage Profiles

Add `--icon` argument to each platform's jpackage profile:

- **macOS:** `--icon ${project.basedir}/src/main/resources/icons/icon.icns`
- **Windows:** `--icon ${project.basedir}/src/main/resources/icons/icon.ico`
- **Linux:** `--icon ${project.basedir}/src/main/resources/icons/icon.png`

## File Structure

```
project-root/
  icon-1024.png                          # Source icon (keep for future edits)
  src/main/resources/icons/
    icon.png                             # 512x512 RGBA — used by Java at runtime
    icon.icns                            # macOS bundle (jpackage)
    icon.ico                             # Windows (jpackage)
```

## Troubleshooting

### Checkerboard pattern visible in dock
The PNG has fake transparency (gray pixels, not real alpha). Re-run Step 2 and Step 3. Verify with:
```bash
identify -verbose icon.png | grep -E "Type:|Channels:"
# Must show: Type: TrueColorAlpha, Channels: 4
```

### Icon appears but corners are white/opaque
Alpha channel was stripped during resize. Always use `PNG32:` prefix with ImageMagick and avoid `sips`.

### Icon is too big/small compared to native apps
Adjust the scale in Step 4. Use 840px as baseline and compare side-by-side with native icons in the dock at enlarged dock size.

### Dock icon shows default Java icon
Ensure `Taskbar.setIconImage()` is called in addition to `setIconImage()`. The Taskbar API is required for the macOS dock when running via `mvn exec:java`.
