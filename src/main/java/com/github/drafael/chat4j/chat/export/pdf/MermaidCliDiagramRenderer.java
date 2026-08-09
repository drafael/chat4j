package com.github.drafael.chat4j.chat.export.pdf;

import static java.util.Arrays.stream;
import static org.apache.commons.text.StringEscapeUtils.unescapeHtml4;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
final class MermaidCliDiagramRenderer {

    static final int MAX_SOURCE_LENGTH = 20_000;
    static final int OUTPUT_SCALE = 2;
    private static final int SMALL_MAX_LOGICAL_WIDTH = 400;
    private static final int MEDIUM_MAX_LOGICAL_WIDTH = 800;
    private static final long MAX_PNG_BYTES = 64L * 1_024 * 1_024;
    private static final long MAX_IMAGE_PIXELS = 40_000_000;
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RENDER_TIMEOUT = Duration.ofSeconds(30);
    private static final String MERMAID_CONFIG_RESOURCE = "/web/export/pdf/publication-mermaid-config.json";
    private static final String PUPPETEER_CONFIG_RESOURCE = "/web/export/pdf/publication-puppeteer-config.json";
    private static final String MERMAID_CONFIG_FILE = "publication-mermaid-config.json";
    private static final String PUPPETEER_CONFIG_FILE = "publication-puppeteer-config.json";
    private static final Pattern VERSION = Pattern.compile(
            "(?m)^\\s*v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?\\s*$"
    );
    private static final Pattern EXTERNAL_RESOURCE = Pattern.compile(
            "(?i)(?:\\b(?:https?|file|ftp|data)\\s*:|//[^\\s])"
    );
    private static final Pattern CSS_ESCAPE = Pattern.compile("\\\\([0-9a-fA-F]{1,6})\\s?");
    private static final Pattern RESOURCE_REFERENCE = Pattern.compile(
            "(?ism)(?:<\\s*(?:img|image|object|embed|iframe|link|script|style|video|audio|source|track|use|feimage|input)\\b"
                    + "|(?:\\{|,|^)\\s*(?:img|image)\\s*:"
                    + "|@import\\b"
                    + "|\\b(?:url|image-set)\\s*\\("
                    + "|!\\s*\\[)"
    );
    private static final Pattern FLOWCHART_HEADER = Pattern.compile("(?i)^(?:graph|flowchart)\\b");
    private static final Pattern TRAILING_SEMICOLON = Pattern.compile(";\\s*$");
    private static final Pattern EDGE_LABEL = Pattern.compile("--\\s+(.+?)\\s+-->");
    private static final Pattern SQUARE_NODE = Pattern.compile("(\\b[A-Za-z][A-Za-z0-9_]*)(\\[)([^]]+)(])");
    private static final Pattern DIAMOND_NODE = Pattern.compile("(\\b[A-Za-z][A-Za-z0-9_]*)(\\{)([^}]+)(})");
    private static final Pattern ROUND_NODE = Pattern.compile("(\\b[A-Za-z][A-Za-z0-9_]*)(\\()([^)]*)(\\))");
    private final String executable;
    private final Map<String, String> environment;
    private final PdfExportProcessRunner processRunner;
    private boolean unavailable;
    private Path preparedWorkspace;

    MermaidCliDiagramRenderer(
            String executable,
            @NonNull Map<String, String> environment,
            @NonNull PdfExportProcessRunner processRunner
    ) {
        this.executable = StringUtils.trimToEmpty(executable);
        this.environment = PdfExportProcessEnvironment.forMermaid(environment);
        this.processRunner = processRunner;
    }

    Optional<String> unavailableReason(@NonNull BooleanSupplier cancelled) {
        if (StringUtils.isBlank(executable)) {
            return Optional.empty();
        }
        try {
            PdfExportProcessRunner.Outcome outcome = processRunner.run(
                    List.of(executable, "--version"),
                    Path.of("").toAbsolutePath(),
                    environment,
                    cancelled,
                    VERSION_TIMEOUT,
                    "chat4j-mermaid-version-output"
            );
            if (outcome.status() == PdfExportProcessRunner.Status.CANCELLED || cancelled.getAsBoolean()) {
                return Optional.empty();
            }
            if (outcome.status() == PdfExportProcessRunner.Status.TIMED_OUT) {
                return Optional.of("Mermaid CLI did not respond to --version.");
            }
            if (outcome.exitCode() != 0) {
                return Optional.of("Mermaid CLI is unavailable or failed its version check.");
            }
            Matcher version = VERSION.matcher(outcome.diagnostics());
            if (!version.find()) {
                return Optional.of("Mermaid CLI returned an unrecognized version; version 11.x is required.");
            }
            String detectedMajor = version.group(1);
            return "11".equals(detectedMajor)
                    ? Optional.empty()
                    : Optional.of("Mermaid CLI version 11.x is required; found %s.x.".formatted(detectedMajor));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("Mermaid CLI version validation was interrupted.");
        } catch (IOException e) {
            if (PdfExportProcessRunner.DIRECT_EXECUTABLE_ERROR.equals(e.getMessage())) {
                return Optional.of(
                        "Mermaid CLI must be directly executable; Windows .cmd and .bat shell launchers are unsupported."
                );
            }
            return Optional.of("Mermaid CLI is unavailable or could not be started.");
        }
    }

    Result render(
            String source,
            @NonNull Path workspace,
            int turnIndex,
            int diagramIndex,
            @NonNull BooleanSupplier cancelled
    ) throws InterruptedException {
        String sourceText = StringUtils.defaultString(source);
        if (cancelled.getAsBoolean()) {
            return Result.cancelledResult();
        }
        if (StringUtils.isBlank(executable) || unavailable) {
            return Result.failure(Failure.UNAVAILABLE);
        }
        if (StringUtils.isBlank(sourceText)) {
            return Result.failure(Failure.BLANK);
        }
        if (sourceText.length() > MAX_SOURCE_LENGTH) {
            return Result.failure(Failure.TOO_LARGE);
        }
        if (hasExternalResource(sourceText)) {
            return Result.failure(Failure.RESOURCE_REFERENCE);
        }

        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        String stem = "mermaid-%d-%d".formatted(turnIndex, diagramIndex);
        Path input = normalizedWorkspace.resolve("%s.mmd".formatted(stem));
        Path output = normalizedWorkspace.resolve("%s.png".formatted(stem));
        try {
            prepareResources(normalizedWorkspace);
            long deadline = System.nanoTime() + RENDER_TIMEOUT.toNanos();
            Attempt attempt = renderAttempt(
                    sourceText,
                    input,
                    output,
                    normalizedWorkspace,
                    cancelled,
                    deadline
            );
            if (!attempt.sourceRejected()) {
                return attempt.result();
            }

            String repairedSource = repairFlowchartSource(sourceText);
            if (repairedSource.equals(sourceText)
                    || repairedSource.length() > MAX_SOURCE_LENGTH
                    || hasExternalResource(repairedSource)
            ) {
                return attempt.result();
            }
            log.debug("Retrying a Mermaid flowchart with safely quoted labels.");
            return renderAttempt(
                    repairedSource,
                    input,
                    output,
                    normalizedWorkspace,
                    cancelled,
                    deadline
            ).result();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (cancelled.getAsBoolean()) {
                return Result.cancelledResult();
            }
            throw e;
        } catch (IOException e) {
            unavailable = true;
            log.warn("Mermaid CLI rendering is unavailable.");
            return Result.failure(Failure.UNAVAILABLE);
        }
    }

    private Attempt renderAttempt(
            String source,
            Path input,
            Path output,
            Path workspace,
            BooleanSupplier cancelled,
            long deadline
    ) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) {
            return new Attempt(Result.cancelledResult(), false);
        }
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            return new Attempt(Result.failure(Failure.TIMEOUT), false);
        }
        Files.writeString(input, source, StandardCharsets.UTF_8);
        Files.deleteIfExists(output);
        PdfExportProcessRunner.Outcome outcome = processRunner.run(
                command(input, output, workspace),
                workspace,
                environment,
                cancelled,
                Duration.ofNanos(remainingNanos),
                "chat4j-mermaid-render-output"
        );
        if (outcome.status() == PdfExportProcessRunner.Status.CANCELLED || cancelled.getAsBoolean()) {
            return new Attempt(Result.cancelledResult(), false);
        }
        if (outcome.status() == PdfExportProcessRunner.Status.TIMED_OUT) {
            log.debug("Mermaid CLI rendering timed out.");
            return new Attempt(Result.failure(Failure.TIMEOUT), false);
        }
        if (!outcome.completedSuccessfully()) {
            if (rendererUnavailable(outcome.diagnostics())) {
                unavailable = true;
                log.warn("Mermaid CLI browser runtime is unavailable.");
                return new Attempt(Result.failure(Failure.UNAVAILABLE), false);
            }
            log.debug("Mermaid CLI rejected a diagram.");
            return new Attempt(Result.failure(Failure.INVALID), true);
        }
        return new Attempt(validatedOutput(output), false);
    }

    static String repairFlowchartSource(String source) {
        String sourceText = StringUtils.defaultString(source);
        String[] lines = sourceText.split("\\n", -1);
        String header = stream(lines)
                .map(String::trim)
                .filter(StringUtils::isNotEmpty)
                .findFirst()
                .orElse("");
        if (!FLOWCHART_HEADER.matcher(header).find()) {
            return sourceText;
        }
        for (int index = 0; index < lines.length; index++) {
            String repaired = TRAILING_SEMICOLON.matcher(lines[index]).replaceFirst("");
            repaired = replaceAll(EDGE_LABEL, repaired, match -> "-->|%s|".formatted(
                    sanitizeLabel(match.group(1))
            ));
            repaired = quoteNodeLabels(repaired, SQUARE_NODE);
            repaired = quoteNodeLabels(repaired, DIAMOND_NODE);
            lines[index] = quoteNodeLabels(repaired, ROUND_NODE);
        }
        return String.join("\n", lines);
    }

    private static String quoteNodeLabels(String source, Pattern pattern) {
        return replaceAll(pattern, source, match -> {
            String label = match.group(3).trim();
            if (label.length() >= 2 && label.startsWith("\"") && label.endsWith("\"")) {
                return match.group();
            }
            return "%s%s\"%s\"%s".formatted(
                    match.group(1),
                    match.group(2),
                    sanitizeLabel(label),
                    match.group(4)
            );
        });
    }

    private static String sanitizeLabel(String label) {
        return StringUtils.trimToEmpty(label)
                .replace('|', '/')
                .replace("&", "and")
                .replace("\"", "&quot;");
    }

    private static String replaceAll(Pattern pattern, String source, Function<Matcher, String> replacer) {
        Matcher matcher = pattern.matcher(source);
        StringBuilder result = new StringBuilder(source.length());
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacer.apply(matcher)));
        }
        return matcher.appendTail(result).toString();
    }

    List<String> command(Path input, Path output, Path workspace) {
        return List.of(
                executable,
                "--input", input.toString(),
                "--output", output.toString(),
                "--configFile", workspace.resolve(MERMAID_CONFIG_FILE).toString(),
                "--puppeteerConfigFile", workspace.resolve(PUPPETEER_CONFIG_FILE).toString(),
                "--backgroundColor", "white",
                "--width", "1200",
                "--height", "800",
                "--scale", Integer.toString(OUTPUT_SCALE),
                "--quiet"
        );
    }

    DisplaySize displaySize(int pixelWidth) {
        int logicalWidth = Math.max(1, pixelWidth / OUTPUT_SCALE);
        if (logicalWidth <= SMALL_MAX_LOGICAL_WIDTH) {
            return DisplaySize.SMALL;
        }
        return logicalWidth <= MEDIUM_MAX_LOGICAL_WIDTH ? DisplaySize.MEDIUM : DisplaySize.LARGE;
    }

    private boolean rendererUnavailable(String diagnostics) {
        String normalized = StringUtils.lowerCase(StringUtils.defaultString(diagnostics), Locale.ROOT);
        return normalized.contains("could not find chrome")
                || normalized.contains("could not find chromium")
                || normalized.contains("failed to launch the browser process")
                || normalized.contains("browser was not found")
                || normalized.contains("chrome-headless-shell");
    }

    private boolean hasExternalResource(String source) {
        String normalized = decodeCssEscapes(unescapeHtml4(unescapeHtml4(source)))
                .replaceAll("(?i)&colon;", ":")
                .replaceAll("(?i)&sol;", "/");
        return EXTERNAL_RESOURCE.matcher(normalized).find()
                || RESOURCE_REFERENCE.matcher(normalized).find();
    }

    private String decodeCssEscapes(String source) {
        Matcher matcher = CSS_ESCAPE.matcher(source);
        StringBuilder decoded = new StringBuilder(source.length());
        int previousEnd = 0;
        while (matcher.find()) {
            decoded.append(source, previousEnd, matcher.start());
            int codePoint = Integer.parseInt(matcher.group(1), 16);
            if (Character.isValidCodePoint(codePoint)) {
                decoded.appendCodePoint(codePoint);
            } else {
                decoded.append(matcher.group());
            }
            previousEnd = matcher.end();
        }
        return decoded.append(source, previousEnd, source.length()).toString();
    }

    private Result validatedOutput(Path output) {
        try {
            if (!Files.isRegularFile(output)) {
                return Result.failure(Failure.INVALID);
            }
            long fileSize = Files.size(output);
            if (fileSize == 0
                    || fileSize > MAX_PNG_BYTES
                    || PdfExportImageFormat.detect(output).orElse(null) != PdfExportImageFormat.PNG
            ) {
                return Result.failure(Failure.INVALID);
            }

            try (ImageInputStream imageInput = ImageIO.createImageInputStream(output.toFile())) {
                if (imageInput == null) {
                    return Result.failure(Failure.INVALID);
                }
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) {
                    return Result.failure(Failure.INVALID);
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(imageInput, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    long pixels = (long) width * height;
                    if (!"png".equalsIgnoreCase(reader.getFormatName())
                            || width <= 0
                            || height <= 0
                            || width > PandocConversationPdfExporter.MAX_LATEX_IMAGE_DIMENSION
                            || height > PandocConversationPdfExporter.MAX_LATEX_IMAGE_DIMENSION
                            || pixels > MAX_IMAGE_PIXELS
                    ) {
                        return Result.failure(Failure.INVALID);
                    }
                    if (reader.read(0) == null) {
                        return Result.failure(Failure.INVALID);
                    }
                    return Result.success(Files.readAllBytes(output), displaySize(width));
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException e) {
            log.debug("Mermaid CLI produced an invalid PNG.");
            return Result.failure(Failure.INVALID);
        }
    }

    private void prepareResources(Path workspace) throws IOException {
        if (workspace.equals(preparedWorkspace)) {
            return;
        }
        Files.createDirectories(workspace);
        copyRequiredResource(MERMAID_CONFIG_RESOURCE, workspace.resolve(MERMAID_CONFIG_FILE));
        copyRequiredResource(PUPPETEER_CONFIG_RESOURCE, workspace.resolve(PUPPETEER_CONFIG_FILE));
        preparedWorkspace = workspace;
    }

    private void copyRequiredResource(String resourceName, Path target) throws IOException {
        try (InputStream input = MermaidCliDiagramRenderer.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Required Mermaid rendering resource is unavailable");
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    enum Failure {
        BLANK,
        TOO_LARGE,
        RESOURCE_REFERENCE,
        INVALID,
        UNAVAILABLE,
        TIMEOUT
    }

    enum DisplaySize {
        SMALL,
        MEDIUM,
        LARGE
    }

    private record Attempt(Result result, boolean sourceRejected) {
    }

    record Result(byte[] png, Failure failure, DisplaySize displaySize, boolean cancelled) {

        static Result success(byte[] png, DisplaySize displaySize) {
            return new Result(png, null, displaySize, false);
        }

        static Result failure(Failure failure) {
            return new Result(null, failure, null, false);
        }

        static Result cancelledResult() {
            return new Result(null, null, null, true);
        }

        boolean successful() {
            return png != null;
        }
    }
}
