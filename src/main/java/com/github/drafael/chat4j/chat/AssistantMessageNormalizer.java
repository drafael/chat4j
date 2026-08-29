package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.chat.render.WebSearchActivityNormalizer;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AgentToolActivityMeta;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;

final class AssistantMessageNormalizer {

    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\u001B\\[[;\\d]*[ -/]*[@-~]");
    private static final Pattern NON_PRINTABLE_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    private static final Pattern UNICODE_FORMAT_PATTERN = Pattern.compile("\\p{Cf}");

    private AssistantMessageNormalizer() {
    }

    static List<Message> normalizeLoadedHistory(List<Message> messages) {
        if (ObjectUtils.isEmpty(messages)) {
            return emptyList();
        }

        List<Message> normalized = new ArrayList<>();
        int index = 0;
        while (index < messages.size()) {
            Message message = messages.get(index);
            if (message.role() != Role.ASSISTANT) {
                normalized.add(message);
                index++;
                continue;
            }

            int cursor = index;
            List<Message> assistantRun = new ArrayList<>();
            while (cursor < messages.size() && messages.get(cursor).role() == Role.ASSISTANT) {
                assistantRun.add(messages.get(cursor));
                cursor++;
            }

            normalized.add(mergeAssistantRun(assistantRun));
            index = cursor;
        }

        return normalized;
    }

    private static Message mergeAssistantRun(List<Message> assistantRun) {
        if (ObjectUtils.isEmpty(assistantRun)) {
            return Message.assistant("");
        }

        if (assistantRun.size() == 1) {
            return assistantRun.getFirst();
        }

        Message primary = assistantRun.stream()
                .filter(candidate -> StringUtils.isNotBlank(candidate.content()))
                .reduce((first, second) -> second)
                .orElse(assistantRun.getLast());

        String mergedThinking = assistantRun.stream()
                .map(candidate -> normalizeThinkingText(candidate.meta() == null
                        ? ""
                        : StringUtils.defaultString(candidate.meta().assistantThinking())))
                .filter(AssistantMessageNormalizer::hasVisibleThinkingContent)
                .collect(joining("\n\n"));

        String mergedWebSearch = WebSearchActivityNormalizer.normalize(assistantRun.stream()
                .map(candidate -> candidate.meta() == null
                        ? ""
                        : StringUtils.defaultString(candidate.meta().assistantWebSearch()))
                .filter(StringUtils::isNotBlank)
                .collect(joining("\n\n")));

        List<AgentToolActivityMeta> mergedAgentToolActivities = assistantRun.stream()
                .filter(candidate -> candidate.meta() != null)
                .flatMap(candidate -> candidate.meta().agentToolActivities().stream())
                .toList();

        MessageMeta meta = primary.meta() == null ? MessageMeta.empty() : primary.meta();
        List<CitationRef> mergedCitations = meta.citations();
        MessageMeta mergedMeta = new MessageMeta(
                meta.activeSkills(),
                meta.fallbackNotices(),
                meta.cancelled(),
                meta.error(),
                mergedThinking,
                mergedWebSearch,
                mergedAgentToolActivities,
                mergedCitations
        );

        return new Message(primary.role(), primary.parts(), primary.timestamp(), mergedMeta);
    }

    static String normalizeThinkingText(String text) {
        if (text == null) {
            return "";
        }

        String withoutAnsi = ANSI_ESCAPE_PATTERN.matcher(text).replaceAll("");
        String normalizedLineEndings = withoutAnsi
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        String withoutInvisible = normalizedLineEndings.replace('\u00A0', ' ');
        String withoutFormatting = UNICODE_FORMAT_PATTERN.matcher(withoutInvisible).replaceAll("");

        return NON_PRINTABLE_PATTERN.matcher(withoutFormatting).replaceAll("");
    }

    private static boolean hasVisibleThinkingContent(String text) {
        return StringUtils.isNotBlank(normalizeThinkingText(text));
    }
}
