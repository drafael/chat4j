package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.UIManager;
import java.awt.Color;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderMenuIconTintResolverTest {

    private final ProviderMenuIconTintResolver subject = new ProviderMenuIconTintResolver();

    @Test
    @DisplayName("Resolve uses fixed macOS menu colors when screen menu bar is enabled")
    void resolve_whenMacScreenMenuBarEnabled_usesFixedMacColors() {
        JMenuItem item = new JMenuItem();

        Color enabledTint = subject.resolve(item, true, true, true);
        Color disabledTint = subject.resolve(item, false, true, true);

        assertThat(enabledTint).isEqualTo(new Color(66, 66, 66));
        assertThat(disabledTint).isEqualTo(new Color(150, 150, 150));
    }

    @Test
    @DisplayName("Resolve uses menu item foreground for enabled non-mac tint")
    void resolve_whenNonMacEnabled_usesOpaqueMenuForeground() {
        JMenuItem item = new JMenuItem();
        item.setForeground(new Color(10, 20, 30, 40));

        Color tint = subject.resolve(item, true, false, false);

        assertThat(tint.getRed()).isEqualTo(10);
        assertThat(tint.getGreen()).isEqualTo(20);
        assertThat(tint.getBlue()).isEqualTo(30);
        assertThat(tint.getAlpha()).isEqualTo(255);
    }

    @Test
    @DisplayName("Resolve uses disabled foreground for non-mac disabled tint")
    void resolve_whenNonMacDisabled_usesDisabledForeground() {
        JMenuItem item = new JMenuItem();
        Object previous = UIManager.get("MenuItem.disabledForeground");

        try {
            UIManager.put("MenuItem.disabledForeground", new Color(90, 91, 92, 120));
            Color fromUi = subject.resolve(item, false, false, false);
            assertThat(fromUi).isEqualTo(new Color(90, 91, 92));
        } finally {
            UIManager.put("MenuItem.disabledForeground", previous);
        }
    }
}
