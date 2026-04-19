package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.awt.Component;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderMenuAvailabilityApplierTest {

    private final ProviderMenuAvailabilityApplier subject = new ProviderMenuAvailabilityApplier();

    @Test
    @DisplayName("Apply updates model items enabled state and provider header text/icon")
    void apply_whenAvailabilityProvided_updatesModelAndHeaderState() {
        Map<String, JRadioButtonMenuItem> modelItemsByKey = new LinkedHashMap<>();
        modelItemsByKey.put("Ollama > llama3", new JRadioButtonMenuItem("llama3"));
        modelItemsByKey.put("OpenAI > gpt-4.1", new JRadioButtonMenuItem("gpt-4.1"));

        Map<String, JMenuItem> providerHeadersByName = new LinkedHashMap<>();
        providerHeadersByName.put("Ollama", new JMenuItem());
        providerHeadersByName.put("OpenAI", new JMenuItem());

        Map<String, Boolean> availability = Map.of("Ollama", false);
        var iconResolver = new RecordingIconResolver();

        subject.apply(modelItemsByKey, providerHeadersByName, availability, iconResolver);

        assertThat(modelItemsByKey.get("Ollama > llama3").isEnabled()).isFalse();
        assertThat(modelItemsByKey.get("OpenAI > gpt-4.1").isEnabled()).isTrue();
        assertThat(providerHeadersByName.get("Ollama").getText()).isEqualTo("Ollama (offline)");
        assertThat(providerHeadersByName.get("OpenAI").getText()).isEqualTo("OpenAI");

        assertThat(iconResolver.modelIconRequests)
                .containsExactly("Ollama:false", "OpenAI:true");
        assertThat(iconResolver.headerIconRequests)
                .containsExactly("Ollama:false", "OpenAI:true");
    }

    @Test
    @DisplayName("Apply enables item with malformed model key")
    void apply_whenModelKeyMalformed_enablesItemWithoutModelIconLookup() {
        Map<String, JRadioButtonMenuItem> modelItemsByKey = Map.of(
                "malformed-key",
                new JRadioButtonMenuItem("Unknown")
        );

        var item = modelItemsByKey.get("malformed-key");
        item.setEnabled(false);

        var iconResolver = new RecordingIconResolver();
        subject.apply(modelItemsByKey, Map.of(), Map.of(), iconResolver);

        assertThat(item.isEnabled()).isTrue();
        assertThat(iconResolver.modelIconRequests).isEmpty();
    }

    private static class RecordingIconResolver implements ProviderMenuAvailabilityApplier.IconResolver {

        private final List<String> modelIconRequests = new ArrayList<>();
        private final List<String> headerIconRequests = new ArrayList<>();

        @Override
        public Icon resolveModelIcon(String providerName, JRadioButtonMenuItem item, boolean enabled) {
            modelIconRequests.add("%s:%s".formatted(providerName, enabled));
            return new DummyIcon();
        }

        @Override
        public Icon resolveHeaderIcon(String providerName, JMenuItem item, boolean enabled) {
            headerIconRequests.add("%s:%s".formatted(providerName, enabled));
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
