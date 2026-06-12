package com.github.drafael.chat4j.chat.diagram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagramHtmlExporterTest {

    @Test
    @DisplayName("Valid Mermaid SVG payload is exported as standalone HTML")
    void parsePayload_whenMermaidSvgIsSafe_returnsStandaloneHtml() throws Exception {
        String payload = """
                {"type":"mermaid","title":"Flow <One>","source":"flowchart TD\\nA-->B","svg":"<svg xmlns=\\"http://www.w3.org/2000/svg\\"><g><text>Hi</text></g></svg>"}
                """;

        DiagramHtmlExporter.DiagramPayload diagramPayload = DiagramHtmlExporter.parsePayload(payload);
        String html = DiagramHtmlExporter.toHtml(diagramPayload);

        assertThat(html)
                .contains("Content-Security-Policy")
                .contains("Flow &lt;One&gt;")
                .contains("<svg xmlns=\"http://www.w3.org/2000/svg\">")
                .contains("flowchart TD");
    }

    @Test
    @DisplayName("Unsafe SVG payloads are rejected")
    void parsePayload_whenSvgContainsActiveContent_throwsException() {
        String payload = """
                {"type":"mermaid","title":"Bad","svg":"<svg><script>alert(1)</script></svg>"}
                """;

        assertThatThrownBy(() -> DiagramHtmlExporter.parsePayload(payload))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("active content");
    }

    @Test
    @DisplayName("External SVG references are rejected")
    void parsePayload_whenSvgContainsExternalReference_throwsException() {
        String payload = """
                {"type":"mermaid","title":"Bad","svg":"<svg><a href=\\"https://example.com\\">x</a></svg>"}
                """;

        assertThatThrownBy(() -> DiagramHtmlExporter.parsePayload(payload))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("active content");
    }

    @Test
    @DisplayName("Protocol-relative SVG references are rejected")
    void parsePayload_whenSvgContainsProtocolRelativeReference_throwsException() {
        String payload = """
                {"type":"mermaid","title":"Bad","svg":"<svg><a href=\\"//example.com\\">x</a></svg>"}
                """;

        assertThatThrownBy(() -> DiagramHtmlExporter.parsePayload(payload))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("active content");
    }

    @Test
    @DisplayName("File SVG references are rejected")
    void parsePayload_whenSvgContainsFileReference_throwsException() {
        String payload = """
                {"type":"mermaid","title":"Bad","svg":"<svg><image href=\\"file:///tmp/leak.txt\\" /></svg>"}
                """;

        assertThatThrownBy(() -> DiagramHtmlExporter.parsePayload(payload))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("active content");
    }

    @Test
    @DisplayName("Mail SVG references are rejected")
    void parsePayload_whenSvgContainsMailReference_throwsException() {
        String payload = """
                {"type":"mermaid","title":"Bad","svg":"<svg><a href=\\"mailto:test@example.com\\">x</a></svg>"}
                """;

        assertThatThrownBy(() -> DiagramHtmlExporter.parsePayload(payload))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("active content");
    }

    @Test
    @DisplayName("Internal SVG fragment references are allowed")
    void parsePayload_whenSvgContainsInternalFragmentReference_returnsPayload() throws Exception {
        String payload = """
                {"type":"mermaid","title":"Good","svg":"<svg><defs><path id=\\"p\\" /></defs><use href=\\"#p\\" /></svg>"}
                """;

        DiagramHtmlExporter.DiagramPayload diagramPayload = DiagramHtmlExporter.parsePayload(payload);

        assertThat(diagramPayload.title()).isEqualTo("Good");
    }
}
