# UI and Platform Notes

## macOS HiDPI fonts

Chat4J uses FlatLaf. On macOS Retina displays, using `deriveFont()` on FlatLaf composite fonts can break glyph layout in labels, list renderers, popups, and tabs.

Avoid:

```java
label.setFont(label.getFont().deriveFont(Font.BOLD, 18f));
label.putClientProperty("FlatLaf.style", "font: bold +5");
```

Use `Fonts.of(style, size)` instead:

```java
label.setFont(Fonts.of(Font.BOLD, 18));
```

`Fonts.of()` uses `.AppleSystemUIFont` on macOS and FlatLaf `defaultFont` on other platforms. This keeps renderer/popup text stable while preserving platform look.

Rule: when manually setting fonts in Chat4J Swing code, use `com.github.drafael.chat4j.util.Fonts`.

## Window placement

Chat4J persists main-window bounds in the settings file, including monitor bounds/ID metadata when available. Restore is monitor-aware: it prefers the saved display, supports negative coordinates for external monitors, clamps oversized/offscreen windows, and repairs placement after display layout changes.

## App icons

Source and generated runtime icons live under:

```text
icon-1024.png
src/main/resources/icons/icon.png
src/main/resources/icons/icon.icns
src/main/resources/icons/icon.ico
```

Recommended tools:

- ImageMagick 7+
- macOS `iconutil`

Important icon rules:

- AI-generated checkerboards are usually fake transparency; replace with real alpha.
- Use `PNG32:` with ImageMagick to keep RGBA output.
- Do not use `sips` for resizing; it can strip alpha.
- macOS dock sizing looks best when artwork is scaled to about 82% of a 1024 canvas (`840x840`).
- Runtime Java icon uses `src/main/resources/icons/icon.png`.
- jpackage icons use platform-specific files:
  - macOS: `icon.icns`
  - Windows: `icon.ico`
  - Linux: `icon.png`

Minimal regeneration commands:

```bash
# Java runtime icon
magick icon-1024.png -resize 512x512 PNG32:src/main/resources/icons/icon.png

# Windows
magick icon-1024.png -define icon:auto-resize=16,32,48,256 src/main/resources/icons/icon.ico

# macOS
mkdir -p target/icon.iconset
for pair in \
  "16 icon_16x16" "32 icon_16x16@2x" "32 icon_32x32" "64 icon_32x32@2x" \
  "128 icon_128x128" "256 icon_128x128@2x" "256 icon_256x256" "512 icon_256x256@2x" \
  "512 icon_512x512" "1024 icon_512x512@2x"; do
  size=$(echo "$pair" | cut -d' ' -f1)
  name=$(echo "$pair" | cut -d' ' -f2)
  magick icon-1024.png -resize ${size}x${size} PNG32:target/icon.iconset/${name}.png
done
iconutil -c icns target/icon.iconset -o src/main/resources/icons/icon.icns
rm -rf target/icon.iconset
```

Troubleshooting:

- Checkerboard visible: source has fake transparency; verify alpha with `identify -verbose icon.png`.
- White/opaque corners: alpha was stripped; regenerate with `PNG32:`.
- Dock shows Java icon during `mvn exec:java`: ensure both `setIconImage()` and `Taskbar.setIconImage()` are called.
