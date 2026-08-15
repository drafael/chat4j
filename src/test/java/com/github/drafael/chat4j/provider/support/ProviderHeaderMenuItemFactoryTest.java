package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderHeaderMenuItemFactoryTest {

    @Test
    @DisplayName("Create initializes provider header item style and icon via resolver")
    void create_whenCalled_initializesHeaderItem() throws Exception {
        RecordingHeaderIconResolver resolver = new RecordingHeaderIconResolver();
        var subject = new ProviderHeaderMenuItemFactory(resolver);

        callOnEdt(() -> {
            JMenuItem header = subject.create("Ollama", "Ollama (offline)", false);

            assertThat(header.isEnabled()).isFalse();
            assertThat(header.getIconTextGap()).isEqualTo(10);
            assertThat(header.getText()).isEqualTo("Ollama (offline)");
            assertThat(header.getIcon()).isNotNull();
            return null;
        });
        assertThat(resolver.requests).containsExactly("Ollama:false");
    }

    @Test
    @DisplayName("Create assigns the actual Together icon to a provider header")
    void create_whenProviderIsTogether_assignsTogetherIcon() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var iconResolver = new ProviderMenuIconResolver(
                    new ProviderMenuIconTintResolver(),
                    ProviderHeaderMenuItemFactoryTest.class
            );
            var subject = new ProviderHeaderMenuItemFactory(iconResolver::resolveHeaderIcon);
            JMenuItem created = subject.create("Together", "Together", true);

            assertThat(created.getIcon()).isNotNull();
            assertThat(created.getText()).isEqualTo("Together");
        });
    }

    @Test
    @DisplayName("Update refreshes text and icon for existing provider header item")
    void update_whenCalled_updatesHeaderTextAndIcon() throws Exception {
        RecordingHeaderIconResolver resolver = new RecordingHeaderIconResolver();
        var subject = new ProviderHeaderMenuItemFactory(resolver);

        callOnEdt(() -> {
            JMenuItem header = new JMenuItem();
            subject.update(header, "OpenAI", "OpenAI", true);

            assertThat(header.getText()).isEqualTo("OpenAI");
            assertThat(header.getIcon()).isNotNull();
            return null;
        });
        assertThat(resolver.requests).containsExactly("OpenAI:true");
    }

    private static <T> T callOnEdt(Callable<T> action) throws Exception {
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

    private static class RecordingHeaderIconResolver implements ProviderHeaderMenuItemFactory.HeaderIconResolver {

        private final List<String> requests = new ArrayList<>();

        @Override
        public Icon resolve(String providerName, JMenuItem item, boolean enabled) {
            requests.add("%s:%s".formatted(providerName, enabled));
            return new DummyIcon();
        }
    }

    private static class DummyIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
        }

        @Override
        public int getIconWidth() {
            return 1;
        }

        @Override
        public int getIconHeight() {
            return 1;
        }
    }
}
