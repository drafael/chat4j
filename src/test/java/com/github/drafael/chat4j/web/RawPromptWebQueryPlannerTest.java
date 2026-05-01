package com.github.drafael.chat4j.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RawPromptWebQueryPlannerTest {

    @Test
    @DisplayName("Raw prompt planner trims the user prompt")
    void planQueries_whenPromptContainsOuterWhitespace_returnsTrimmedPrompt() {
        RawPromptWebQueryPlanner subject = new RawPromptWebQueryPlanner();

        assertThat(subject.planQueries("  java 25 features  ", () -> false))
                .containsExactly("java 25 features");
    }

    @Test
    @DisplayName("Raw prompt planner returns no queries after cancellation")
    void planQueries_whenCancelled_returnsEmptyList() {
        RawPromptWebQueryPlanner subject = new RawPromptWebQueryPlanner();

        assertThat(subject.planQueries("java 25", () -> true)).isEmpty();
    }
}
