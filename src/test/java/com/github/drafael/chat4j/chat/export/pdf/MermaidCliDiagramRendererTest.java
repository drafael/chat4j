package com.github.drafael.chat4j.chat.export.pdf;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MermaidCliDiagramRendererTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("Blank and oversized Mermaid sources are rejected without starting Chromium")
    void render_whenSourceIsBlankOrOversized_preservesSourceWithoutProcess() throws Exception {
        PdfExportProcessRunner processRunner = mock(PdfExportProcessRunner.class);
        var subject = new MermaidCliDiagramRenderer("mmdc", Map.of(), processRunner);

        MermaidCliDiagramRenderer.Result blank = subject.render("  ", tempDirectory, 0, 0, () -> false);
        MermaidCliDiagramRenderer.Result oversized = subject.render(
                "x".repeat(MermaidCliDiagramRenderer.MAX_SOURCE_LENGTH + 1),
                tempDirectory,
                0,
                1,
                () -> false
        );

        assertThat(blank.failure()).isEqualTo(MermaidCliDiagramRenderer.Failure.BLANK);
        assertThat(oversized.failure()).isEqualTo(MermaidCliDiagramRenderer.Failure.TOO_LARGE);
        verifyNoInteractions(processRunner);
    }

    @Test
    @DisplayName("External Mermaid resources are rejected before the CLI can start")
    void render_whenSourceContainsExternalResource_rejectsEverySupportedScheme() throws Exception {
        List<String> sources = List.of(
                "flowchart LR\nA[http://example.com/image.png]",
                "flowchart LR\nA[https://example.com/image.png]",
                "flowchart LR\nA[file:/private/image.png]",
                "flowchart LR\nA[ftp://example.com/image.png]",
                "flowchart LR\nA[data:image/png;base64,AAAA]",
                "flowchart LR\nA[//example.com/image.png]",
                "flowchart LR\nA[https&#58;//example.com/image.png]",
                "flowchart LR\nA[&#104;&#116;&#116;&#112;&#115;&#58;//example.com/image.png]",
                "flowchart LR\nA[&#47;&#47;example.com/image.png]",
                "flowchart LR\nA[\"<img src='/Users/example/private.png'>\"]",
                "flowchart LR\nA@{ img: '../private.png' }",
                "flowchart LR\nclassDef private background-image: url(../private.png)",
                "flowchart LR\nclassDef private background-image: u\\72 l(../private.png)",
                "flowchart LR\nA[\"<style>@import '../private.css';</style>\"]",
                "flowchart LR\nA[![private](../private.png)]"
        );
        for (String source : sources) {
            PdfExportProcessRunner processRunner = mock(PdfExportProcessRunner.class);
            var subject = new MermaidCliDiagramRenderer("mmdc", Map.of(), processRunner);

            MermaidCliDiagramRenderer.Result result = subject.render(source, tempDirectory, 0, 0, () -> false);

            assertThat(result.failure())
                    .as(source)
                    .isEqualTo(MermaidCliDiagramRenderer.Failure.RESOURCE_REFERENCE);
            verifyNoInteractions(processRunner);
        }
    }

    @Test
    @DisplayName("A rejected flowchart retries once with safely quoted labels")
    void render_whenFlowchartUsesBrowserRepairableLabels_matchesBrowserRendering() throws Exception {
        String source = """
                graph TD
                    A[Start Parsing] --> B{Identify Diagram Type};
                    B -- graph/flowchart --> C[Define Nodes (ID)];
                    C --> D[Define Connections (-->)];
                    D --> E[Render Diagram];
                """;
        List<String> submittedSources = new ArrayList<>();
        PdfExportProcessRunner processRunner = mock(PdfExportProcessRunner.class);
        when(processRunner.run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        )).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            Path input = Path.of(command.get(command.indexOf("--input") + 1));
            submittedSources.add(Files.readString(input));
            if (submittedSources.size() == 1) {
                return PdfExportProcessRunner.Outcome.completed(1, "Parse error");
            }
            writePng(outputPath(command), 600, 800);
            return PdfExportProcessRunner.Outcome.completed(0, "");
        });
        var subject = new MermaidCliDiagramRenderer("mmdc", Map.of(), processRunner);

        MermaidCliDiagramRenderer.Result result = subject.render(source, tempDirectory, 0, 0, () -> false);

        assertThat(result.successful()).isTrue();
        assertThat(submittedSources).containsExactly(source, """
                graph TD
                    A["Start Parsing"] --> B{"Identify Diagram Type"}
                    B -->|graph/flowchart| C["Define Nodes (ID)"]
                    C --> D["Define Connections (-->)"]
                    D --> E["Render Diagram"]
                """);
        verify(processRunner, times(2)).run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        );
    }

    @Test
    @DisplayName("A successful CLI render accepts only its expected PNG and reports adaptive size")
    void render_whenCliWritesValidPng_returnsManagedImage() throws Exception {
        PdfExportProcessRunner processRunner = successfulPngRunner(1_200, 500);
        var subject = new MermaidCliDiagramRenderer("/tools/mmdc", Map.of("SAFE", "true"), processRunner);

        MermaidCliDiagramRenderer.Result result = subject.render(
                "flowchart LR\nA --> B",
                tempDirectory,
                2,
                3,
                () -> false
        );

        assertThat(result.successful()).isTrue();
        assertThat(result.displaySize()).isEqualTo(MermaidCliDiagramRenderer.DisplaySize.MEDIUM);
        assertThat(result.png()).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
        assertThat(tempDirectory.resolve("mermaid-2-3.mmd")).hasContent("flowchart LR\nA --> B");
        assertThat(tempDirectory.resolve("publication-mermaid-config.json")).isRegularFile();
        assertThat(tempDirectory.resolve("publication-puppeteer-config.json")).isRegularFile();
    }

    @Test
    @DisplayName("A narrow tall diagram remains valid within the demonstrated LaTeX dimension boundary")
    void render_whenDiagramIsTall_acceptsPngWithinLatexBoundary() throws Exception {
        PdfExportProcessRunner processRunner = successfulPngRunner(100, 15_000);
        var subject = new MermaidCliDiagramRenderer("mmdc", Map.of(), processRunner);

        MermaidCliDiagramRenderer.Result result = subject.render(
                "flowchart TB\nA --> B",
                tempDirectory,
                0,
                0,
                () -> false
        );

        assertThat(result.successful()).isTrue();
        assertThat(result.displaySize()).isEqualTo(MermaidCliDiagramRenderer.DisplaySize.SMALL);
    }

    @Test
    @DisplayName("A diagram exceeding the demonstrated LaTeX dimension boundary falls back to source")
    void render_whenDiagramExceedsLatexDimension_returnsInvalidFailure() throws Exception {
        PdfExportProcessRunner processRunner = successfulPngRunner(100, 17_000);
        var subject = new MermaidCliDiagramRenderer("mmdc", Map.of(), processRunner);

        MermaidCliDiagramRenderer.Result result = subject.render(
                "flowchart TB\nA --> B",
                tempDirectory,
                0,
                0,
                () -> false
        );

        assertThat(result.failure()).isEqualTo(MermaidCliDiagramRenderer.Failure.INVALID);
    }

    @Test
    @DisplayName("Bundled Mermaid configuration fixes strict limits and keeps Chromium sandboxing enabled")
    void configuration_whenLoaded_enforcesApplicationOwnedSecurityPolicy() throws Exception {
        String mermaidConfig = resourceText("/web/export/pdf/publication-mermaid-config.json");
        String puppeteerConfig = resourceText("/web/export/pdf/publication-puppeteer-config.json");

        assertThat(mermaidConfig)
                .contains("\"securityLevel\": \"strict\"")
                .contains("\"maxTextSize\": 20000")
                .contains("\"maxEdges\": 500")
                .contains("\"deterministicIds\": true");
        assertThat(puppeteerConfig)
                .contains("--disable-background-networking")
                .contains("--no-proxy-server")
                .contains("--host-resolver-rules=MAP * ~NOTFOUND")
                .doesNotContain("--no-sandbox");
    }

    @Test
    @DisplayName("The Mermaid command keeps the configured executable as one direct token")
    void command_whenBuilt_usesOnlyFixedApplicationArguments() {
        var subject = new MermaidCliDiagramRenderer(
                "/Applications/Mermaid CLI/mmdc",
                Map.of(),
                new PdfExportProcessRunner()
        );
        Path input = tempDirectory.resolve("diagram.mmd");
        Path output = tempDirectory.resolve("diagram.png");

        List<String> command = subject.command(input, output, tempDirectory);

        assertThat(command)
                .startsWith("/Applications/Mermaid CLI/mmdc", "--input", input.toString(), "--output", output.toString())
                .contains("--configFile", tempDirectory.resolve("publication-mermaid-config.json").toString())
                .contains("--puppeteerConfigFile", tempDirectory.resolve("publication-puppeteer-config.json").toString())
                .contains("--width", "1200", "--height", "800", "--scale", "2", "--quiet")
                .doesNotContain("sh", "bash", "cmd.exe", "npx", "--no-sandbox");
    }

    @Test
    @DisplayName("Mermaid subprocesses receive only operational environment variables")
    void render_whenEnvironmentContainsCredentials_filtersSecretsBeforeStartup() throws Exception {
        var receivedEnvironment = new AtomicReference<Map<String, String>>();
        PdfExportProcessRunner processRunner = mock(PdfExportProcessRunner.class);
        when(processRunner.run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        )).thenAnswer(invocation -> {
            receivedEnvironment.set(invocation.getArgument(2));
            List<String> command = invocation.getArgument(0);
            writePng(outputPath(command), 600, 400);
            return PdfExportProcessRunner.Outcome.completed(0, "");
        });
        var subject = new MermaidCliDiagramRenderer(
                "mmdc",
                Map.of(
                        "PATH", "/tools",
                        "PUPPETEER_EXECUTABLE_PATH", "/tools/chrome",
                        "OPENAI_API_KEY", "secret",
                        "SERVICE_TOKEN", "secret",
                        "CLIENT_SECRET", "secret",
                        "LC_SECRET", "secret"
                ),
                processRunner
        );

        MermaidCliDiagramRenderer.Result result = subject.render(
                "flowchart LR\nA --> B",
                tempDirectory,
                0,
                0,
                () -> false
        );

        assertThat(result.successful()).isTrue();
        assertThat(receivedEnvironment.get())
                .containsEntry("PATH", "/tools")
                .containsEntry("PUPPETEER_EXECUTABLE_PATH", "/tools/chrome")
                .doesNotContainKeys("OPENAI_API_KEY", "SERVICE_TOKEN", "CLIENT_SECRET", "LC_SECRET");
    }

    @Test
    @DisplayName("Corrupt CLI output remains a readable source fallback")
    void render_whenCliWritesCorruptOutput_returnsInvalidFailure() throws Exception {
        PdfExportProcessRunner processRunner = mock(PdfExportProcessRunner.class);
        when(processRunner.run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        )).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            Files.writeString(outputPath(command), "not a png");
            return PdfExportProcessRunner.Outcome.completed(0, "");
        });
        var subject = new MermaidCliDiagramRenderer("mmdc", Map.of(), processRunner);

        MermaidCliDiagramRenderer.Result result = subject.render(
                "flowchart LR\nA --> B",
                tempDirectory,
                0,
                0,
                () -> false
        );

        assertThat(result.failure()).isEqualTo(MermaidCliDiagramRenderer.Failure.INVALID);
    }

    @Test
    @DisplayName("Missing output and ordinary syntax failures remain per-diagram source fallbacks")
    void render_whenCliDoesNotProduceValidDiagram_returnsInvalidFailure() throws Exception {
        PdfExportProcessRunner missingOutputRunner = mock(PdfExportProcessRunner.class);
        when(missingOutputRunner.run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        )).thenReturn(PdfExportProcessRunner.Outcome.completed(0, ""));
        PdfExportProcessRunner syntaxFailureRunner = mock(PdfExportProcessRunner.class);
        when(syntaxFailureRunner.run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        )).thenReturn(PdfExportProcessRunner.Outcome.completed(1, "Parse error"));

        MermaidCliDiagramRenderer.Result missingOutput = new MermaidCliDiagramRenderer(
                "mmdc",
                Map.of(),
                missingOutputRunner
        ).render("flowchart LR\nA --> B", tempDirectory, 0, 0, () -> false);
        MermaidCliDiagramRenderer.Result syntaxFailure = new MermaidCliDiagramRenderer(
                "mmdc",
                Map.of(),
                syntaxFailureRunner
        ).render("flowchart LR\nA -->", tempDirectory, 0, 1, () -> false);

        assertThat(missingOutput.failure()).isEqualTo(MermaidCliDiagramRenderer.Failure.INVALID);
        assertThat(syntaxFailure.failure()).isEqualTo(MermaidCliDiagramRenderer.Failure.INVALID);
    }

    @Test
    @DisplayName("An executable startup failure disables the unavailable renderer without exposing its path")
    void render_whenExecutableCannotStart_returnsUnavailableFailure() throws Exception {
        PdfExportProcessRunner processRunner = mock(PdfExportProcessRunner.class);
        when(processRunner.run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        )).thenThrow(new IOException("/private/tools/mmdc does not exist"));
        var subject = new MermaidCliDiagramRenderer("/private/tools/mmdc", Map.of(), processRunner);

        MermaidCliDiagramRenderer.Result result = subject.render(
                "flowchart LR\nA --> B",
                tempDirectory,
                0,
                0,
                () -> false
        );
        Optional<String> reason = subject.unavailableReason(() -> false);

        assertThat(result.failure()).isEqualTo(MermaidCliDiagramRenderer.Failure.UNAVAILABLE);
        assertThat(reason)
                .hasValue("Mermaid CLI is unavailable or could not be started.")
                .hasValueSatisfying(message -> assertThat(message).doesNotContain("/private"));
    }

    @Test
    @DisplayName("Mermaid preflight explains why Windows command scripts are unsupported")
    void unavailableReason_whenLauncherRequiresShell_returnsActionableGuidance() throws Exception {
        Path launcher = tempDirectory.resolve("mmdc.cmd");
        Files.writeString(launcher, "echo unsupported");
        var subject = new MermaidCliDiagramRenderer(
                launcher.toString(),
                Map.of(),
                new PdfExportProcessRunner()
        );

        assertThat(subject.unavailableReason(() -> false))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("directly executable")
                        .contains(".cmd")
                        .contains("unsupported"));
    }

    @Test
    @DisplayName("CLI timeout and cancellation remain distinct export outcomes")
    void render_whenProcessStopsEarly_distinguishesTimeoutAndCancellation() throws Exception {
        PdfExportProcessRunner timeoutRunner = mock(PdfExportProcessRunner.class);
        when(timeoutRunner.run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        )).thenReturn(PdfExportProcessRunner.Outcome.timedOut("timeout"));
        PdfExportProcessRunner cancelledRunner = mock(PdfExportProcessRunner.class);
        when(cancelledRunner.run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        )).thenReturn(PdfExportProcessRunner.Outcome.cancelledOutcome());

        MermaidCliDiagramRenderer.Result timeout = new MermaidCliDiagramRenderer(
                "mmdc",
                Map.of(),
                timeoutRunner
        ).render("flowchart LR\nA --> B", tempDirectory, 0, 0, () -> false);
        MermaidCliDiagramRenderer.Result cancelled = new MermaidCliDiagramRenderer(
                "mmdc",
                Map.of(),
                cancelledRunner
        ).render("flowchart LR\nA --> B", tempDirectory, 0, 1, () -> false);

        assertThat(timeout.failure()).isEqualTo(MermaidCliDiagramRenderer.Failure.TIMEOUT);
        assertThat(cancelled.cancelled()).isTrue();
    }

    @Test
    @DisplayName("Unexpected thread interruption propagates instead of producing a partial export")
    void render_whenThreadIsInterrupted_propagatesInterruption() throws Exception {
        PdfExportProcessRunner processRunner = mock(PdfExportProcessRunner.class);
        when(processRunner.run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        )).thenThrow(new InterruptedException("interrupted"));
        var subject = new MermaidCliDiagramRenderer("mmdc", Map.of(), processRunner);
        var failure = new AtomicReference<Throwable>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                subject.render("flowchart LR\nA --> B", tempDirectory, 0, 0, () -> false);
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        worker.join(Duration.ofSeconds(5));

        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(InterruptedException.class);
    }

    @Test
    @DisplayName("A missing Chromium runtime disables repeated CLI startup attempts for the export")
    void render_whenBrowserRuntimeIsMissing_marksRendererUnavailable() throws Exception {
        PdfExportProcessRunner processRunner = mock(PdfExportProcessRunner.class);
        when(processRunner.run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        )).thenReturn(PdfExportProcessRunner.Outcome.completed(
                1,
                "Could not find Chrome (ver. 131); chrome-headless-shell is unavailable"
        ));
        var subject = new MermaidCliDiagramRenderer("mmdc", Map.of(), processRunner);

        MermaidCliDiagramRenderer.Result first = subject.render(
                "flowchart LR\nA --> B",
                tempDirectory,
                0,
                0,
                () -> false
        );
        MermaidCliDiagramRenderer.Result second = subject.render(
                "flowchart LR\nB --> C",
                tempDirectory,
                0,
                1,
                () -> false
        );

        assertThat(first.failure()).isEqualTo(MermaidCliDiagramRenderer.Failure.UNAVAILABLE);
        assertThat(second.failure()).isEqualTo(MermaidCliDiagramRenderer.Failure.UNAVAILABLE);
        verify(processRunner, times(1)).run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        );
    }

    @Test
    @DisplayName("Mermaid CLI preflight accepts version 11 and rejects other major versions")
    void unavailableReason_whenVersionIsChecked_enforcesSupportedMajor() throws Exception {
        PdfExportProcessRunner supportedRunner = versionRunner("11.16.0");
        PdfExportProcessRunner unsupportedRunner = versionRunner("12.0.1");
        PdfExportProcessRunner malformedRunner = versionRunner("unknown version");

        var supported = new MermaidCliDiagramRenderer("mmdc", Map.of(), supportedRunner);
        var unsupported = new MermaidCliDiagramRenderer("mmdc", Map.of(), unsupportedRunner);
        var malformed = new MermaidCliDiagramRenderer("mmdc", Map.of(), malformedRunner);

        assertThat(supported.unavailableReason(() -> false)).isEmpty();
        assertThat(unsupported.unavailableReason(() -> false))
                .hasValueSatisfying(reason -> assertThat(reason).contains("11.x").contains("12.x"));
        assertThat(malformed.unavailableReason(() -> false))
                .hasValueSatisfying(reason -> assertThat(reason).contains("unrecognized version"));
    }

    @Test
    @DisplayName("Rendered width maps to small, medium, and large publication sizes")
    void displaySize_whenWidthChanges_scalesDiagramProportionally() {
        var subject = new MermaidCliDiagramRenderer("mmdc", Map.of(), new PdfExportProcessRunner());

        assertThat(subject.displaySize(800)).isEqualTo(MermaidCliDiagramRenderer.DisplaySize.SMALL);
        assertThat(subject.displaySize(1_200)).isEqualTo(MermaidCliDiagramRenderer.DisplaySize.MEDIUM);
        assertThat(subject.displaySize(2_000)).isEqualTo(MermaidCliDiagramRenderer.DisplaySize.LARGE);
    }

    private String resourceText(String path) throws Exception {
        try (var input = MermaidCliDiagramRendererTest.class.getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private PdfExportProcessRunner successfulPngRunner(int width, int height) throws Exception {
        PdfExportProcessRunner processRunner = mock(PdfExportProcessRunner.class);
        when(processRunner.run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        )).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            writePng(outputPath(command), width, height);
            return PdfExportProcessRunner.Outcome.completed(0, "");
        });
        return processRunner;
    }

    private PdfExportProcessRunner versionRunner(String version) throws Exception {
        PdfExportProcessRunner processRunner = mock(PdfExportProcessRunner.class);
        when(processRunner.run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        )).thenReturn(PdfExportProcessRunner.Outcome.completed(0, version));
        return processRunner;
    }

    private Path outputPath(List<String> command) {
        return Path.of(command.get(command.indexOf("--output") + 1));
    }

    private void writePng(Path output, int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLACK);
            graphics.drawLine(10, 10, width - 10, height - 10);
        } finally {
            graphics.dispose();
        }
        assertThat(ImageIO.write(image, "png", output.toFile())).isTrue();
    }
}
