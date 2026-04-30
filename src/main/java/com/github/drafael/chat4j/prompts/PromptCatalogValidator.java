package com.github.drafael.chat4j.prompts;

import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class PromptCatalogValidator {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]*");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("@\\{\\{([^}]*)}}");

    private PromptCatalogValidator() {
    }

    public static void validateOrThrow(@NonNull List<PromptTemplate> prompts) {
        List<String> errors = validate(prompts);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }
    }

    public static List<String> validate(@NonNull List<PromptTemplate> prompts) {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        prompts.forEach(prompt -> validatePrompt(prompt, ids, errors));
        return List.copyOf(errors);
    }

    public static Set<String> placeholders(String template) {
        Set<String> placeholders = new HashSet<>();
        var matcher = PLACEHOLDER_PATTERN.matcher(StringUtils.defaultString(template));
        while (matcher.find()) {
            String name = matcher.group(1);
            if (VARIABLE_PATTERN.matcher(name).matches()) {
                placeholders.add(name);
            }
        }
        return placeholders;
    }

    private static void validatePrompt(PromptTemplate prompt, Set<String> ids, List<String> errors) {
        String promptLabel = StringUtils.defaultIfBlank(prompt.title(), StringUtils.defaultIfBlank(prompt.id(), "Prompt"));

        if (StringUtils.isBlank(prompt.id())) {
            errors.add("%s ID is required".formatted(promptLabel));
        } else if (!ID_PATTERN.matcher(prompt.id()).matches()) {
            errors.add("%s ID must use lowercase letters, numbers, and dashes".formatted(promptLabel));
        } else if (!ids.add(prompt.id())) {
            errors.add("Duplicate prompt ID: %s".formatted(prompt.id()));
        }

        if (StringUtils.isBlank(prompt.title())) {
            errors.add("%s title is required".formatted(promptLabel));
        }
        if (StringUtils.isBlank(prompt.prompt())) {
            errors.add("%s prompt is required".formatted(promptLabel));
        }

        Set<String> variableNames = new HashSet<>();
        prompt.variables().forEach(variable -> validateVariable(promptLabel, variable, variableNames, errors));
        validateMalformedPlaceholders(promptLabel, prompt.prompt(), errors);
        Set<String> placeholders = placeholders(prompt.prompt());
        placeholders.stream()
                .filter(placeholder -> !variableNames.contains(placeholder))
                .sorted()
                .forEach(placeholder -> errors.add("%s references undefined variable: %s".formatted(promptLabel, placeholder)));
    }

    private static void validateMalformedPlaceholders(String promptLabel, String template, List<String> errors) {
        var matcher = PLACEHOLDER_PATTERN.matcher(StringUtils.defaultString(template));
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!VARIABLE_PATTERN.matcher(name).matches()) {
                errors.add("%s contains malformed placeholder: @{{%s}}".formatted(promptLabel, name));
            }
        }
    }

    private static void validateVariable(
            String promptLabel,
            PromptVariable variable,
            Set<String> variableNames,
            List<String> errors
    ) {
        if (StringUtils.isBlank(variable.name())) {
            errors.add("%s has a variable without a name".formatted(promptLabel));
            return;
        }
        if (!VARIABLE_PATTERN.matcher(variable.name()).matches()) {
            errors.add("%s variable must start with a letter and use letters, numbers, dashes, or underscores: %s"
                    .formatted(promptLabel, variable.name()));
        }
        if (!variableNames.add(variable.name())) {
            errors.add("%s has duplicate variable: %s".formatted(promptLabel, variable.name()));
        }
        if (variable.type() == PromptVariableType.SELECT && variable.options().stream().allMatch(StringUtils::isBlank)) {
            errors.add("%s select variable needs options: %s".formatted(promptLabel, variable.name()));
        }
    }
}
