package com.github.drafael.chat4j.prompts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateRendererTest {

    private final PromptTemplateRenderer subject = new PromptTemplateRenderer();

    @Test
    @DisplayName("Renderer replaces prompt variables using at-brace delimiters")
    void render_whenValuesProvided_replacesVariables() {
        PromptTemplate prompt = new PromptTemplate(
                "demo",
                "Demo",
                "Rewrite @{{text}} as @{{tone}}.",
                PromptTemplate.DEFAULT_MODEL,
                List.of(PromptVariable.input("text"), PromptVariable.input("tone"))
        );

        String rendered = subject.render(prompt, Map.of("text", "hello", "tone", "friendly"));

        assertThat(rendered).isEqualTo("Rewrite hello as friendly.");
    }

    @Test
    @DisplayName("Renderer throws when a variable is undefined")
    void render_whenValueMissing_throwsException() {
        PromptTemplate prompt = new PromptTemplate(
                "demo",
                "Demo",
                "Rewrite @{{text}}.",
                PromptTemplate.DEFAULT_MODEL,
                List.of(PromptVariable.input("text"))
        );

        assertThatThrownBy(() -> subject.render(prompt, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot resolve variable");
    }

    @Test
    @DisplayName("Renderer does not substitute nested values")
    void render_whenValueContainsPlaceholder_keepsValueLiteral() {
        PromptTemplate prompt = new PromptTemplate(
                "demo",
                "Demo",
                "Text: @{{text}}",
                PromptTemplate.DEFAULT_MODEL,
                List.of(PromptVariable.input("text"))
        );

        String rendered = subject.render(prompt, Map.of("text", "@{{env:HOME}}"));

        assertThat(rendered).isEqualTo("Text: @{{env:HOME}}");
    }
}
