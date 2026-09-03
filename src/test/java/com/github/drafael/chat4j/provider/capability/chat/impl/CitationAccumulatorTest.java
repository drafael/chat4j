package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationAccumulatorTest {

    @Test
    @DisplayName("Repeated source placements keep one source number and distinct response spans")
    void addNew_whenSourceAppearsAtDistinctResponseSpans_emitsEachPlacementWithStableNumber() {
        var subject = new CitationAccumulator();
        var first = webCitation(4L, 10L);
        var second = webCitation(20L, 26L);

        CitationRef numberedFirst = subject.addNew(first).orElseThrow();
        CitationRef numberedSecond = subject.addNew(second).orElseThrow();

        assertThat(numberedFirst.number()).isEqualTo(1);
        assertThat(numberedSecond.number()).isEqualTo(1);
        assertThat(numberedFirst.responseStartIndex()).isEqualTo(4L);
        assertThat(numberedSecond.responseStartIndex()).isEqualTo(20L);
    }

    @Test
    @DisplayName("Duplicate streamed annotations are emitted only once")
    void addNew_whenSourceAndResponseSpanRepeat_ignoresDuplicateEvent() {
        var subject = new CitationAccumulator();
        var citation = webCitation(4L, 10L);

        assertThat(subject.addNew(citation)).isPresent();
        assertThat(subject.addNew(citation)).isEmpty();
    }

    private CitationRef webCitation(Long start, Long end) {
        return CitationRef.builder()
                .kind(CitationKind.WEB)
                .title("Example")
                .url("https://example.com")
                .responseStartIndex(start)
                .responseEndIndex(end)
                .build();
    }
}
