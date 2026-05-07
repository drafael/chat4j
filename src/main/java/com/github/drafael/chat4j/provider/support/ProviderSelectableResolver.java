package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.apache.commons.lang3.Validate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import static java.util.stream.Collectors.toMap;

public class ProviderSelectableResolver {

    public Map<String, Boolean> resolve(
            List<ProviderRegistry.ProviderDef> providers,
            Predicate<ProviderRegistry.ProviderDef> selectablePredicate
    ) {
        Validate.notNull(providers, "providers must not be null");
        Validate.notNull(selectablePredicate, "selectablePredicate must not be null");

        return providers.stream()
                .collect(toMap(
                        ProviderRegistry.ProviderDef::name,
                        selectablePredicate::test,
                        (existing, replacement) -> replacement,
                        LinkedHashMap::new
                ));
    }
}
