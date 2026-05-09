package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import lombok.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import static java.util.stream.Collectors.toMap;

public class ProviderSelectableResolver {

    public Map<String, Boolean> resolve(
            @NonNull List<ProviderRegistry.ProviderDef> providers,
            @NonNull Predicate<ProviderRegistry.ProviderDef> selectablePredicate
    ) {

        return providers.stream()
                .collect(toMap(
                        ProviderRegistry.ProviderDef::name,
                        selectablePredicate::test,
                        (existing, replacement) -> replacement,
                        LinkedHashMap::new
                ));
    }
}
