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
        return new DiagramPayload(title, svg, source);
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
        return """
                <!doctype html>
                <html>
                <head>
                  <meta charset="UTF-8">
                  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:;">
                  <title>%s</title>
                  <style>
                    html, body { margin: 0; min-height: 100%%; background: #f6f7f9; color: #1f2937; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
                    body { box-sizing: border-box; padding: 28px; }
                    .page { max-width: 1400px; margin: 0 auto; }
                    .header { margin: 0 0 18px 0; font-size: 18px; font-weight: 700; }
                    .diagram { box-sizing: border-box; min-height: 60vh; display: flex; align-items: center; justify-content: center; padding: 24px; border: 1px solid #d9dee7; border-radius: 14px; background: #ffffff; box-shadow: 0 4px 18px rgba(15, 23, 42, 0.08); overflow: auto; }
                    .diagram svg { max-width: 100%%; height: auto; }
                    details { margin-top: 18px; color: #4b5563; }
                    summary { cursor: pointer; font-weight: 600; }
                    pre { white-space: pre-wrap; overflow-wrap: anywhere; padding: 14px; border-radius: 10px; background: #eef1f5; color: #334155; }
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
                title,
                payload.svg(),
                StringUtils.isBlank(source) ? "" : "<details><summary>Source</summary><pre>%s</pre></details>".formatted(source)
        );
    }

    record DiagramPayload(String title, String svg, String source) {
        @Override
        public String toString() {
            return "DiagramPayload[title=%s, svg=<masked>, source=<masked>]".formatted(title);
        }
    }
}
