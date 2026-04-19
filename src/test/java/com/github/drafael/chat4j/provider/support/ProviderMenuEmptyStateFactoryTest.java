package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderMenuEmptyStateFactoryTest {

    private final ProviderMenuEmptyStateFactory subject = new ProviderMenuEmptyStateFactory();

    @Test
    @DisplayName("No providers item is disabled and contains expected text")
    void noProvidersAvailableItem_whenCalled_returnsDisabledMenuItem() {
        JMenuItem item = subject.noProvidersAvailableItem();

        assertThat(item.getText()).isEqualTo("No providers available");
        assertThat(item.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("No models item is disabled and contains expected text")
    void noModelsAvailableItem_whenCalled_returnsDisabledMenuItem() {
        JMenuItem item = subject.noModelsAvailableItem();

        assertThat(item.getText()).isEqualTo("No models available");
        assertThat(item.isEnabled()).isFalse();
    }
}
