package com.github.drafael.chat4j.chat.export.pdf;

import com.github.drafael.chat4j.persistence.conversation.ConversationRepository.ConversationRecord;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository.LoadedConversation;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.chat.render.WebSearchActivityNormalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

@Builder
public record ConversationPdfDocument(
        String title,
        String provider,
        String model,
        LocalDateTime createdAt,
        Instant exportedAt,
        List<Turn> turns
) {

    public ConversationPdfDocument {
        title = StringUtils.defaultIfBlank(title, "Conversation");
        provider = StringUtils.defaultString(provider);
        model = StringUtils.defaultString(model);
        exportedAt = exportedAt == null ? Instant.now() : exportedAt;
        turns = turns == null ? emptyList() : List.copyOf(turns);
    }

    public static ConversationPdfDocument from(@NonNull LoadedConversation loadedConversation, @NonNull Instant exportedAt) {
        ConversationRecord conversation = loadedConversation.conversation();
        List<Turn> turns = loadedConversation.messages().stream()
                .map(messageRecord -> messageRecord.message())
                .filter(message -> message.role() == Role.USER || message.role() == Role.ASSISTANT)
                .map(Turn::from)
                .toList();
        return ConversationPdfDocument.builder()
                .title(conversation.title())
                .provider(conversation.provider())
                .model(conversation.model())
                .createdAt(conversation.createdAt())
                .exportedAt(exportedAt)
                .turns(turns)
                .build();
    }

    public record Turn(
            Role role,
            Instant timestamp,
            List<ContentPart> parts,
            List<String> fallbackNotices,
            boolean cancelled,
            String error,
            String assistantWebSearch,
            List<CitationRef> citations
    ) {

        private static final Pattern GENERATED_WEB_SOURCE_LINE = Pattern.compile(
                "^\\[\\d+]\\s+\\[.*]\\(<https?://[^>]+>\\)$",
                Pattern.CASE_INSENSITIVE
        );

        public Turn(
                Role role,
                Instant timestamp,
                List<ContentPart> parts,
                List<String> fallbackNotices,
                boolean cancelled,
                String error,
                List<CitationRef> citations
        ) {
            this(role, timestamp, parts, fallbackNotices, cancelled, error, "", citations);
        }

        public Turn {
            parts = parts == null ? emptyList() : List.copyOf(parts);
            fallbackNotices = fallbackNotices == null ? emptyList() : List.copyOf(fallbackNotices);
            error = StringUtils.defaultString(error);
            assistantWebSearch = WebSearchActivityNormalizer.normalize(assistantWebSearch);
            citations = citations == null
                    ? emptyList()
                    : List.copyOf(citations.stream()
                            .filter(citation -> citation != null)
                            .collect(toMap(
                                    CitationRef::number,
                                    identity(),
                                    (existing, replacement) -> existing,
                                    LinkedHashMap::new
                            ))
                            .values());
        }

        private static Turn from(Message message) {
            return new Turn(
                    message.role(),
                    message.timestamp(),
                    message.parts(),
                    message.meta().fallbackNotices(),
                    message.meta().cancelled(),
                    message.meta().error(),
                    message.meta().assistantWebSearch(),
                    message.meta().citations()
            );
        }

        public String textForRendering() {
            String text = parts.stream()
                    .filter(TextPart.class::isInstance)
                    .map(TextPart.class::cast)
                    .map(TextPart::text)
                    .collect(joining("\n"));
            return citations.isEmpty() ? text : removeGeneratedCitationAppendix(text);
        }

        private String removeGeneratedCitationAppendix(String text) {
            List<String> lines = text.lines().toList();
            int lastContentLine = lines.size() - 1;
            while (lastContentLine >= 0 && StringUtils.isBlank(lines.get(lastContentLine))) {
                lastContentLine--;
            }
            int firstSourceLine = lastContentLine;
            while (firstSourceLine >= 0 && GENERATED_WEB_SOURCE_LINE.matcher(lines.get(firstSourceLine).trim()).matches()) {
                firstSourceLine--;
            }
            if (firstSourceLine == lastContentLine
                    || firstSourceLine < 0
                    || !"Sources:".equalsIgnoreCase(lines.get(firstSourceLine).trim())
            ) {
                return text;
            }
            return lines.subList(0, firstSourceLine).stream().collect(joining("\n")).stripTrailing();
        }
    }
}
