package com.github.drafael.chat4j.chat.model;

import com.github.drafael.chat4j.util.Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSelectorButtonTest {

    @Test
    @DisplayName("Provider icon size remains compact and independent from provider text height")
    void providerIconSize_whenCalled_returnsCompactScaledSize() throws Exception {
        Method method = ModelSelectorButton.class.getDeclaredMethod("providerIconSize", FontMetrics.class);
        method.setAccessible(true);

        int size = (int) method.invoke(null, new Object[]{null});

        assertThat(size).isEqualTo(Fonts.scale(14));
    }

    @Test
    @DisplayName("Together selection resolves and paints the Together provider mark")
    void providerIcon_whenTogetherSelected_returnsRenderableTogetherIcon() throws Exception {
        var subject = callOnEdt(ModelSelectorButton::new);

        Icon icon = callOnEdt(() -> {
            subject.setSelection("Together", "Qwen/Qwen3.5-9B");
            Method method = ModelSelectorButton.class.getDeclaredMethod("providerIcon", int.class);
            method.setAccessible(true);
            return (Icon) method.invoke(subject, Fonts.scale(14));
        });

        assertThat(icon).isNotNull();
        long paintedPixels = callOnEdt(() -> {
            var image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                icon.paintIcon(subject, graphics, 0, 0);
            } finally {
                graphics.dispose();
            }
            long count = 0;
            for (int x = 0; x < image.getWidth(); x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    if ((image.getRGB(x, y) >>> 24) != 0) {
                        count++;
                    }
                }
            }
            return count;
        });
        assertThat(paintedPixels).isGreaterThan(0);
    }

    @Test
    @DisplayName("Empty-chat interaction text identifies the current model and direct selection")
    void setSelection_whenChatEmpty_showsDirectSelectionText() throws Exception {
        var subject = callOnEdt(ModelSelectorButton::new);

        callOnEdt(() -> {
            subject.setSelection("Anthropic", "claude-sonnet-4-6");
            return null;
        });

        assertThat(callOnEdt(() -> subject.getToolTipText()))
                .isEqualTo("Current model: Anthropic claude-sonnet-4-6. Select a model.");
        assertThat(callOnEdt(() -> subject.getAccessibleContext().getAccessibleName()))
                .isEqualTo("Current model: Anthropic claude-sonnet-4-6. Select a model.");
    }

    @Test
    @DisplayName("Non-empty-chat interaction text explains that another model starts a new chat")
    void setConversationHasMessages_whenTrue_describesNewChatBehavior() throws Exception {
        var subject = callOnEdt(ModelSelectorButton::new);

        callOnEdt(() -> {
            subject.setSelection("OpenAI", "gpt-5");
            subject.setConversationHasMessages(true);
            return null;
        });

        assertThat(callOnEdt(() -> subject.getToolTipText()))
                .isEqualTo("Current model: OpenAI gpt-5. Start a new chat with another model.");
        assertThat(callOnEdt(() -> subject.getAccessibleContext().getAccessibleName()))
                .isEqualTo("Current model: OpenAI gpt-5. Start a new chat with another model.");
    }

    @Test
    @DisplayName("Interaction text remains readable when no model is selected")
    void setSelection_whenMissing_showsActionWithoutBlankCurrentModel() throws Exception {
        var subject = callOnEdt(ModelSelectorButton::new);

        callOnEdt(() -> {
            subject.setSelection("", "");
            subject.setConversationHasMessages(true);
            return null;
        });

        assertThat(callOnEdt(() -> subject.getToolTipText())).isEqualTo("Start a new chat with another model.");
    }

    @Test
    @DisplayName("Preferred width can grow up to sixty percent of the title bar")
    void getPreferredSize_whenModelNameIsLong_capsAtSixtyPercentOfTitleBar() throws Exception {
        int preferredWidth = callOnEdt(() -> {
            var subject = new ModelSelectorButton();
            subject.setSelection(
                    "LM Studio",
                    "gemma-4-e4b-claude-abliterated-super-extra-long-experimental-model-name"
            );

            var titleBar = new JPanel(new BorderLayout());
            var centerPanel = new JPanel();
            titleBar.setSize(new Dimension(1000, 32));
            centerPanel.setSize(new Dimension(900, 32));
            centerPanel.add(subject);
            titleBar.add(centerPanel, BorderLayout.CENTER);
            return subject.getPreferredSize().width;
        });

        assertThat(preferredWidth).isEqualTo(600);
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }
}
