package com.github.drafael.chat4j.chat.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolInvocationRequestTest {

    @Test
    @DisplayName("Tool invocation string forms mask raw arguments")
    void toString_whenInvocationContainsSecret_masksArguments() {
        var subject = new ToolInvocationRequest("id", "tool", "{\"token\":\"top-secret\"}");

        assertThat(subject.toString()).contains("argumentsJson=****").doesNotContain("top-secret");
    }
}
