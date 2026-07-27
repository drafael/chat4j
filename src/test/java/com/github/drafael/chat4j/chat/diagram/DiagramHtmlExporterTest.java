package com.github.drafael.chat4j.chat.diagram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagramHtmlExporterTest {

    @Test
    @DisplayName("Source-only Mermaid payloads produce standalone renderer pages")
    void parsePayload_whenSourceIsValid_producesStandaloneRendererPage() throws Exception {
        var payload = DiagramHtmlExporter.parsePayload(payload("flowchart TD\nA-->B"));

        String html = DiagramHtmlExporter.toHtml(payload);

        assertThat(payload.source()).isEqualTo("flowchart TD\nA-->B");
        assertThat(html.indexOf(".diagram svg .mindmap-node-label")).isLessThan(html.indexOf("details {"));
        assertThat(html)
                .contains("class=\"mermaid\">flowchart TD\nA--&gt;B</pre>")
                .contains("securityLevel: 'strict'")
                .contains("theme: 'base'")
                .contains("\"primaryTextColor\":\"#111111\"")
                .contains("height: calc(100vh - 170px)")
                .contains(".diagram > .mermaid { box-sizing: border-box; width: 100%; height: 100%; margin: 0; }")
                .contains(".diagram svg { width: 100% !important; height: 100% !important")
                .contains("pre.source { white-space: pre-wrap; overflow-wrap: anywhere; padding: 14px; border-radius: 10px; background: #eeeeee; color: #111111; }")
                .contains("mermaid.run({")
                .contains("postRenderCallback: function(id)")
                .contains("base-uri 'none'")
                .contains("connect-src 'none'");
    }

    @Test
    @DisplayName("Diagram titles and source are escaped in standalone pages")
    void toHtml_whenMetadataContainsMarkup_escapesMetadata() throws Exception {
        var payload = DiagramHtmlExporter.parsePayload("""
                {"type":"mermaid","title":"<Title>","source":"flowchart TD\\nA[<b>unsafe</b>]","pageBackground":"#fafafa","sourceBackground":"#eeeeee","background":"#f4f4f4","color":"#111111","borderColor":"#cccccc","themeVariables":{"darkMode":false,"textColor":"#111111","primaryColor":"#ffffff","primaryTextColor":"#111111","edgeLabelBackground":"#f4f4f4","lineColor":"#777777","actorBkg":"#eeeeee","actorBorder":"#777777","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#777777","activationBkgColor":"#e5e5e5","activationBorderColor":"#777777","branchLabelColor":"#111111"}}
                """);

        String html = DiagramHtmlExporter.toHtml(payload);

        assertThat(html)
                .contains("&lt;Title&gt;")
                .contains("A[&lt;b&gt;unsafe&lt;/b&gt;]")
                .doesNotContain("<Title>")
                .doesNotContain("A[<b>unsafe</b>]");
    }

    @Test
    @DisplayName("Missing Mermaid source is rejected")
    void parsePayload_whenSourceIsMissing_throwsException() {
        assertThatThrownBy(() -> DiagramHtmlExporter.parsePayload("{\"type\":\"mermaid\"}"))
                .isInstanceOf(IOException.class)
                .hasMessage("Diagram source is missing.");
    }

    @Test
    @DisplayName("Oversized Mermaid source is rejected")
    void parsePayload_whenSourceIsTooLarge_throwsException() {
        String source = "flowchart TD\n" + "A".repeat(20_000);

        assertThatThrownBy(() -> DiagramHtmlExporter.parsePayload(payload(source)))
                .isInstanceOf(IOException.class)
                .hasMessage("Diagram source is too large.");
    }

    @Test
    @DisplayName("Standalone pages preserve resolved theme colors across Mermaid families")
    void toHtml_whenThemeVariablesAreResolved_preservesThemeVariables() throws Exception {
        var payload = DiagramHtmlExporter.parsePayload("""
                {"type":"mermaid","title":"Diagram","source":"mindmap\\n  root((Topic))","pageBackground":"rgb(24,28,38)","sourceBackground":"rgb(55,65,81)","background":"rgb(31,36,48)","color":"rgb(241,245,249)","borderColor":"rgb(120,130,145)","themeVariables":{"darkMode":true,"textColor":"rgb(241,245,249)","primaryColor":"rgb(45,55,72)","noteTextColor":"rgb(241,245,249)","edgeLabelBackground":"rgb(31,36,48)","lineColor":"rgb(145,155,170)","actorBkg":"rgb(65,75,92)","actorBorder":"rgb(241,245,249)","actorLineColor":"rgb(241,245,249)","labelBoxBkgColor":"rgb(45,55,72)","labelBoxBorderColor":"rgb(241,245,249)","activationBkgColor":"rgb(65,75,92)","activationBorderColor":"rgb(241,245,249)","branchLabelColor":"rgb(241,245,249)","cScale0":"rgb(45,55,72)","git0":"rgb(145,155,170)","quadrant1Fill":"rgb(55,65,81)","taskBkgColor":"rgb(45,55,72)"}}
                """);

        String html = DiagramHtmlExporter.toHtml(payload);

        assertThat(html)
                .contains("\"darkMode\":true")
                .contains("\"noteTextColor\":\"rgb(241,245,249)\"")
                .contains("\"actorBkg\":\"rgb(65,75,92)\"")
                .contains("\"actorLineColor\":\"rgb(241,245,249)\"")
                .contains("\"primaryColor\":\"rgb(45,55,72)\"")
                .contains("\"cScale0\":\"rgb(45,55,72)\"")
                .contains("\"git0\":\"rgb(145,155,170)\"")
                .contains("\"quadrant1Fill\":\"rgb(55,65,81)\"")
                .contains("\"taskBkgColor\":\"rgb(45,55,72)\"")
                .contains(".diagram svg .mindmap-node.section-root rect")
                .contains("fill: rgb(45,55,72) !important")
                .contains(".diagram svg rect.actor")
                .contains("fill: rgb(65,75,92) !important; stroke: rgb(241,245,249) !important");
    }

    @Test
    @DisplayName("Missing or unsafe theme colors are rejected instead of replaced with fixed colors")
    void parsePayload_whenThemeColorsAreInvalid_throwsException() {
        assertThatThrownBy(() -> DiagramHtmlExporter.parsePayload("""
                {"type":"mermaid","title":"Diagram","source":"flowchart TD\\nA-->B","pageBackground":"#fafafa","sourceBackground":"#eeeeee","background":"red;position:fixed","color":"url(x)","borderColor":"expression(x)","themeVariables":{"darkMode":false,"primaryColor":"#ffffff"}}
                """))
                .isInstanceOf(IOException.class)
                .hasMessage("Diagram theme color is missing or invalid: background");
    }

    @Test
    @DisplayName("Missing resolved theme variables are rejected instead of using Mermaid defaults")
    void parsePayload_whenThemeVariablesAreMissing_throwsException() {
        assertThatThrownBy(() -> DiagramHtmlExporter.parsePayload("""
                {"type":"mermaid","title":"Diagram","source":"flowchart TD\\nA-->B","pageBackground":"#fafafa","sourceBackground":"#eeeeee","background":"#f4f4f4","color":"#111111","borderColor":"#cccccc"}
                """))
                .isInstanceOf(IOException.class)
                .hasMessage("Diagram theme variables are missing.");
    }

    @Test
    @DisplayName("Export writes a complete temporary HTML document")
    void exportMermaidHtml_whenPayloadIsValid_writesDocument() throws Exception {
        var path = DiagramHtmlExporter.exportMermaidHtml(payload("sequenceDiagram\nA->>B: hello"));
        try {
            assertThat(path).isRegularFile();
            assertThat(Files.readString(path))
                    .contains("<!doctype html>")
                    .contains("sequenceDiagram")
                    .contains("mermaid.initialize");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static String payload(String source) {
        String escaped = source.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
        return """
                {"type":"mermaid","title":"Mermaid Diagram","source":"%s","pageBackground":"#fafafa","sourceBackground":"#eeeeee","background":"#f4f4f4","color":"#111111","borderColor":"#cccccc","themeVariables":{"darkMode":false,"background":"#fafafa","textColor":"#111111","primaryColor":"#ffffff","primaryTextColor":"#111111","primaryBorderColor":"#cccccc","secondaryColor":"#eeeeee","tertiaryColor":"#e5e5e5","lineColor":"#777777","edgeLabelBackground":"#f4f4f4","actorBkg":"#eeeeee","actorBorder":"#777777","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#777777","activationBkgColor":"#e5e5e5","activationBorderColor":"#777777","branchLabelColor":"#111111"}}
                """.formatted(escaped);
    }
}
