package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderModelMenuItemFactoryTest {

    @Test
    @DisplayName("Create assigns the Together icon and wires model selection")
    void create_whenCalled_returnsConfiguredTogetherModelItem() throws Exception {
        var selectedModelKey = new AtomicReference<String>();

        SwingUtilities.invokeAndWait(() -> {
            var iconResolver = new ProviderMenuIconResolver(
                    new ProviderMenuIconTintResolver(),
                    ProviderModelMenuItemFactoryTest.class
            );
            var subject = new ProviderModelMenuItemFactory(iconResolver);
            ProviderModelMenuItemFactory.CreatedModelItem created = subject.create(
                    "Together",
                    "Qwen/Qwen3.5-9B",
                    true,
                    selectedModelKey::set
            );

            assertThat(created.modelKey()).isEqualTo("Together > Qwen/Qwen3.5-9B");
            assertThat(created.item().getText()).isEqualTo("Qwen/Qwen3.5-9B");
            assertThat(created.item().isEnabled()).isTrue();
            assertThat(created.item().getIcon()).isNotNull();
            assertThat(created.item().getIconTextGap()).isEqualTo(8);

            created.item().doClick();
        });
        assertThat(selectedModelKey.get()).isEqualTo("Together > Qwen/Qwen3.5-9B");
    }

    @Test
    @DisplayName("Create rejects blank provider/model and null callback")
    void create_whenArgumentsInvalid_throwsException() {
        var iconResolver = new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), ProviderModelMenuItemFactoryTest.class);
        var subject = new ProviderModelMenuItemFactory(iconResolver);

        assertThatThrownBy(() -> subject.create("  ", "gpt", true, key -> {
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerName must not be blank");

        assertThatThrownBy(() -> subject.create("OpenAI", "  ", true, key -> {
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId must not be blank");

        assertThatThrownBy(() -> subject.create("OpenAI", "gpt", true, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("onSelected");
    }
}
