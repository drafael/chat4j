package com.github.drafael.chat4j.prompts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static java.util.Collections.emptyList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptCatalogValidatorTest {

    @Test
    @DisplayName("Built-in prompt catalog is valid")
    void validate_whenBuiltInsProvided_returnsNoErrors() {
        assertThat(PromptCatalogValidator.validate(BuiltInPromptCatalog.prompts())).isEmpty();
    }

    @Test
    @DisplayName("Validator reports duplicate IDs and undefined placeholders")
    void validate_whenCatalogHasDuplicateIdsAndUndefinedVariables_returnsErrors() {
        List<PromptTemplate> prompts = List.of(
                new PromptTemplate("same", "First", "@{{text}} @{{tone}}", PromptTemplate.DEFAULT_MODEL, List.of(PromptVariable.input("text"))),
                new PromptTemplate("same", "Second", "@{{text}}", PromptTemplate.DEFAULT_MODEL, List.of(PromptVariable.input("text")))
        );

        assertThat(PromptCatalogValidator.validate(prompts))
                .anySatisfy(error -> assertThat(error).contains("Duplicate prompt ID"))
                .anySatisfy(error -> assertThat(error).contains("undefined variable: tone"));
    }

    @Test
    @DisplayName("Validator reports malformed placeholders")
    void validate_whenPlaceholderNameIsMalformed_returnsError() {
        List<PromptTemplate> prompts = List.of(new PromptTemplate(
                "demo",
                "Demo",
                "Rewrite @{{ text }} and @{{1bad}}",
                PromptTemplate.DEFAULT_MODEL,
                List.of(PromptVariable.input("text"))
        ));

        assertThat(PromptCatalogValidator.validate(prompts))
                .anySatisfy(error -> assertThat(error).contains("malformed placeholder: @{{ text }}"))
                .anySatisfy(error -> assertThat(error).contains("malformed placeholder: @{{1bad}}"));
    }

    @Test
    @DisplayName("Validator rejects select variables without options")
    void validateOrThrow_whenSelectVariableHasNoOptions_throwsException() {
        List<PromptTemplate> prompts = List.of(new PromptTemplate(
                "tone",
                "Tone",
                "@{{tone}}",
                PromptTemplate.DEFAULT_MODEL,
                List.of(PromptVariable.select("tone", emptyList()))
        ));

        assertThatThrownBy(() -> PromptCatalogValidator.validateOrThrow(prompts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("select variable needs options");
    }
}
