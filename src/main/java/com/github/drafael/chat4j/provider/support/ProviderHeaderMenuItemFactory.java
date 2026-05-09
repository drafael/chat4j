package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.util.Fonts;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import javax.swing.Icon;
import javax.swing.JMenuItem;
import java.awt.Font;

public class ProviderHeaderMenuItemFactory {

    private static final int HEADER_ICON_TEXT_GAP = 10;

    private final HeaderIconResolver headerIconResolver;

    public ProviderHeaderMenuItemFactory(@NonNull HeaderIconResolver headerIconResolver) {
        this.headerIconResolver = headerIconResolver;
    }

    public JMenuItem create(String providerName, String text, boolean enabled) {
        JMenuItem header = new JMenuItem();
        header.setEnabled(false);
        Fonts.apply(header, Font.BOLD, Fonts.SIZE_BODY);
        header.setIconTextGap(HEADER_ICON_TEXT_GAP);
        update(header, providerName, text, enabled);
        return header;
    }

    public void update(@NonNull JMenuItem header, String providerName, String text, boolean enabled) {
        Validate.notBlank(providerName, "providerName must not be blank");
        Validate.notBlank(text, "text must not be blank");

        header.setText(text);
        header.setIcon(headerIconResolver.resolve(providerName, header, enabled));
    }

    @FunctionalInterface
    public interface HeaderIconResolver {
        Icon resolve(String providerName, JMenuItem item, boolean enabled);
    }
}
