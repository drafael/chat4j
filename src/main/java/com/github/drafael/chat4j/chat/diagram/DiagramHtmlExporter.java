package com.github.drafael.chat4j.chat.diagram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.text.StringEscapeUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiagramHtmlExporter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_PAYLOAD_CHARS = 5_000_000;
    private static final int MAX_SVG_CHARS = 4_000_000;
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("(?i)<\\s*/?\\s*script\\b");
    private static final Pattern EVENT_HANDLER_PATTERN = Pattern.compile("(?i)\\son[a-z0-9_-]+\\s*=");
    private static final Pattern REFERENCE_ATTRIBUTE_PATTERN = Pattern.compile(
            "(?i)(?:^|[\\s<])(?:href|xlink:href|src)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"
    );
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?(?:[0-9a-fA-F]{2})?");
    private static final Pattern RGB_COLOR_PATTERN = Pattern.compile(
            "rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})(?:\\s*,\\s*(?:0|1|0?\\.\\d+))?\\s*\\)",
            Pattern.CASE_INSENSITIVE
    );

    private DiagramHtmlExporter() {
    }

    public static Path exportMermaidHtml(String payload) throws IOException {
        DiagramPayload diagramPayload = parsePayload(payload);
        String html = toHtml(diagramPayload);
        Path path = Files.createTempFile("chat4j-mermaid-", ".html");
        Files.writeString(path, html, StandardCharsets.UTF_8);
        path.toFile().deleteOnExit();
        return path;
    }

    static DiagramPayload parsePayload(String payload) throws IOException {
        String raw = StringUtils.defaultString(payload);
        if (raw.length() > MAX_PAYLOAD_CHARS) {
            throw new IOException("Diagram is too large.");
        }

        JsonNode root = OBJECT_MAPPER.readTree(raw);
        String type = root.path("type").asText("");
        if (!Strings.CS.equals(type, "mermaid")) {
            throw new IOException("Unsupported diagram type.");
        }

        String svg = root.path("svg").asText("");
        if (StringUtils.isBlank(svg)) {
            throw new IOException("Diagram SVG is missing.");
        }
        if (svg.length() > MAX_SVG_CHARS) {
            throw new IOException("Diagram SVG is too large.");
        }
        if (unsafeSvg(svg)) {
            throw new IOException("Diagram SVG contains unsupported active content.");
        }

        String title = StringUtils.defaultIfBlank(root.path("title").asText(""), "Mermaid Diagram");
        String source = root.path("source").asText("");
        String background = root.path("background").asText("");
        String color = root.path("color").asText("");
        String borderColor = root.path("borderColor").asText("");
        return new DiagramPayload(title, svg, source, background, color, borderColor);
    }

    static boolean unsafeSvg(String svg) {
        return SCRIPT_PATTERN.matcher(svg).find()
                || EVENT_HANDLER_PATTERN.matcher(svg).find()
                || hasUnsafeReferenceAttribute(svg);
    }

    private static boolean hasUnsafeReferenceAttribute(String svg) {
        Matcher matcher = REFERENCE_ATTRIBUTE_PATTERN.matcher(svg);
        while (matcher.find()) {
            String value = unquotedAttributeValue(matcher.group(1));
            if (!Strings.CS.startsWith(value, "#")) {
                return true;
            }
        }
        return false;
    }

    private static String unquotedAttributeValue(String rawValue) {
        String value = StringUtils.trimToEmpty(rawValue);
        if (value.length() < 2) {
            return value;
        }
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    static String toHtml(DiagramPayload payload) {
        String title = StringEscapeUtils.escapeHtml4(payload.title());
        String source = StringEscapeUtils.escapeHtml4(payload.source());
        String diagramBackground = safeCssColor(payload.background(), "#ffffff");
        boolean darkDiagram = isDarkColor(diagramBackground);
        String textColor = safeCssColor(payload.color(), darkDiagram ? "#f8fafc" : "#1f2937");
        String borderColor = safeCssColor(payload.borderColor(), darkDiagram ? "rgba(255,255,255,0.18)" : "#d9dee7");
        String pageBackground = darkDiagram ? diagramBackground : "#f6f7f9";
        String sourceBackground = darkDiagram ? "rgba(15,23,42,0.42)" : "#eef1f5";
        String sourceText = darkDiagram ? textColor : "#334155";
        return """
                <!doctype html>
                <html>
                <head>
                  <meta charset="UTF-8">
                  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:;">
                  <title>%s</title>
                  <style>
                    html, body { margin: 0; min-height: 100%%; background: %s; color: %s; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
                    body { box-sizing: border-box; padding: 28px; }
                    .page { max-width: none; margin: 0 auto; }
                    .header { margin: 0 0 18px 0; font-size: 18px; font-weight: 700; color: %s; }
                    .diagram { box-sizing: border-box; min-height: calc(100vh - 170px); display: flex; align-items: center; justify-content: center; padding: 24px; border: 1px solid %s; border-radius: 14px; background: %s; color: %s; box-shadow: 0 4px 18px rgba(15, 23, 42, 0.18); overflow: auto; }
                    .diagram svg { max-width: 100%%; height: auto; color: inherit; }
                    details { margin-top: 18px; color: %s; }
                    summary { cursor: pointer; font-weight: 600; }
                    pre { white-space: pre-wrap; overflow-wrap: anywhere; padding: 14px; border-radius: 10px; background: %s; color: %s; }
                  </style>
                </head>
                <body>
                  <main class="page">
                    <h1 class="header">%s</h1>
                    <section class="diagram">%s</section>
                    %s
                  </main>
                </body>
                </html>
                """.formatted(
                title,
                pageBackground,
                textColor,
                textColor,
                borderColor,
                diagramBackground,
                textColor,
                textColor,
                sourceBackground,
                sourceText,
                title,
                payload.svg(),
                StringUtils.isBlank(source) ? "" : "<details><summary>Source</summary><pre>%s</pre></details>".formatted(source)
        );
    }

    private static String safeCssColor(String color, String fallback) {
        String value = StringUtils.trimToEmpty(color);
        if (value.length() > 64 || Strings.CI.equals(value, "transparent")) {
            return fallback;
        }
        if (HEX_COLOR_PATTERN.matcher(value).matches() || RGB_COLOR_PATTERN.matcher(value).matches()) {
            return value;
        }
        return fallback;
    }

    private static boolean isDarkColor(String color) {
        int[] rgb = rgb(color);
        if (rgb.length != 3) {
            return false;
        }
        return ((rgb[0] * 299 + rgb[1] * 587 + rgb[2] * 114) / 1000) < 128;
    }

    private static int[] rgb(String color) {
        String value = StringUtils.trimToEmpty(color);
        Matcher rgbMatcher = RGB_COLOR_PATTERN.matcher(value);
        if (rgbMatcher.matches()) {
            return new int[] {
                    clampColor(rgbMatcher.group(1)),
                    clampColor(rgbMatcher.group(2)),
                    clampColor(rgbMatcher.group(3))
            };
        }
        if (!Strings.CS.startsWith(value, "#")) {
            return new int[0];
        }
        String hex = value.substring(1);
        if (hex.length() == 3) {
            return new int[] {
                    Integer.parseInt(hex.substring(0, 1).repeat(2), 16),
                    Integer.parseInt(hex.substring(1, 2).repeat(2), 16),
                    Integer.parseInt(hex.substring(2, 3).repeat(2), 16)
            };
        }
        if (hex.length() >= 6) {
            return new int[] {
                    Integer.parseInt(hex.substring(0, 2), 16),
                    Integer.parseInt(hex.substring(2, 4), 16),
                    Integer.parseInt(hex.substring(4, 6), 16)
            };
        }
        return new int[0];
    }

    private static int clampColor(String value) {
        return Math.max(0, Math.min(255, Integer.parseInt(value)));
    }

    record DiagramPayload(String title, String svg, String source, String background, String color, String borderColor) {
        @Override
        public String toString() {
            return "DiagramPayload[title=%s, svg=<masked>, source=<masked>, background=<masked>, color=<masked>, borderColor=<masked>]".formatted(title);
        }
    }
}
