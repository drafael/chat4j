package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderModelMenuItemFactoryTest {

    @Test
    @DisplayName("Create builds model menu item with icon, enablement and selection callback")
    void create_whenCalled_returnsConfiguredModelItemAndWiresSelectionCallback() {
        var iconResolver = new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), ProviderModelMenuItemFactoryTest.class);
        var subject = new ProviderModelMenuItemFactory(iconResolver);
        var selectedModelKey = new AtomicReference<String>();

        ProviderModelMenuItemFactory.CreatedModelItem created = subject.create(
                "OpenAI",
                "gpt-4.1",
                true,
                selectedModelKey::set
        );

        assertThat(created.modelKey()).isEqualTo("OpenAI > gpt-4.1");
        assertThat(created.item().getText()).isEqualTo("gpt-4.1");
        assertThat(created.item().isEnabled()).isTrue();
        assertThat(created.item().getIcon()).isNotNull();
        assertThat(created.item().getIconTextGap()).isEqualTo(8);

        created.item().doClick();
        assertThat(selectedModelKey.get()).isEqualTo("OpenAI > gpt-4.1");
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
