package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JMenuItem;
import java.awt.Component;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderHeaderMenuItemFactoryTest {

    @Test
    @DisplayName("Create initializes provider header item style and icon via resolver")
    void create_whenCalled_initializesHeaderItem() {
        RecordingHeaderIconResolver resolver = new RecordingHeaderIconResolver();
        var subject = new ProviderHeaderMenuItemFactory(resolver);

        JMenuItem header = subject.create("Ollama", "Ollama (offline)", false);

        assertThat(header.isEnabled()).isFalse();
        assertThat(header.getIconTextGap()).isEqualTo(10);
        assertThat(header.getText()).isEqualTo("Ollama (offline)");
        assertThat(header.getIcon()).isNotNull();
        assertThat(resolver.requests).containsExactly("Ollama:false");
    }

    @Test
    @DisplayName("Update refreshes text and icon for existing provider header item")
    void update_whenCalled_updatesHeaderTextAndIcon() {
        RecordingHeaderIconResolver resolver = new RecordingHeaderIconResolver();
        var subject = new ProviderHeaderMenuItemFactory(resolver);
        JMenuItem header = new JMenuItem();

        subject.update(header, "OpenAI", "OpenAI", true);

        assertThat(header.getText()).isEqualTo("OpenAI");
        assertThat(header.getIcon()).isNotNull();
        assertThat(resolver.requests).containsExactly("OpenAI:true");
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
