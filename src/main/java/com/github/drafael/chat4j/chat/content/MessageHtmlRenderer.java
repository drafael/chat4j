package com.github.drafael.chat4j.chat.content;

import com.github.drafael.chat4j.chat.render.CodeFontResolver;
import com.github.drafael.chat4j.chat.render.MarkdownPaletteResolver;
import com.github.drafael.chat4j.chat.render.MarkdownRenderer;
import com.github.drafael.chat4j.chat.render.Palette;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.util.Fonts;
import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

import static java.util.stream.Collectors.joining;

public final class MessageHtmlRenderer {

    private static final int GENERATED_IMAGE_MAX_WIDTH = 640;
    private static final int GENERATED_IMAGE_MAX_HEIGHT = 640;

    public String render(Role role, RenderMode renderMode, String text, boolean isDark) {
        return render(role, renderMode, text, isDark, MarkdownPaletteResolver.resolve(isDark));
    }

    public String render(Role role, RenderMode renderMode, String text, boolean isDark, Palette palette) {
        return render(role, renderMode, List.of(new TextPart(StringUtils.defaultString(text))), isDark, palette);
    }

    public String render(Role role, RenderMode renderMode, List<ContentPart> parts, boolean isDark) {
        return render(role, renderMode, parts, isDark, MarkdownPaletteResolver.resolve(isDark));
    }

    public String render(Role role, RenderMode renderMode, List<ContentPart> parts, boolean isDark, Palette palette) {
        List<ContentPart> safeParts = parts == null ? List.of() : parts.stream().filter(part -> part != null && !part.isEmpty()).toList();
        String safeText = textProjection(safeParts);
        String html = switch (role) {
            case USER -> renderMode == RenderMode.MARKDOWN
                    ? toEscapedHtml(safeText, palette, true)
                    : toUserMarkdownHtml(safeText, palette, isDark);
            case null, default -> renderMode == RenderMode.MARKDOWN
                    ? toEscapedHtml(safeText, palette, true)
                    : MarkdownRenderer.toHtml(safeText, palette);
        };
        if (role == Role.USER || renderMode == RenderMode.MARKDOWN || safeParts.stream().noneMatch(GeneratedImagePart.class::isInstance)) {
            return html;
        }
        return appendGeneratedImageHtml(html, safeParts);
    }

    private String textProjection(List<ContentPart> parts) {
        return parts.stream()
                .filter(TextPart.class::isInstance)
                .map(TextPart.class::cast)
                .map(TextPart::text)
                .collect(joining());
    }

    private String appendGeneratedImageHtml(String html, List<ContentPart> parts) {
        String imagesHtml = parts.stream()
                .filter(GeneratedImagePart.class::isInstance)
                .map(GeneratedImagePart.class::cast)
                .map(this::renderGeneratedImageHtml)
                .filter(StringUtils::isNotBlank)
                .collect(joining("\n"));
        if (StringUtils.isBlank(imagesHtml)) {
            return html;
        }
        String generatedImageCss = """
                .generated-image-wrap, .generated-image-wrap:link, .generated-image-wrap:visited, .generated-image-wrap:focus { display: inline-block; border: none; outline: none; background: transparent; padding: 0; margin: 8px 0 4px 0; cursor: pointer; text-decoration: none; color: inherit; }
                .generated-image { max-width: 420px; max-height: 420px; border: 0; border-radius: 12px; display: block; }
                """;
        return html
                .replace("</style>", "%s</style>".formatted(generatedImageCss))
                .replace("</body>", "%s</body>".formatted(imagesHtml));
    }

    private String renderGeneratedImageHtml(GeneratedImagePart part) {
        AttachmentRef ref = part.attachmentRef();
        if (ref == null || StringUtils.isBlank(ref.storagePath())) {
            return "";
        }
        try {
            String uri = Path.of(ref.storagePath()).toUri().toString();
            String alt = escapeHtmlAttribute(part.altText());
            String path = escapeHtmlAttribute(ref.storagePath());
            Dimension size = generatedImageDisplaySize(part);
            return """
                    <a class="generated-image-wrap" href="%s" data-action="open-attachment" data-attachment-path="%s" title="%s" style="border: none; outline: none; text-decoration: none;"><img class="generated-image" src="%s" alt="%s" width="%d" height="%d" border="0" style="border: 0;"></a>
                    """.formatted(uri, path, alt, uri, alt, size.width, size.height);
        } catch (Exception e) {
            return "";
        }
    }

    private Dimension generatedImageDisplaySize(GeneratedImagePart part) {
        Dimension naturalSize = generatedImageNaturalSize(part);
        double scale = Math.min(
                1.0,
                Math.min(
                        GENERATED_IMAGE_MAX_WIDTH / (double) naturalSize.width,
                        GENERATED_IMAGE_MAX_HEIGHT / (double) naturalSize.height
                )
        );
        int width = Math.max(1, (int) Math.round(naturalSize.width * scale));
        int height = Math.max(1, (int) Math.round(naturalSize.height * scale));
        return new Dimension(width, height);
    }

    private Dimension generatedImageNaturalSize(GeneratedImagePart part) {
        if (part.width() != null && part.height() != null && part.width() > 0 && part.height() > 0) {
            return new Dimension(part.width(), part.height());
        }
        try {
            BufferedImage image = ImageIO.read(Path.of(part.attachmentRef().storagePath()).toFile());
            if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                return new Dimension(image.getWidth(), image.getHeight());
            }
        } catch (Exception ignored) {
        }
        return new Dimension(GENERATED_IMAGE_MAX_WIDTH, GENERATED_IMAGE_MAX_HEIGHT);
    }

    String toEscapedHtml(String text, Palette palette, boolean monospaced) {
        String escaped = escapeHtml(text);

        String fontFamily = monospaced ? palette.monoFontFamily() : palette.baseFontFamily();
        int fontSize = monospaced ? CodeFontResolver.resolveCodeFontSize() : Fonts.scale(Fonts.SIZE_SMALL);
        return "<html><head><style>body { color: %s; margin: 0; padding: 0; } pre { font-family: %s; font-size: %dpx; line-height: 1.7; margin: 0; padding: 0; white-space: pre-wrap; word-wrap: break-word; }</style></head><body><pre>%s</pre></body></html>"
                .formatted(palette.textColor(), fontFamily, fontSize, escaped);
    }

    String toUserMarkdownHtml(String text, Palette palette, boolean isDark) {
        String badgeBackground = isDark ? "#2d4f8f" : "#dbeafe";
        String badgeText = isDark ? "#dbeafe" : "#1e3a8a";
        String fallbackBackground = isDark ? "#5c3a16" : "#ffedd5";
        String fallbackText = isDark ? "#fed7aa" : "#9a3412";
        StringBuilder badgeHtml = new StringBuilder();
        String markdown = text.lines()
                .filter(line -> appendBadgeLine(line, badgeHtml, badgeBackground, badgeText, fallbackBackground, fallbackText))
                .collect(joining("\n"));

        String html = MarkdownRenderer.toHtml(markdown, palette);
        if (badgeHtml.isEmpty()) {
            return html;
        }

        return html
                .replace("</style>", "%s</style>".formatted(userBadgeCss(badgeFontSize(), badgeBackground, badgeText, fallbackBackground, fallbackText)))
                .replace("<body>", "<body>%s".formatted(badgeHtml));
    }

    private boolean appendBadgeLine(String line,
                                    StringBuilder badgeHtml,
                                    String skillBadgeBackground,
                                    String skillBadgeText,
                                    String fallbackBadgeBackground,
                                    String fallbackBadgeText
    ) {
        if (line.startsWith("[SKILL] ") || line.startsWith("[FALLBACK] ")) {
            badgeHtml.append(toUserLineHtml(line, skillBadgeBackground, skillBadgeText, fallbackBadgeBackground, fallbackBadgeText));
            return false;
        }
        return true;
    }

    private String userBadgeCss(int badgeFontSize,
                                String badgeBackground,
                                String badgeText,
                                String fallbackBackground,
                                String fallbackText
    ) {
        return """
                .line { margin: 0 0 3px 0; }
                .notice { margin: 0 0 6px 0; }
                .badge { display: inline-block; border-radius: 999px; padding: 1px 6px; font-size: %dpx; font-weight: 700; letter-spacing: 0.04em; margin-right: 6px; }
                .skill { background: %s; color: %s; }
                .fallback { background: %s; color: %s; }
                """.formatted(badgeFontSize, badgeBackground, badgeText, fallbackBackground, fallbackText);
    }

    private int badgeFontSize() {
        return Math.max(9, CodeFontResolver.resolveCodeFontSize() - 1);
    }

    private String toUserLineHtml(String line,
                                  String skillBadgeBackground,
                                  String skillBadgeText,
                                  String fallbackBadgeBackground,
                                  String fallbackBadgeText
    ) {
        if (StringUtils.isBlank(line)) {
            return "<div class='line'>&nbsp;</div>";
        }

        if (line.startsWith("[SKILL] ")) {
            return "<div class='line'><span class='badge skill' style='background: %s; color: %s;'>SKILL</span>%s</div>"
                    .formatted(skillBadgeBackground, skillBadgeText, escapeHtml(line.substring(8).trim()));
        }

        if (line.startsWith("[FALLBACK] ")) {
            String notice = line.substring(11).trim();
            return "<div class='line notice fallback-notice' style='margin: 0 0 6px 0; font-size: 0.92em; line-height: 1.35;'><span class='badge fallback' style='background: %s; color: %s; border-radius: 999px; padding: 1px 6px; font-weight: 700; letter-spacing: 0.04em; margin-right: 6px;'>ATTACHMENT</span><span style='opacity: 0.82;' title='%s'>%s</span></div>"
                    .formatted(
                            fallbackBadgeBackground,
                            fallbackBadgeText,
                            escapeHtmlAttribute(notice),
                            escapeHtml(compactFallbackNotice(notice))
                    );
        }

        return "<div class='line'>%s</div>".formatted(escapeHtml(line));
    }

    private String compactFallbackNotice(String notice) {
        String normalized = StringUtils.defaultString(notice).toLowerCase();
        if ((normalized.contains("supports rich input") && normalized.contains("file upload mapping"))
                || normalized.contains("native file upload is not mapped")) {
            return "Extracted text sent · native upload not mapped";
        }
        if (normalized.contains("uses text-only fallback for file attachments")
                || normalized.contains("native file upload is unavailable")) {
            return "Extracted text sent · native upload unavailable";
        }
        if (normalized.contains("text-only image references")) {
            return "Image sent as a text reference";
        }
        return notice;
    }

    private String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String escapeHtmlAttribute(String text) {
        return escapeHtml(text)
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
