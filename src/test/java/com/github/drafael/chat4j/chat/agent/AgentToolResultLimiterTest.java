package com.github.drafael.chat4j.chat.agent;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolResultLimiterTest {

    @Test
    @DisplayName("Multiline output within the boundary remains unchanged")
    void limit_whenOutputFits_preservesNewlinesAndTabs() {
        String value = "first\n\tsecond";

        assertThat(AgentToolResultLimiter.limit(value)).isEqualTo(value);
    }

    @Test
    @DisplayName("Truncation never splits a supplementary Unicode code point")
    void limit_whenBoundaryEndsInsideCodePoint_preservesWholeCodePointAndMarksTruncation() {
        String value = "%s😀tail".formatted("a".repeat(AgentToolResultLimiter.MAX_BYTES - 1));

        String limited = AgentToolResultLimiter.limit(value);

        assertThat(limited).startsWith("a".repeat(AgentToolResultLimiter.MAX_BYTES - 1));
        assertThat(limited).doesNotContain("�");
        assertThat(limited).endsWith("[truncated after 65536 bytes]");
        assertThat(limited.substring(0, limited.indexOf("\n\n[truncated"))
                .getBytes(StandardCharsets.UTF_8)).hasSize(AgentToolResultLimiter.MAX_BYTES - 1);
    }
}
