package com.github.drafael.chat4j.chat.model;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.github.drafael.chat4j.provider.api.ProviderModelInfo;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ModelInfoPopupTest {

    @Test
    @DisplayName("The model card renders untrusted markup as text and omits unknown fields")
    void content_partialMetadata_showsOnlyKnownValuesAsPlainText() throws Exception {
        var info = ProviderModelInfo.builder().modelId("vendor/model")
                .displayName("<html>Model</html>").description("<script>not markup</script>")
                .maxInputTokens(1000000L).inputUsdPerMillion(BigDecimal.ZERO).build();
        var subject = new AtomicReference<JPanel>();
        var text = new AtomicReference<String>();
        SwingUtilities.invokeAndWait(() -> {
            var providerIcon = new ImageIcon(new BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB));
            subject.set(ModelInfoPopup.content(info, providerIcon));
            subject.get().setSize(subject.get().getPreferredSize());
            subject.get().doLayout();
            JPanel heading = (JPanel) subject.get().getComponent(0);
            heading.doLayout();
            JLabel provider = (JLabel) ((BorderLayout) heading.getLayout()).getLayoutComponent(BorderLayout.WEST);
            JTextArea name = (JTextArea) ((BorderLayout) heading.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            assertThat(provider.getIcon()).isSameAs(providerIcon);
            assertThat(provider.getX() + provider.getWidth()).isLessThan(name.getX());
            text.set(Arrays.stream(subject.get().getComponents())
                    .flatMap(component -> component instanceof JPanel panel ? Arrays.stream(panel.getComponents()) : Stream.of(component))
                    .filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                    .map(JTextArea::getText).reduce("", (left, right) -> "%s\n%s".formatted(left, right)));
        });

        assertThat(text.get()).contains(
                "<html>Model</html>",
                "<script>not markup</script>",
                "vendor/model",
                "Input: $0.00 / M tokens",
                "Max input:"
        ).doesNotContain("Output:", "Context window:", "Max output:");
    }

    @ParameterizedTest
    @ValueSource(ints = {13, 24})
    @DisplayName("The provider icon is centered on the first title line even when the model name wraps")
    void content_wrappedTitle_alignsIconWithFirstLine(int fontSize) throws Exception {
        var info = ProviderModelInfo.builder().modelId("provider/model")
                .displayName("Provider model with a long descriptive name that wraps across several lines. ".repeat(3)).build();
        SwingUtilities.invokeAndWait(() -> {
            LookAndFeel originalLookAndFeel = UIManager.getLookAndFeel();
            Object originalFont = UIManager.get("Label.font");
            try {
                assertThat(FlatLightLaf.setup()).isTrue();
                var font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
                UIManager.put("Label.font", font);
                int iconSize = new JLabel().getFontMetrics(font).getHeight() - 1;
                var icon = new ImageIcon(new BufferedImage(iconSize, iconSize, BufferedImage.TYPE_INT_ARGB));
                JPanel subject = ModelInfoPopup.content(info, icon);
                subject.setSize(subject.getPreferredSize());
                subject.doLayout();
                JPanel heading = (JPanel) subject.getComponent(0);
                heading.doLayout();
                JLabel provider = (JLabel) ((BorderLayout) heading.getLayout()).getLayoutComponent(BorderLayout.WEST);
                JTextArea title = (JTextArea) ((BorderLayout) heading.getLayout()).getLayoutComponent(BorderLayout.CENTER);
                try {
                    Rectangle2D firstLine = title.modelToView2D(0);
                    assertThat(title.getHeight()).isGreaterThan((int) firstLine.getHeight());
                    double textCenter = title.getY() + firstLine.getCenterY();
                    double iconCenter = provider.getY() + provider.getInsets().top + icon.getIconHeight() / 2.0;
                    assertThat(Math.abs(iconCenter - textCenter)).as("icon and first title line center difference")
                            .isLessThanOrEqualTo(1.0);
                } catch (BadLocationException e) {
                    throw new IllegalStateException(e);
                }
            } finally {
                assertThat(FlatLaf.setup(originalLookAndFeel)).isTrue();
                UIManager.put("Label.font", originalFont);
            }
        });
    }

    @Test
    @DisplayName("A model card that fits the screen does not hide its text and metadata behind a scrollbar")
    void showInfo_descriptionFitsScreen_keepsAllContentVisible() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A desktop display is required.");
        var info = ProviderModelInfo.builder().modelId("amazon/nova-premier-v1").displayName("Amazon: Nova Premier")
                .description("A model for complex reasoning, coding, and multimodal tasks. ".repeat(8))
                .inputUsdPerMillion(new BigDecimal("2.50")).outputUsdPerMillion(new BigDecimal("12.50"))
                .contextWindowTokens(1000000L).maxOutputTokens(32000L).build();
        SwingUtilities.invokeAndWait(() -> {
            Object originalFont = UIManager.get("Label.font");
            var owner = new JDialog();
            var subject = new ModelInfoPopup(owner);
            try {
                UIManager.put("Label.font", new Font(Font.SANS_SERIF, Font.PLAIN, 22));
                subject.showInfo(info, null, new Rectangle(100, 100, 340, 560), new Rectangle(100, 300, 340, 30));
                subject.validate();
                assertThat(subject.getHeight()).isGreaterThan(400);
                JScrollPane scroll = (JScrollPane) subject.getContentPane();
                assertThat(scroll.getVerticalScrollBar().isVisible())
                        .as("card %s, content preferred size %s", subject.getSize(), scroll.getViewport().getView().getPreferredSize())
                        .isFalse();
                assertThat(scroll.getViewport().getExtentSize().height)
                        .isGreaterThanOrEqualTo(scroll.getViewport().getView().getPreferredSize().height);
                JPanel content = (JPanel) scroll.getViewport().getView();
                Arrays.stream(content.getComponents())
                        .flatMap(component -> component instanceof JPanel panel ? Arrays.stream(panel.getComponents()) : Stream.of(component))
                        .filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                        .forEach(area -> {
                            try {
                                Rectangle2D lastLine = area.modelToView2D(area.getDocument().getLength());
                                assertThat(lastLine.getMaxY()).as("text area %s must contain its final line %s", area.getSize(), lastLine)
                                        .isLessThanOrEqualTo(area.getHeight());
                            } catch (BadLocationException e) {
                                throw new IllegalStateException(e);
                            }
                        });
            } finally {
                subject.dispose();
                owner.dispose();
                UIManager.put("Label.font", originalFont);
            }
        });
        SwingUtilities.invokeAndWait(() -> { });
    }

    @ParameterizedTest
    @CsvSource({"540, 300", "540, 1000", "320, 300", "320, 1000"})
    @DisplayName("Narrow-screen cards wrap all text within the viewport, including when a scrollbar is needed")
    void showInfo_narrowScreen_wrapsTextAtFinalViewportWidth(int screenWidth, int screenHeight) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A desktop display is required.");
        var info = ProviderModelInfo.builder().modelId("provider/model")
                .displayName("A provider model with a descriptive name that spans multiple lines and needs wrapping")
                .description("Synthetic public model description for layout verification. ".repeat(12))
                .inputUsdPerMillion(new BigDecimal("2.50")).contextWindowTokens(128000L).maxOutputTokens(32000L).build();
        SwingUtilities.invokeAndWait(() -> {
            LookAndFeel originalLookAndFeel = UIManager.getLookAndFeel();
            Object originalFont = UIManager.get("Label.font");
            var owner = new JDialog();
            var subject = new ModelInfoPopup(owner);
            try {
                assertThat(FlatLightLaf.setup()).isTrue();
                UIManager.put("Label.font", new Font(Font.SANS_SERIF, Font.PLAIN, 20));
                var icon = new ImageIcon(new BufferedImage(22, 22, BufferedImage.TYPE_INT_ARGB));
                subject.showInfo(info, icon, new Rectangle(100, 100, 340, 560), new Rectangle(100, 300, 340, 30));
                JScrollPane scroll = (JScrollPane) subject.getContentPane();
                // Exercise the production screen-fitting boundary without requiring a portrait monitor in CI.
                var fit = ModelInfoPopup.class.getDeclaredMethod("fitToScreen", JScrollPane.class, Dimension.class);
                fit.setAccessible(true);
                fit.invoke(null, scroll, new Dimension(screenWidth, screenHeight));
                subject.pack();
                subject.validate();
                assertThat(subject.getWidth()).isEqualTo(screenWidth);
                assertThat(subject.getHeight()).isLessThanOrEqualTo(screenHeight);
                assertThat(scroll.getVerticalScrollBar().isVisible()).isEqualTo(screenHeight == 300);
                JPanel content = (JPanel) scroll.getViewport().getView();
                assertThat(content.getWidth()).isEqualTo(scroll.getViewport().getExtentSize().width);
                Arrays.stream(content.getComponents())
                        .flatMap(component -> component instanceof JPanel panel ? Arrays.stream(panel.getComponents()) : Stream.of(component))
                        .filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                        .forEach(area -> {
                            Point origin = SwingUtilities.convertPoint(area, 0, 0, content);
                            IntStream.rangeClosed(0, area.getDocument().getLength()).forEach(offset -> {
                                try {
                                    Rectangle2D character = area.modelToView2D(offset);
                                    assertThat(origin.x + character.getMaxX()).as("text must fit the visible viewport")
                                            .isLessThanOrEqualTo(scroll.getViewport().getExtentSize().width);
                                    assertThat(character.getMaxY()).as("wrapped text must fit its measured height")
                                            .isLessThanOrEqualTo(area.getHeight());
                                } catch (BadLocationException e) {
                                    throw new IllegalStateException(e);
                                }
                            });
                        });
                assertThat(Arrays.stream(content.getComponents()).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                        .map(JTextArea::getText).toList()).contains("Input: $2.50 / M tokens");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            } finally {
                subject.dispose();
                owner.dispose();
                assertThat(FlatLaf.setup(originalLookAndFeel)).isTrue();
                UIManager.put("Label.font", originalFont);
            }
        });
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    @DisplayName("The card appears to the right when space is available")
    void position_spaceOnRight_placesCardBesideSelector() {
        Rectangle subject = ModelInfoPopup.position(new Rectangle(100, 100, 340, 560),
                new Rectangle(100, 300, 340, 30), new Dimension(350, 200), new Rectangle(0, 0, 1920, 1080));

        assertThat(subject).isEqualTo(new Rectangle(448, 300, 350, 200));
    }

    @Test
    @DisplayName("The card flips left and stays above the bottom of a secondary screen")
    void position_nearScreenEdges_flipsAndClampsToUsableBounds() {
        Rectangle subject = ModelInfoPopup.position(new Rectangle(-600, 100, 340, 560),
                new Rectangle(-600, 750, 340, 30), new Dimension(350, 200), new Rectangle(-1280, 0, 1280, 800));

        assertThat(subject).isEqualTo(new Rectangle(-958, 600, 350, 200));
    }

    @Test
    @DisplayName("Oversized model descriptions remain scrollable within the screen")
    void position_contentExceedsScreen_constrainsCardSize() {
        Rectangle subject = ModelInfoPopup.position(new Rectangle(0, 0, 340, 560),
                new Rectangle(0, 0, 340, 30), new Dimension(1000, 2000), new Rectangle(0, 0, 320, 300));

        assertThat(subject).isEqualTo(new Rectangle(0, 0, 320, 300));
    }
}
