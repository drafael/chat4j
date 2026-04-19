package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderMenuIconResolverTest {

    @Test
    @DisplayName("Resolve model icon returns icon for known provider")
    void resolveModelIcon_whenProviderKnown_returnsIcon() {
        var subject = new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), ProviderMenuIconResolverTest.class);

        var icon = subject.resolveModelIcon("OpenAI", new JRadioButtonMenuItem("gpt-4.1"), true);

        assertThat(icon).isNotNull();
    }

    @Test
    @DisplayName("Resolve header icon returns icon for known provider")
    void resolveHeaderIcon_whenProviderKnown_returnsIcon() {
        var subject = new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), ProviderMenuIconResolverTest.class);

        var icon = subject.resolveHeaderIcon("OpenAI", new JMenuItem("OpenAI"), true);

        assertThat(icon).isNotNull();
    }

    @Test
    @DisplayName("Resolve methods return null for unknown provider")
    void resolve_whenProviderUnknown_returnsNull() {
        var subject = new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), ProviderMenuIconResolverTest.class);

        assertThat(subject.resolveModelIcon("Unknown", new JRadioButtonMenuItem("x"), true)).isNull();
        assertThat(subject.resolveHeaderIcon("Unknown", new JMenuItem("x"), true)).isNull();
    }

    @Test
    @DisplayName("Constructor rejects null dependencies")
    void constructor_whenNullArgumentsProvided_throwsException() {
        assertThatThrownBy(() -> new ProviderMenuIconResolver(null, ProviderMenuIconResolverTest.class))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("providerMenuIconTintResolver");

        assertThatThrownBy(() -> new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("resourceAnchor");
    }
}
