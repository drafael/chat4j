package com.github.drafael.chat4j.web;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.function.BooleanSupplier;

import static java.util.Collections.emptyList;

public class RawPromptWebQueryPlanner implements WebQueryPlanner {

    @Override
    public List<String> planQueries(String userPrompt, BooleanSupplier isCancelled) {
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            return emptyList();
        }

        String query = StringUtils.trimToEmpty(userPrompt);
        return StringUtils.isBlank(query) ? emptyList() : List.of(query);
    }
}
