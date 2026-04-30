package com.github.drafael.chat4j.prompts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import java.util.List;

import static java.util.Collections.emptyList;

public record PromptVariable(String name, PromptVariableType type, List<String> options, String defaultValue) {

    @JsonCreator
    public PromptVariable(
            @JsonProperty("name") String name,
            @JsonProperty("type") PromptVariableType type,
            @JsonProperty("options") List<String> options,
            @JsonProperty("defaultValue") String defaultValue
    ) {
        this.name = StringUtils.trimToEmpty(name);
        this.type = type == null ? PromptVariableType.INPUT : type;
        this.options = options == null ? emptyList() : List.copyOf(options);
        this.defaultValue = StringUtils.defaultString(defaultValue);
    }

    public static PromptVariable input(String name) {
        Validate.notBlank(name, "name should not be blank");
        return new PromptVariable(name, PromptVariableType.INPUT, emptyList(), "");
    }

    public static PromptVariable select(String name, @NonNull List<String> options) {
        Validate.notBlank(name, "name should not be blank");
        return new PromptVariable(name, PromptVariableType.SELECT, options, "");
    }
}
