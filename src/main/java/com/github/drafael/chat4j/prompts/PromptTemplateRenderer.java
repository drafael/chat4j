package com.github.drafael.chat4j.prompts;

import lombok.NonNull;
import org.apache.commons.text.StringSubstitutor;

import java.util.Map;

public class PromptTemplateRenderer {

    public String render(@NonNull PromptTemplate promptTemplate, @NonNull Map<String, String> values) {
        StringSubstitutor substitutor = new StringSubstitutor(values, "@{{", "}}");
        substitutor.setEnableUndefinedVariableException(true);
        substitutor.setDisableSubstitutionInValues(true);
        return substitutor.replace(promptTemplate.prompt());
    }
}
