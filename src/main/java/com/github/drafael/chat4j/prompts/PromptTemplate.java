package com.github.drafael.chat4j.prompts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

import static java.util.Collections.emptyList;

public record PromptTemplate(String id, String title, String prompt, String model, List<PromptVariable> variables) {

    public static final String DEFAULT_MODEL = "default";

    @JsonCreator
    public PromptTemplate(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("prompt") String prompt,
            @JsonProperty("model") String model,
            @JsonProperty("variables") List<PromptVariable> variables
    ) {
        this.id = StringUtils.trimToEmpty(id);
        this.title = StringUtils.trimToEmpty(title);
        this.prompt = StringUtils.defaultString(prompt);
        this.model = StringUtils.defaultIfBlank(model, DEFAULT_MODEL);
        this.variables = variables == null ? emptyList() : List.copyOf(variables);
    }
}
