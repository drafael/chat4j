package com.github.drafael.chat4j.chat.export.pdf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class SmilesDiagramRendererTest {

    private final SmilesDiagramRenderer subject = SmilesDiagramRenderer.instance();

    @ParameterizedTest
    @MethodSource("representativeSmiles")
    @DisplayName("Representative SMILES structures render as print-quality PNG images")
    void render_whenSmilesIsValid_producesPrintQualityPng(String smiles) throws Exception {
        SmilesDiagramRenderer.Result result = subject.render(smiles);

        assertThat(result.successful()).isTrue();
        assertThat(result.failure()).isNull();
        assertThat(result.png()).isNotEmpty();
        var image = ImageIO.read(new ByteArrayInputStream(result.png()));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(SmilesDiagramRenderer.IMAGE_WIDTH);
        assertThat(image.getHeight()).isEqualTo(SmilesDiagramRenderer.IMAGE_HEIGHT);
        assertThat(nonWhitePixelCount(image)).isGreaterThan(1_000);
        assertThat(hasNonWhiteBorderPixel(image, 24)).isFalse();
    }

    @Test
    @DisplayName("SMILES display sizes grow with the estimated molecule atom count")
    void render_whenMoleculesHaveDifferentComplexity_assignsProportionalDisplaySizes() {
        assertThat(subject.render("O").displaySize()).isEqualTo(SmilesDiagramRenderer.DisplaySize.SMALL);
        assertThat(subject.render("PO(O)(O)O").displaySize()).isEqualTo(SmilesDiagramRenderer.DisplaySize.MEDIUM);
        assertThat(subject.render("CC(=O)Oc1ccccc1C(=O)O").displaySize())
                .isEqualTo(SmilesDiagramRenderer.DisplaySize.LARGE);
    }

    @Test
    @DisplayName("Repeated identical SMILES requests remain renderable")
    void render_whenSourceIsRepeated_producesBothDiagrams() {
        SmilesDiagramRenderer.Result first = subject.render("CCO");
        SmilesDiagramRenderer.Result second = subject.render("CCO");

        assertThat(first.successful()).isTrue();
        assertThat(second.successful()).isTrue();
        assertThat(second.png()).isEqualTo(first.png());
    }

    @Test
    @DisplayName("Concurrent SMILES requests are serialized without losing diagrams")
    void render_whenCallsAreConcurrent_producesEveryDiagram() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = List.of("CCO", "c1ccccc1", "C[N+](C)(C)C.[Cl-]").stream()
                    .map(smiles -> executor.submit(() -> subject.render(smiles)))
                    .toList();

            assertThat(futures).allSatisfy(future -> assertThat(future.get().successful()).isTrue());
        }
    }

    @Test
    @DisplayName("Invalid SMILES remains distinguishable from a renderer outage")
    void render_whenSmilesIsInvalid_reportsInvalidSource() {
        SmilesDiagramRenderer.Result result = subject.render("not a smiles");

        assertThat(result.successful()).isFalse();
        assertThat(result.failure()).isEqualTo(SmilesDiagramRenderer.Failure.INVALID);
        assertThat(result.png()).isNull();
    }

    @Test
    @DisplayName("Blank and oversized SMILES are rejected before rendering")
    void render_whenSourceIsBlankOrOversized_reportsInputFailure() {
        SmilesDiagramRenderer.Result blank = subject.render("   ");
        SmilesDiagramRenderer.Result oversized = subject.render("C".repeat(
                SmilesDiagramRenderer.MAX_SOURCE_LENGTH + 1
        ));

        assertThat(blank.failure()).isEqualTo(SmilesDiagramRenderer.Failure.BLANK);
        assertThat(oversized.failure()).isEqualTo(SmilesDiagramRenderer.Failure.TOO_LARGE);
    }

    private static Stream<String> representativeSmiles() {
        return Stream.of(
                "CCO",
                "CC(=O)Oc1ccccc1C(=O)O",
                "Cn1c(=O)c2c(ncn2C)n(C)c1=O",
                "c1ccccc1",
                "C[N+](C)(C)C.[Cl-]",
                "N[C@@H](C)C(=O)O"
        );
    }

    private long nonWhitePixelCount(BufferedImage image) {
        return IntStream.iterate(0, y -> y < image.getHeight(), y -> y + 4)
                .mapToLong(y -> IntStream.iterate(0, x -> x < image.getWidth(), x -> x + 4)
                        .filter(x -> !isWhite(image.getRGB(x, y)))
                        .count())
                .sum();
    }

    private boolean hasNonWhiteBorderPixel(BufferedImage image, int border) {
        return IntStream.range(0, image.getHeight()).anyMatch(y ->
                IntStream.range(0, image.getWidth()).anyMatch(x ->
                        (x < border || x >= image.getWidth() - border || y < border || y >= image.getHeight() - border)
                                && !isWhite(image.getRGB(x, y))
                )
        );
    }

    private boolean isWhite(int rgb) {
        return (rgb & 0x00ffffff) == 0x00ffffff;
    }
}
