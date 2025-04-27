# macOS HiDPI Font Rendering in Java Swing with FlatLaf

## Overview

Chat4J uses [FlatLaf](https://www.formdev.com/flatlaf/) as its Swing Look and Feel. On macOS with Retina (HiDPI) displays, font rendering requires careful handling to avoid broken or missing characters. This document describes the issues encountered and the solution adopted.

## Environment

- macOS with Retina display (system scale 2.0)
- Java 21 (Homebrew)
- FlatLaf 3.7.1
- FlatLaf IntelliJ Themes 3.7.1

## The Problem

When rendering text on macOS HiDPI displays, several standard Java font approaches produce broken output where characters appear scattered, missing, or truncated:

### Approach 1: `deriveFont()` on component fonts

```java
// BROKEN: produces missing/scattered characters on HiDPI
label.setFont(label.getFont().deriveFont(Font.BOLD, 18f));
```

### Approach 2: `deriveFont()` on UIManager default font

```java
// BROKEN: same issue as above
label.setFont(UIManager.getFont("defaultFont").deriveFont(Font.BOLD, 18f));
```

### Approach 3: FlatLaf style properties

```java
// BROKEN: does not apply correctly in cell renderers and popup dialogs
label.putClientProperty("FlatLaf.styleClass", "h2");
label.putClientProperty("FlatLaf.style", "font: bold +5");
```

All three approaches result in text rendering like this:
- Full words reduced to scattered single characters (e.g., "explain grep" → "g sk s")
- Tab labels truncated to single letters (e.g., "General" → "G")
- Dropdown text showing only fragments

## Root Cause

### Composite Fonts

FlatLaf creates **composite fonts** using `StyleContext.getDefaultStyleContext().getFont()`. Composite fonts are special Java font objects that:
- Support full Unicode rendering across multiple font files
- Handle HiDPI scaling correctly on macOS
- Bundle proper font metrics for layout calculations

When `deriveFont()` is called on a composite font, it strips the composite wrapper and returns a **raw font** that lacks proper HiDPI metrics. The layout engine then calculates incorrect character widths, causing the scattered/missing character effect.

Reference: FlatLaf source — [`FontUtils.createCompositeFont()`](https://github.com/JFormDesigner/FlatLaf/blob/main/flatlaf-core/src/main/java/com/formdev/flatlaf/FontUtils.java)

### Cell Renderers and Popup Components

FlatLaf installs its default font on components during UI initialization, which happens when components are added to the Swing component hierarchy. However:

1. **Cell renderer labels** — `DefaultListCellRenderer.getListCellRendererComponent()` creates or reuses `JLabel` instances that are transient and never added to the component tree. FlatLaf never processes their `putClientProperty()` calls.

2. **Popup dialogs** — `JDialog` and `JWindow` instances created with `setUndecorated(true)` may not fully inherit FlatLaf's font setup, especially their child components.

3. **Dynamic components** — Any `new JLabel()` or `new JPanel()` created at runtime outside the normal hierarchy may not receive FlatLaf's composite font.

### Why the FlatLaf Demo Works

The [FlatLaf Demo](https://github.com/JFormDesigner/FlatLaf/tree/main/flatlaf-demo) renders correctly because it:
- Never calls `setFont()` on individual components
- Uses standard Swing components in the normal component hierarchy
- Uses FlatLaf's style class system only on components that are properly attached to the tree
- Relies entirely on FlatLaf's automatic font initialization

## Solution

Use the `Fonts.of()` utility (`com.github.drafael.chat4j.util.Fonts`) — a cross-platform font factory that handles the macOS HiDPI issue while supporting all platforms:

```java
// WORKS: correct rendering on all platforms
label.setFont(Fonts.of(Font.PLAIN, 13));
label.setFont(Fonts.of(Font.BOLD, 18));
```

### Implementation

```java
public final class Fonts {
    private Fonts() {}

    public static Font of(int style, int size) {
        if (SystemInfo.isMacOS) {
            return new Font(".AppleSystemUIFont", style, size);
        }
        Font base = UIManager.getFont("defaultFont");
        if (base != null) {
            return base.deriveFont(style, (float) size);
        }
        return new Font(Font.SANS_SERIF, style, size);
    }
}
```

### How it works

- **macOS**: Uses `.AppleSystemUIFont` — the private font name that resolves directly to the system font (SF Pro) with proper HiDPI support. This bypasses FlatLaf's composite font system entirely and works in all contexts: cell renderers, popups, and standard components.
- **Windows/Linux**: Uses FlatLaf's `defaultFont` via `deriveFont()`, which works correctly on these platforms since the composite font / HiDPI issue is macOS-specific. Falls back to `Font.SANS_SERIF` if FlatLaf's default font is unavailable.

### Trade-offs

| Aspect | `Fonts.of()` | Raw `deriveFont()` | FlatLaf Style Properties |
|--------|-------------|--------------------|-----------------------|
| macOS HiDPI rendering | Correct | Broken | Broken in renderers/popups |
| Windows/Linux | Correct | Correct | Correct (in component tree) |
| Cell renderers | Works | Broken on macOS | Broken |
| Popup dialogs | Works | Broken on macOS | Broken |
| Theme consistency | Matches system font | Matches system font | Guaranteed match |
| `.AppleSystemUIFont` | Private API, could change | N/A | N/A |

## FlatLaf References

- [FlatLaf Documentation](https://www.formdev.com/flatlaf/)
- [FlatLaf Typography & Fonts](https://www.formdev.com/flatlaf/typography/)
- [FlatLaf Client Properties](https://www.formdev.com/flatlaf/client-properties/)
- [FlatLaf Customizing — Fonts](https://www.formdev.com/flatlaf/customizing/#fonts)
- [FlatLaf GitHub — FontUtils.java](https://github.com/JFormDesigner/FlatLaf/blob/main/flatlaf-core/src/main/java/com/formdev/flatlaf/FontUtils.java)
- [FlatLaf GitHub — FlatLaf.java `initDefaultFont()`](https://github.com/JFormDesigner/FlatLaf/blob/main/flatlaf-core/src/main/java/com/formdev/flatlaf/FlatLaf.java)
- [JDK Bug: Composite font issues on macOS](https://bugs.openjdk.org/browse/JDK-8289609)

## Summary

On macOS HiDPI with Java 21 and FlatLaf, never use `deriveFont()` or FlatLaf style properties for font configuration — these produce broken rendering in cell renderers and popup dialogs due to composite font unwrapping. Use `Fonts.of(style, size)` everywhere instead, which handles macOS via `.AppleSystemUIFont` and delegates to FlatLaf's default font on other platforms.
