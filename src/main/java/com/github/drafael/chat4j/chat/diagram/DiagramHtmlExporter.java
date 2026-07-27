package com.github.drafael.chat4j.chat.diagram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptBrowserAssets;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.text.StringEscapeUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptResources.safeScriptContent;

public final class DiagramHtmlExporter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_PAYLOAD_CHARS = 100_000;
    private static final int MAX_SOURCE_CHARS = 20_000;
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");
    private static final Pattern RGB_COLOR_PATTERN = Pattern.compile(
            "rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})(?:\\s*,\\s*(?:0|1|0?\\.\\d+))?\\s*\\)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern THEME_VARIABLE_NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,63}");
    private static final Set<String> REQUIRED_THEME_COLORS = Set.of(
            "textColor",
            "edgeLabelBackground",
            "lineColor",
            "actorBkg",
            "actorBorder",
            "labelBoxBkgColor",
            "labelBoxBorderColor",
            "activationBkgColor",
            "activationBorderColor",
            "primaryColor",
            "branchLabelColor"
    );

    private DiagramHtmlExporter() {
    }

    public static Path exportMermaidHtml(String payload) throws IOException {
        DiagramPayload diagramPayload = parsePayload(payload);
        Path path = Files.createTempFile("chat4j-mermaid-", ".html");
        try {
            Files.writeString(path, toHtml(diagramPayload), StandardCharsets.UTF_8);
            path.toFile().deleteOnExit();
            return path;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    static DiagramPayload parsePayload(String payload) throws IOException {
        String raw = StringUtils.defaultString(payload);
        if (raw.length() > MAX_PAYLOAD_CHARS) {
            throw new IOException("Diagram is too large.");
        }

        JsonNode root = OBJECT_MAPPER.readTree(raw);
        if (root == null || !root.isObject() || !Strings.CS.equals(root.path("type").asText(""), "mermaid")) {
            throw new IOException("Unsupported diagram type.");
        }

        String source = root.path("source").asText("");
        if (StringUtils.isBlank(source)) {
            throw new IOException("Diagram source is missing.");
        }
        if (source.length() > MAX_SOURCE_CHARS) {
            throw new IOException("Diagram source is too large.");
        }

        return new DiagramPayload(
                StringUtils.defaultIfBlank(root.path("title").asText(""), "Mermaid Diagram"),
                source,
                requiredCssColor(root, "pageBackground"),
                requiredCssColor(root, "sourceBackground"),
                requiredCssColor(root, "background"),
                requiredCssColor(root, "color"),
                requiredCssColor(root, "borderColor"),
                validatedThemeVariables(root.path("themeVariables"))
        );
    }

    static String toHtml(DiagramPayload payload) {
        String title = StringEscapeUtils.escapeHtml4(payload.title());
        String source = StringEscapeUtils.escapeHtml4(payload.source());
        String pageBackground = payload.pageBackground();
        String sourceBackground = payload.sourceBackground();
        String diagramBackground = payload.background();
        String textColor = payload.color();
        String borderColor = payload.borderColor();
        String mermaidScript = safeScriptContent(TranscriptBrowserAssets.mermaidScript());
        String themeVariables = safeScriptContent(payload.themeVariables().toString());
        String themeCss = standaloneThemeCss(payload);
        return """
                <!doctype html>
                <html>
                <head>
                  <meta charset="UTF-8">
                  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; base-uri 'none'; form-action 'none'; object-src 'none'; connect-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline';">
                  <title>%s</title>
                  <style>
                    html, body { margin: 0; min-height: 100%%; background: %s; color: %s; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
                    body { box-sizing: border-box; padding: 28px; }
                    .page { max-width: none; margin: 0 auto; }
                    .header { margin: 0 0 18px 0; font-size: 18px; font-weight: 700; }
                    .diagram { box-sizing: border-box; width: 100%%; height: calc(100vh - 170px); min-height: 360px; display: flex; align-items: center; justify-content: center; padding: 24px; border: 1px solid %s; border-radius: 14px; background: %s; overflow: auto; }
                    .diagram > .mermaid { box-sizing: border-box; width: 100%%; height: 100%%; margin: 0; }
                    .diagram svg { width: 100%% !important; height: 100%% !important; max-width: none !important; max-height: 100%% !important; }
                    %s
                    details { margin-top: 18px; }
                    summary { cursor: pointer; font-weight: 600; }
                    pre.source { white-space: pre-wrap; overflow-wrap: anywhere; padding: 14px; border-radius: 10px; background: %s; color: %s; }
                  </style>
                  <script>%s</script>
                </head>
                <body>
                  <main class="page">
                    <h1 class="header">%s</h1>
                    <section class="diagram"><pre class="mermaid">%s</pre></section>
                    <details><summary>Source</summary><pre class="source">%s</pre></details>
                  </main>
                  <script>
                    var themeTextColor = '%s';
                    var diagramBackgroundColor = '%s';
                    function colorParts(color) {
                      var value = String(color || '').trim();
                      var rgb = value.match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)/i);
                      if (rgb) {
                        return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])];
                      }
                      var hex = value.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i);
                      if (!hex) {
                        return null;
                      }
                      var digits = hex[1].length === 3 ? hex[1].replace(/./g, function(ch) { return ch + ch; }) : hex[1];
                      return [parseInt(digits.slice(0, 2), 16), parseInt(digits.slice(2, 4), 16), parseInt(digits.slice(4, 6), 16)];
                    }
                    function relativeLuminance(color) {
                      var parts = colorParts(color);
                      if (!parts) {
                        return null;
                      }
                      function channel(value) {
                        var normalized = value / 255;
                        return normalized <= 0.03928 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
                      }
                      return channel(parts[0]) * 0.2126 + channel(parts[1]) * 0.7152 + channel(parts[2]) * 0.0722;
                    }
                    function contrastRatio(first, second) {
                      var firstLuminance = relativeLuminance(first);
                      var secondLuminance = relativeLuminance(second);
                      if (firstLuminance === null || secondLuminance === null) {
                        return 0;
                      }
                      var lighter = Math.max(firstLuminance, secondLuminance);
                      var darker = Math.min(firstLuminance, secondLuminance);
                      return (lighter + 0.05) / (darker + 0.05);
                    }
                    function readableColor(background) {
                      return contrastRatio(background, themeTextColor) >= contrastRatio(background, diagramBackgroundColor)
                        ? themeTextColor
                        : diagramBackgroundColor;
                    }
                    function svgPaint(element, property) {
                      var value = element ? element.getAttribute(property) : '';
                      if ((!value || !value.trim()) && element) {
                        value = window.getComputedStyle(element).getPropertyValue(property);
                      }
                      value = String(value || '').trim();
                      return value && value !== 'none' && value !== 'transparent' && value.indexOf('url(') !== 0 && colorParts(value) ? value : '';
                    }
                    function nodeFillColor(node) {
                      var shapes = node.querySelectorAll('rect, polygon, path, circle, ellipse');
                      for (var index = 0; index < shapes.length; index++) {
                        var fill = svgPaint(shapes[index], 'fill');
                        if (fill) {
                          return fill;
                        }
                      }
                      return svgPaint(node, 'fill');
                    }
                    function applyReadableNodeLabels(svg) {
                      Array.prototype.forEach.call(svg.querySelectorAll('g.node'), function(node) {
                        if (node.closest && node.closest('.edgeLabel')) {
                          return;
                        }
                        var fill = nodeFillColor(node);
                        if (!fill) {
                          return;
                        }
                        var color = readableColor(fill);
                        Array.prototype.forEach.call(node.querySelectorAll('text, tspan, .nodeLabel, .nodeLabel *, foreignObject, foreignObject *'), function(label) {
                          label.style.setProperty('color', color, 'important');
                          label.style.setProperty('fill', color, 'important');
                          label.style.setProperty('fill-opacity', '1', 'important');
                          label.style.setProperty('opacity', '1', 'important');
                        });
                      });
                    }
                    mermaid.initialize({
                      startOnLoad: false,
                      securityLevel: 'strict',
                      theme: 'base',
                      themeVariables: %s
                    });
                    mermaid.run({
                      querySelector: '.mermaid',
                      postRenderCallback: function(id) {
                        var svg = document.getElementById(id);
                        if (svg) {
                          applyReadableNodeLabels(svg);
                        }
                      }
                    });
                  </script>
                </body>
                </html>
                """.formatted(
                title,
                pageBackground,
                textColor,
                borderColor,
                diagramBackground,
                themeCss,
                sourceBackground,
                textColor,
                mermaidScript,
                title,
                source,
                source,
                textColor,
                diagramBackground,
                themeVariables
        );
    }

    private static String standaloneThemeCss(DiagramPayload payload) {
        ObjectNode theme = payload.themeVariables();
        String text = theme.path("textColor").asText();
        String line = theme.path("lineColor").asText();
        String actorSurface = theme.path("actorBkg").asText();
        String actorLine = theme.path("actorBorder").asText();
        String labelSurface = theme.path("labelBoxBkgColor").asText();
        String labelLine = theme.path("labelBoxBorderColor").asText();
        return """
                .diagram svg .edgeLabel, .diagram svg .edgeLabel p { color: %1$s !important; background: %2$s !important; }
                .diagram svg .edgeLabel text, .diagram svg .edgeLabel tspan { fill: %1$s !important; color: %1$s !important; }
                .diagram svg .mindmap-node-label, .diagram svg .mindmap-node-label div, .diagram svg .mindmap-node-label span, .diagram svg .mindmap-node .nodeLabel, .diagram svg .mindmap-node .nodeLabel span, .diagram svg .mindmap-node .text-inner-tspan, .diagram svg .mindmap-node .text-outer-tspan { color: %1$s !important; fill: %1$s !important; opacity: 1 !important; fill-opacity: 1 !important; }
                .diagram svg .mindmap-node.section-root rect, .diagram svg .mindmap-node.section-root path, .diagram svg .mindmap-node.section-root circle, .diagram svg .mindmap-node.section-root polygon { fill: %3$s !important; }
                .diagram svg .branchLabel text, .diagram svg .branchLabel tspan { fill: %4$s !important; color: %4$s !important; }
                .diagram svg .commit-merge, .diagram svg .commit-reverse, .diagram svg .commit-highlight-inner { stroke: %5$s !important; fill: %5$s !important; }
                .diagram svg path.flowchart-link, .diagram svg .edgePath path, .diagram svg .edge-pattern-solid, .diagram svg .edge-pattern-dashed, .diagram svg .edge-pattern-dotted, .diagram svg .transition, .diagram svg .relationshipLine, .diagram svg .mindmap-edge, .diagram svg [class*='section-edge-'], .diagram svg marker path { stroke: %5$s !important; }
                .diagram svg marker path, .diagram svg .arrowheadPath, .diagram svg .marker { fill: %5$s !important; stroke: %5$s !important; }
                .diagram svg rect.actor, .diagram svg .actor-box { fill: %6$s !important; stroke: %7$s !important; }
                .diagram svg text.actor, .diagram svg text.actor tspan, .diagram svg .messageText, .diagram svg .messageText tspan, .diagram svg .labelText, .diagram svg .labelText tspan, .diagram svg .loopText, .diagram svg .loopText tspan, .diagram svg .noteText, .diagram svg .noteText tspan { fill: %1$s !important; color: %1$s !important; stroke: none !important; }
                .diagram svg .actor-line, .diagram svg .messageLine0, .diagram svg .messageLine1, .diagram svg .loopLine, .diagram svg .actor-man line, .diagram svg .actor-man circle, .diagram svg #arrowhead path, .diagram svg #crosshead path { stroke: %7$s !important; }
                .diagram svg #arrowhead path, .diagram svg #crosshead path { fill: %7$s !important; }
                .diagram svg .labelBox, .diagram svg .note { fill: %8$s !important; stroke: %9$s !important; }
                .diagram svg .activation0, .diagram svg .activation1, .diagram svg .activation2 { fill: %8$s !important; stroke: %9$s !important; }
                """.formatted(
                text,
                theme.path("edgeLabelBackground").asText(),
                theme.path("primaryColor").asText(),
                theme.path("branchLabelColor").asText(),
                line,
                actorSurface,
                actorLine,
                labelSurface,
                labelLine
        );
    }

    private static ObjectNode validatedThemeVariables(JsonNode themeVariables) throws IOException {
        if (!themeVariables.isObject() || themeVariables.isEmpty()) {
            throw new IOException("Diagram theme variables are missing.");
        }
        ObjectNode validated = OBJECT_MAPPER.createObjectNode();
        var fields = themeVariables.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String name = field.getKey();
            JsonNode value = field.getValue();
            if (!THEME_VARIABLE_NAME_PATTERN.matcher(name).matches()) {
                throw new IOException("Diagram theme variable name is invalid.");
            }
            if (Strings.CS.equals(name, "darkMode") && value.isBoolean()) {
                validated.put(name, value.booleanValue());
            } else if (value.isTextual()) {
                validated.put(name, validatedCssColor(value.asText(), name));
            } else {
                throw new IOException("Diagram theme variable is invalid: %s".formatted(name));
            }
        }
        if (!validated.path("darkMode").isBoolean()) {
            throw new IOException("Diagram theme variable is invalid: darkMode");
        }
        for (String name : REQUIRED_THEME_COLORS) {
            if (!validated.path(name).isTextual()) {
                throw new IOException("Diagram theme variable is missing: %s".formatted(name));
            }
        }
        return validated;
    }

    private static String requiredCssColor(JsonNode root, String fieldName) throws IOException {
        return validatedCssColor(root.path(fieldName).asText(""), fieldName);
    }

    private static String validatedCssColor(String color, String fieldName) throws IOException {
        String value = StringUtils.trimToEmpty(color);
        Matcher rgbMatcher = RGB_COLOR_PATTERN.matcher(value);
        boolean validRgb = rgbMatcher.matches()
                && IntStream.rangeClosed(1, 3).map(index -> Integer.parseInt(rgbMatcher.group(index))).allMatch(channel -> channel <= 255);
        if (value.length() > 64 || Strings.CI.equals(value, "transparent")
                || !(HEX_COLOR_PATTERN.matcher(value).matches() || validRgb)) {
            throw new IOException("Diagram theme color is missing or invalid: %s".formatted(fieldName));
        }
        return value;
    }

    record DiagramPayload(
            String title,
            String source,
            String pageBackground,
            String sourceBackground,
            String background,
            String color,
            String borderColor,
            ObjectNode themeVariables
    ) {
        @Override
        public String toString() {
            return "DiagramPayload[title=%s, source=<masked>, background=<masked>, color=<masked>, borderColor=<masked>]".formatted(title);
        }
    }
}
