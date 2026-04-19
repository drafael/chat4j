package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.Validate;

import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.util.Map;

public class ProviderMenuAvailabilityApplier {

    private final ProviderAvailabilityLabelFormatter providerAvailabilityLabelFormatter;

    public ProviderMenuAvailabilityApplier() {
        this(new ProviderAvailabilityLabelFormatter());
    }

    ProviderMenuAvailabilityApplier(ProviderAvailabilityLabelFormatter providerAvailabilityLabelFormatter) {
        this.providerAvailabilityLabelFormatter = Validate.notNull(
                providerAvailabilityLabelFormatter,
                "providerAvailabilityLabelFormatter must not be null"
        );
    }

    public void apply(
            Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            Map<String, JMenuItem> providerHeaderItemsByName,
            Map<String, Boolean> providerEnabledByName,
            IconResolver iconResolver
    ) {
        Validate.notNull(modelMenuItemsByKey, "modelMenuItemsByKey must not be null");
        Validate.notNull(providerHeaderItemsByName, "providerHeaderItemsByName must not be null");
        Validate.notNull(providerEnabledByName, "providerEnabledByName must not be null");
        Validate.notNull(iconResolver, "iconResolver must not be null");

        modelMenuItemsByKey.forEach((modelKey, item) -> ModelSelectionCodec.parse(modelKey).ifPresentOrElse(selection -> {
                    Boolean enabled = providerEnabledByName.get(selection.provider());
                    boolean selectable = enabled == null || enabled;
                    item.setEnabled(selectable);
                    item.setIcon(iconResolver.resolveModelIcon(selection.provider(), item, selectable));
                },
                () -> item.setEnabled(true)
        ));

        providerHeaderItemsByName.forEach((providerName, headerItem) -> {
            Boolean enabled = providerEnabledByName.get(providerName);
            boolean providerEnabled = enabled == null || enabled;
            headerItem.setText(providerAvailabilityLabelFormatter.format(providerName, providerEnabled));
            headerItem.setIcon(iconResolver.resolveHeaderIcon(providerName, headerItem, providerEnabled));
        });
    }

    public interface IconResolver {
        Icon resolveModelIcon(String providerName, JRadioButtonMenuItem item, boolean enabled);

        Icon resolveHeaderIcon(String providerName, JMenuItem item, boolean enabled);
    }
}
