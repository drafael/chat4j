package com.github.drafael.chat4j.chat.render;

import com.github.drafael.chat4j.util.Fonts;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import static java.util.Collections.nCopies;
import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.commons.lang3.StringUtils.normalizeSpace;

@Slf4j
public final class ReadAloudTextExtractor {

    private static final int SPEECH_CODE_FONT_SIZE = 11;
    private static final Pattern FENCE_OPEN = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern BLOCKQUOTE_PREFIX = Pattern.compile("^ {0,3}>[ \\t]?");
    private static final Pattern LIST_ITEM = Pattern.compile("^( *)(?:[-+*]|\\d+[.)])([ \\t]+)(.*)$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}\\s+.*$");
    private static final Pattern SETEXT_HEADING_UNDERLINE = Pattern.compile("^ {0,3}(?:=+|-+)\\s*$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile(
            "^ {0,3}(?:(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$"
    );
    private static final Pattern ESCAPED_MARKDOWN_CHARACTER = Pattern.compile("\\\\(\\p{Punct})");
    private static final Pattern BLANK_LINE = Pattern.compile("\\n[ \\t\\r]*\\n");
    private static final Palette SPEECH_PALETTE = new Palette(
            "sans-serif",
            "sans-serif",
            "monospace",
            "monospace",
            "#000000",
            "#000000",
            "#000000",
            "#ffffff",
            "#000000",
            "#ffffff",
            "#000000",
            "#ffffff",
            "#ffffff",
            "#000000",
            "#000000"
    );

    private ReadAloudTextExtractor() {
    }

    public static String extract(String markdown) {
        try {
            checkInterrupted();
            String filteredMarkdown = removeExcludedBlocks(defaultString(markdown));
            if (filteredMarkdown.isBlank()) {
                return "";
            }
            String speechMarkdown = normalizeMultilineInlineCode(filteredMarkdown);
            checkInterrupted();
            String html = Fonts.withScaleFactor(1.0f, () -> CodeFontResolver.withResolvedCodeFontSize(
                    SPEECH_CODE_FONT_SIZE,
                    () -> MarkdownRenderer.toBodyHtml(speechMarkdown, SPEECH_PALETTE)
            ));
            checkInterrupted();
            Document document = Jsoup.parseBodyFragment(html);
            checkInterrupted();
            document.select(".md-code-block, .md-latex-inline").remove();
            if (document.body() == null) {
                return "";
            }
            String visibleText = ESCAPED_MARKDOWN_CHARACTER.matcher(document.body().text()).replaceAll("$1");
            return normalizeSpace(visibleText);
        } catch (CancellationException e) {
            return "";
        } catch (RuntimeException | LinkageError e) {
            log.warn("Could not prepare Read aloud text: {}", e.getClass().getSimpleName());
            return "";
        }
    }

    private static String normalizeMultilineInlineCode(String markdown) {
        StringBuilder normalized = new StringBuilder(markdown.length());
        int cursor = 0;
        while (cursor < markdown.length()) {
            checkInterrupted();
            int openingStart = markdown.indexOf('`', cursor);
            if (openingStart < 0) {
                normalized.append(markdown, cursor, markdown.length());
                break;
            }
            normalized.append(markdown, cursor, openingStart);
            int openingEnd = endOfBacktickRun(markdown, openingStart);
            int delimiterLength = openingEnd - openingStart;
            int paragraphEnd = paragraphEnd(markdown, openingEnd);
            int closingStart = matchingBacktickRun(markdown, openingEnd, paragraphEnd, delimiterLength);
            if (closingStart < 0) {
                normalized.append(markdown, openingStart, openingEnd);
                cursor = openingEnd;
                continue;
            }
            normalized.append(markdown, openingStart, openingEnd);
            normalized.append(markdown.substring(openingEnd, closingStart).replace('\n', ' '));
            normalized.append(markdown, closingStart, closingStart + delimiterLength);
            cursor = closingStart + delimiterLength;
        }
        return normalized.toString();
    }

    private static int paragraphEnd(String markdown, int start) {
        Matcher matcher = BLANK_LINE.matcher(markdown);
        return matcher.find(start) ? matcher.start() : markdown.length();
    }

    private static int matchingBacktickRun(String markdown, int start, int end, int delimiterLength) {
        int candidate = markdown.indexOf('`', start);
        while (candidate >= 0 && candidate < end) {
            int runEnd = endOfBacktickRun(markdown, candidate);
            if (runEnd - candidate == delimiterLength) {
                return candidate;
            }
            candidate = markdown.indexOf('`', runEnd);
        }
        return -1;
    }

    private static int endOfBacktickRun(String markdown, int start) {
        int end = start;
        while (end < markdown.length() && markdown.charAt(end) == '`') {
            end++;
        }
        return end;
    }

    private static String removeExcludedBlocks(String markdown) {
        List<String> retainedLines = new ArrayList<>();
        Fence fence = null;
        int indentedCodeIndent = -1;
        List<Integer> listContentIndents = new ArrayList<>();
        MathBlock mathBlock = null;
        NestedListContinuation nestedListContinuation = null;
        ContainerContext previousRetainedContainer = null;
        boolean previousLineBlank = true;
        boolean indentedCodeCanStart = true;

        for (String sourceLine : markdown.split("\\n", -1)) {
            checkInterrupted();
            String line = sourceLine;
            if (fence != null) {
                ContainerContext closingContext = line.isBlank() && fence.containerContext().containsOnlyLists()
                        ? fence.containerContext()
                        : effectiveContainerContext(
                                containerContext(line, listContentIndents),
                                containerContinuationContent(line, listContentIndents),
                                nestedListContinuation
                        );
                if (fence.containerContext().isPrefixOf(closingContext)) {
                    if (fence.closes(line, listContentIndents, closingContext)) {
                        fence = null;
                    }
                    previousLineBlank = true;
                    indentedCodeCanStart = true;
                    continue;
                }
                fence = null;
                previousLineBlank = true;
                indentedCodeCanStart = true;
            }

            if (mathBlock == null) {
                pruneInterruptedListContainers(listContentIndents, sourceLine);
            }
            int activeListContentIndent = activeListContentIndent(listContentIndents);
            String containerContent = activeListContentIndent >= 0 ? line : stripBlockquotePrefixes(line);
            if (mathBlock != null) {
                String mathContent = containerContinuationContent(line, listContentIndents);
                ContainerContext closingContext = sourceLine.isBlank()
                        && mathBlock.containerContext().containsOnlyLists()
                        ? mathBlock.containerContext()
                        : effectiveContainerContext(
                                containerContext(sourceLine, listContentIndents),
                                mathContent,
                                nestedListContinuation
                        );
                if (mathBlock.containerContext().isPrefixOf(closingContext)) {
                    int closeIndex = mathBlock.containerContext().equals(closingContext)
                            && indentationColumns(mathContent) >= mathBlock.minimumClosingIndent()
                            ? mathContent.indexOf(mathBlock.closeDelimiter())
                            : -1;
                    if (closeIndex < 0) {
                        previousLineBlank = true;
                        indentedCodeCanStart = true;
                        continue;
                    }
                    line = mathContent.substring(closeIndex + mathBlock.closeDelimiter().length());
                    mathBlock = null;
                    containerContent = line;
                    if (line.isBlank()) {
                        previousLineBlank = true;
                        indentedCodeCanStart = true;
                        continue;
                    }
                } else {
                    mathBlock = null;
                    previousLineBlank = true;
                    indentedCodeCanStart = true;
                    pruneInterruptedListContainers(listContentIndents, sourceLine);
                    activeListContentIndent = activeListContentIndent(listContentIndents);
                    containerContent = activeListContentIndent >= 0 ? line : stripBlockquotePrefixes(line);
                }
            }

            String continuationContent = containerContinuationContent(line, listContentIndents);
            boolean containerBlank = continuationContent.isBlank();
            if (indentedCodeIndent >= 0) {
                if (containerBlank || indentationColumns(continuationContent) >= indentedCodeIndent) {
                    previousLineBlank = true;
                    indentedCodeCanStart = true;
                    continue;
                }
                indentedCodeIndent = -1;
            }

            ListItem listItem = listItem(containerContent, listContentIndents);
            ContainerContext rawContainerContext = containerContext(sourceLine, listContentIndents);
            NestedListContinuation explicitNestedList = nestedListContinuation(
                    sourceLine,
                    listContentIndents,
                    rawContainerContext
            );
            if (explicitNestedList != null) {
                nestedListContinuation = explicitNestedList;
            } else if (!containerBlank && !appliesTo(rawContainerContext, continuationContent, nestedListContinuation)) {
                nestedListContinuation = null;
            }
            ContainerContext lineContainerContext = effectiveContainerContext(
                    rawContainerContext,
                    continuationContent,
                    nestedListContinuation
            );
            int inheritedListIndent = inheritedListIndent(
                    rawContainerContext,
                    continuationContent,
                    nestedListContinuation
            );
            Fence openedFence = openingFence(
                    containerContent,
                    listContentIndents,
                    inheritedListIndent,
                    lineContainerContext
            );
            if (openedFence != null) {
                appendBlockBoundary(retainedLines);
                updateListContentIndents(
                        listContentIndents,
                        listItem,
                        sourceLine,
                        containerBlank,
                        previousLineBlank,
                        rawContainerContext
                );
                fence = openedFence;
                previousLineBlank = true;
                indentedCodeCanStart = true;
                continue;
            }

            String leafContent = containerLeafContent(containerContent, listContentIndents);
            String mathCloseDelimiter = openingMathCloseDelimiter(leafContent);
            mathBlock = mathCloseDelimiter == null
                    ? null
                    : new MathBlock(mathCloseDelimiter, lineContainerContext, inheritedListIndent);
            if (mathBlock != null || MarkdownBlockRenderer.isBareDisplayLatexLine(leafContent.trim())) {
                appendBlockBoundary(retainedLines);
                updateListContentIndents(
                        listContentIndents,
                        listItem,
                        sourceLine,
                        containerBlank,
                        previousLineBlank,
                        rawContainerContext
                );
                previousLineBlank = true;
                indentedCodeCanStart = true;
                continue;
            }

            int requiredCodeIndent = 4;
            boolean listItemStartsIndentedCode = listItem != null
                    && indentationColumns(leafContent) >= requiredCodeIndent;
            boolean continuationStartsIndentedCode = indentedCodeCanStart
                    && listItem == null
                    && indentationColumns(continuationContent) >= requiredCodeIndent;
            if (listItemStartsIndentedCode || continuationStartsIndentedCode) {
                appendBlockBoundary(retainedLines);
                if (listItemStartsIndentedCode) {
                    updateListContentIndents(
                            listContentIndents,
                            listItem,
                            sourceLine,
                            containerBlank,
                            previousLineBlank,
                            rawContainerContext
                    );
                }
                indentedCodeIndent = requiredCodeIndent;
                previousLineBlank = true;
                indentedCodeCanStart = true;
                continue;
            }

            line = speechSourceLine(leafContent);
            boolean containerChanged = previousRetainedContainer != null
                    && !previousRetainedContainer.equals(lineContainerContext);
            boolean standaloneInlineBlock = HEADING.matcher(leafContent.stripTrailing()).matches()
                    || isTableLine(leafContent);
            boolean startsSeparateInlineBlock = listItem != null || containerChanged || standaloneInlineBlock;
            if (startsSeparateInlineBlock) {
                appendBlockBoundary(retainedLines);
            }
            retainedLines.add(line);
            if (standaloneInlineBlock) {
                appendBlockBoundary(retainedLines);
            }
            previousRetainedContainer = lineContainerContext;
            updateListContentIndents(
                    listContentIndents,
                    listItem,
                    sourceLine,
                    containerBlank,
                    previousLineBlank,
                    rawContainerContext
            );
            previousLineBlank = containerBlank;
            indentedCodeCanStart = containerBlank || startsStandaloneBlock(leafContent);
        }

        return String.join("\n", retainedLines);
    }

    private static void appendBlockBoundary(List<String> retainedLines) {
        if (!retainedLines.isEmpty() && !retainedLines.getLast().isBlank()) {
            retainedLines.add("");
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Read aloud extraction was cancelled");
        }
    }

    private static Fence openingFence(
            String line,
            List<Integer> listContentIndents,
            int inheritedListIndent,
            ContainerContext containerContext
    ) {
        String candidate = line.stripTrailing();
        int activeListContentIndent = activeListContentIndent(listContentIndents);
        int indentation = indentationColumns(candidate);
        int containerIndent = 0;
        if (activeListContentIndent >= 0
                && indentation >= activeListContentIndent
                && indentation <= activeListContentIndent + 3
        ) {
            String continuationContent = containerContinuationContent(candidate, listContentIndents);
            Fence continuationFence = fenceAtStart(
                    continuationContent,
                    inheritedListIndent,
                    inheritedListIndent + 3,
                    containerContext
            );
            if (continuationFence != null) {
                return continuationFence;
            }
            candidate = continuationContent;
            containerIndent = indentation;
        } else {
            Matcher matcher = FENCE_OPEN.matcher(candidate);
            if (matcher.matches()) {
                return fence(matcher, 0, 3, containerContext);
            }
        }

        while (true) {
            Matcher listMatcher = matchingListItem(candidate);
            if (listMatcher == null || indentationColumns(listMatcher.group(1)) > 3) {
                return null;
            }
            containerIndent += listItemContentIndent(candidate, listMatcher);
            candidate = stripBlockquotePrefixes(listItemContent(candidate, listMatcher));
            Fence containerFence = fenceAtStart(candidate, 0, containerIndent + 3, containerContext);
            if (containerFence != null) {
                return containerFence;
            }
        }
    }

    private static Fence fenceAtStart(
            String content,
            int minimumClosingIndent,
            int maximumClosingIndent,
            ContainerContext containerContext
    ) {
        Matcher matcher = FENCE_OPEN.matcher(content);
        return matcher.matches()
                ? fence(matcher, minimumClosingIndent, maximumClosingIndent, containerContext)
                : null;
    }

    private static Fence fence(
            Matcher matcher,
            int minimumClosingIndent,
            int maximumClosingIndent,
            ContainerContext containerContext
    ) {
        String delimiter = matcher.group(1);
        if (delimiter.charAt(0) == '`' && matcher.group(2).contains("`")) {
            return null;
        }
        return new Fence(
                delimiter.charAt(0),
                delimiter.length(),
                minimumClosingIndent,
                maximumClosingIndent,
                containerContext
        );
    }

    private static String speechSourceLine(String leafContent) {
        String candidate = leafContent.stripTrailing();
        return SETEXT_HEADING_UNDERLINE.matcher(candidate).matches() || isHorizontalRule(candidate)
                ? ""
                : removeInvalidBacktickFenceDelimiter(candidate);
    }

    private static String removeInvalidBacktickFenceDelimiter(String content) {
        Matcher matcher = FENCE_OPEN.matcher(content);
        if (!matcher.matches() || matcher.group(1).charAt(0) != '`' || !matcher.group(2).contains("`")) {
            return content;
        }
        return "%s%s".formatted(
                content.substring(0, matcher.start(1)),
                content.substring(matcher.end(1))
        );
    }

    private static String openingMathCloseDelimiter(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("$$") && !trimmed.substring(2).contains("$$")) {
            return "$$";
        }
        if (trimmed.startsWith("\\[") && !trimmed.substring(2).contains("\\]")) {
            return "\\]";
        }
        return null;
    }

    private static String containerLeafContent(String line, List<Integer> listContentIndents) {
        String candidate = containerContinuationContent(line, listContentIndents);
        while (true) {
            Matcher matcher = matchingListItem(candidate);
            if (matcher == null || indentationColumns(matcher.group(1)) > 3) {
                return candidate;
            }
            candidate = stripBlockquotePrefixes(listItemContent(candidate, matcher));
        }
    }

    private static String containerContinuationContent(String line, List<Integer> listContentIndents) {
        String candidate = line.stripTrailing();
        boolean activeListApplied = false;
        while (true) {
            int indentation = indentationColumns(candidate);
            int applicableListIndent = listContentIndents.stream()
                    .filter(contentIndent -> contentIndent <= indentation)
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(-1);
            if (!activeListApplied
                    && applicableListIndent >= 0
                    && indentation <= applicableListIndent + 3
            ) {
                candidate = candidate.stripLeading();
                activeListApplied = true;
                continue;
            }
            Matcher blockquoteMatcher = BLOCKQUOTE_PREFIX.matcher(candidate);
            if (!blockquoteMatcher.find()) {
                return candidate;
            }
            candidate = candidate.substring(blockquoteMatcher.end());
        }
    }

    private static NestedListContinuation nestedListContinuation(
            String line,
            List<Integer> listContentIndents,
            ContainerContext containerContext
    ) {
        String candidate = line.stripTrailing();
        boolean activeListApplied = false;
        boolean blockquoteSeen = false;
        int trackedListIndex = -1;
        int minimumIndent = 0;
        int containerIndex = 0;

        while (true) {
            int indentation = indentationColumns(candidate);
            if (!activeListApplied) {
                long activeListDepth = listContentIndents.stream()
                        .filter(contentIndent -> contentIndent <= indentation)
                        .count();
                if (activeListDepth > 0) {
                    containerIndex += (int) activeListDepth;
                    candidate = candidate.stripLeading();
                    activeListApplied = true;
                    continue;
                }
            }

            Matcher blockquoteMatcher = BLOCKQUOTE_PREFIX.matcher(candidate);
            if (blockquoteMatcher.find()) {
                blockquoteSeen = true;
                containerIndex++;
                candidate = candidate.substring(blockquoteMatcher.end());
                continue;
            }

            Matcher listMatcher = matchingListItem(candidate);
            if (listMatcher == null || indentationColumns(listMatcher.group(1)) > 3) {
                break;
            }
            if (blockquoteSeen) {
                trackedListIndex = containerIndex;
                minimumIndent = listItemContentIndent(candidate, listMatcher);
            }
            containerIndex++;
            candidate = listItemContent(candidate, listMatcher);
        }

        if (trackedListIndex < 0 || trackedListIndex >= containerContext.containers().size()) {
            return null;
        }
        List<ContainerKind> parentContainers = new ArrayList<>(containerContext.containers());
        parentContainers.remove(trackedListIndex);
        return new NestedListContinuation(
                new ContainerContext(parentContainers),
                containerContext,
                minimumIndent
        );
    }

    private static ContainerContext effectiveContainerContext(
            ContainerContext rawContext,
            String continuationContent,
            NestedListContinuation nestedListContinuation
    ) {
        return appliesTo(rawContext, continuationContent, nestedListContinuation)
                ? nestedListContinuation.containerContext()
                : rawContext;
    }

    private static int inheritedListIndent(
            ContainerContext rawContext,
            String continuationContent,
            NestedListContinuation nestedListContinuation
    ) {
        return nestedListContinuation != null
                && nestedListContinuation.parentContext().equals(rawContext)
                && indentationColumns(continuationContent) >= nestedListContinuation.minimumIndent()
                ? nestedListContinuation.minimumIndent()
                : 0;
    }

    private static boolean appliesTo(
            ContainerContext rawContext,
            String continuationContent,
            NestedListContinuation nestedListContinuation
    ) {
        if (nestedListContinuation == null) {
            return false;
        }
        if (nestedListContinuation.containerContext().equals(rawContext)) {
            return true;
        }
        return nestedListContinuation.parentContext().equals(rawContext)
                && indentationColumns(continuationContent) >= nestedListContinuation.minimumIndent();
    }

    private static boolean startsStandaloneBlock(String content) {
        String candidate = content.stripTrailing();
        return HEADING.matcher(candidate).matches()
                || SETEXT_HEADING_UNDERLINE.matcher(candidate).matches()
                || isHorizontalRule(candidate)
                || isTableLine(candidate);
    }

    private static int activeListContentIndent(List<Integer> listContentIndents) {
        return listContentIndents.isEmpty() ? -1 : listContentIndents.getLast();
    }

    private static void pruneInterruptedListContainers(List<Integer> listContentIndents, String line) {
        if (!line.stripLeading().startsWith(">")) {
            return;
        }
        int indentation = indentationColumns(line);
        while (!listContentIndents.isEmpty() && listContentIndents.getLast() > indentation) {
            listContentIndents.removeLast();
        }
    }

    private static void updateListContentIndents(
            List<Integer> listContentIndents,
            ListItem listItem,
            String structuralLine,
            boolean blank,
            boolean previousLineBlank,
            ContainerContext structuralContext
    ) {
        if (listItem != null) {
            while (!listContentIndents.isEmpty() && listContentIndents.getLast() > listItem.leadingIndent()) {
                listContentIndents.removeLast();
            }
            listContentIndents.add(listItem.contentIndent());
            return;
        }
        if (!blank) {
            pruneDedentedNestedLists(listContentIndents, structuralContext);
        }
        if (!blank && (previousLineBlank || interruptsLazyListContinuation(structuralLine))) {
            int indentation = indentationColumns(structuralLine);
            while (!listContentIndents.isEmpty() && listContentIndents.getLast() > indentation) {
                listContentIndents.removeLast();
            }
        }
    }

    private static void pruneDedentedNestedLists(
            List<Integer> listContentIndents,
            ContainerContext structuralContext
    ) {
        int retainedDepth = structuralContext.listDepth();
        if (retainedDepth <= 0 || retainedDepth >= listContentIndents.size()) {
            return;
        }
        while (listContentIndents.size() > retainedDepth) {
            listContentIndents.removeLast();
        }
    }

    private static boolean interruptsLazyListContinuation(String line) {
        String candidate = line.stripTrailing();
        String leftTrimmed = candidate.stripLeading();
        return HEADING.matcher(candidate).matches()
                || SETEXT_HEADING_UNDERLINE.matcher(candidate).matches()
                || isHorizontalRule(candidate)
                || leftTrimmed.startsWith(">")
                || FENCE_OPEN.matcher(candidate).matches()
                || leftTrimmed.startsWith("$$")
                || leftTrimmed.startsWith("\\[")
                || MarkdownBlockRenderer.isBareDisplayLatexLine(stripBlockquotePrefixes(candidate).trim());
    }

    private static String stripBlockquotePrefixes(String line) {
        String content = line;
        Matcher matcher = BLOCKQUOTE_PREFIX.matcher(content);
        while (matcher.find()) {
            content = content.substring(matcher.end());
            matcher = BLOCKQUOTE_PREFIX.matcher(content);
        }
        return content;
    }

    private static ContainerContext containerContext(String line, List<Integer> listContentIndents) {
        String candidate = line.stripTrailing();
        List<ContainerKind> containers = new ArrayList<>();
        boolean activeListApplied = false;

        while (true) {
            int indentation = indentationColumns(candidate);
            if (!activeListApplied) {
                long activeListDepth = listContentIndents.stream()
                        .filter(contentIndent -> contentIndent <= indentation)
                        .count();
                if (activeListDepth > 0) {
                    containers.addAll(nCopies((int) activeListDepth, ContainerKind.LIST));
                    candidate = candidate.stripLeading();
                    activeListApplied = true;
                    continue;
                }
            }

            Matcher blockquoteMatcher = BLOCKQUOTE_PREFIX.matcher(candidate);
            if (blockquoteMatcher.find()) {
                containers.add(ContainerKind.BLOCKQUOTE);
                candidate = candidate.substring(blockquoteMatcher.end());
                continue;
            }

            Matcher listMatcher = matchingListItem(candidate);
            if (listMatcher == null || indentationColumns(listMatcher.group(1)) > 3) {
                return new ContainerContext(containers);
            }
            containers.add(ContainerKind.LIST);
            candidate = listItemContent(candidate, listMatcher);
        }
    }

    private static ListItem listItem(String line, List<Integer> listContentIndents) {
        Matcher matcher = matchingListItem(line);
        if (matcher == null) {
            return null;
        }
        int leadingIndent = indentationColumns(matcher.group(1));
        int activeListContentIndent = activeListContentIndent(listContentIndents);
        boolean alignedWithListContent = listContentIndents.stream().anyMatch(indent -> indent == leadingIndent);
        boolean nestedUnderActiveItem = activeListContentIndent >= 0
                && leadingIndent >= activeListContentIndent
                && leadingIndent <= activeListContentIndent + 3;
        if (leadingIndent > 3 && !alignedWithListContent && !nestedUnderActiveItem) {
            return null;
        }
        return new ListItem(leadingIndent, listItemContentIndent(line, matcher));
    }

    private static String listItemContent(String line, Matcher matcher) {
        int padding = listMarkerPadding(line, matcher);
        return padding <= 4
                ? matcher.group(3)
                : "%s%s".formatted(" ".repeat(padding - 1), matcher.group(3));
    }

    private static int listItemContentIndent(String line, Matcher matcher) {
        int markerEnd = columnsBefore(line, matcher.start(2));
        int padding = listMarkerPadding(line, matcher);
        return markerEnd + (padding <= 4 ? padding : 1);
    }

    private static int listMarkerPadding(String line, Matcher matcher) {
        return columnsBefore(line, matcher.start(3)) - columnsBefore(line, matcher.start(2));
    }

    private static Matcher matchingListItem(String content) {
        if (isHorizontalRule(content)) {
            return null;
        }
        Matcher matcher = LIST_ITEM.matcher(content);
        return matcher.matches() ? matcher : null;
    }

    private static boolean isHorizontalRule(String content) {
        return HORIZONTAL_RULE.matcher(content.stripTrailing()).matches();
    }

    private static boolean isTableLine(String content) {
        String candidate = content.trim();
        return candidate.startsWith("|") && candidate.endsWith("|");
    }

    private static int columnsBefore(String line, int endIndex) {
        int columns = 0;
        for (int index = 0; index < endIndex; index++) {
            columns = line.charAt(index) == '\t' ? columns + 4 - columns % 4 : columns + 1;
        }
        return columns;
    }

    private static int indentationColumns(String line) {
        int columns = 0;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == ' ') {
                columns++;
            } else if (character == '\t') {
                columns += 4 - columns % 4;
            } else {
                break;
            }
        }
        return columns;
    }

    private record Fence(
            char delimiter,
            int length,
            int minimumClosingIndent,
            int maximumClosingIndent,
            ContainerContext containerContext
    ) {
        private boolean closes(
                String line,
                List<Integer> listContentIndents,
                ContainerContext closingContainerContext
        ) {
            if (!containerContext.equals(closingContainerContext)) {
                return false;
            }
            String candidate = containerContinuationContent(line, listContentIndents);
            int indentation = indentationColumns(candidate);
            if (indentation < minimumClosingIndent || indentation > maximumClosingIndent) {
                return false;
            }
            int index = leadingWhitespaceLength(candidate);
            int delimiterStart = index;
            while (index < candidate.length() && candidate.charAt(index) == delimiter) {
                index++;
            }
            return index - delimiterStart >= length && candidate.substring(index).isBlank();
        }
    }

    private static int leadingWhitespaceLength(String line) {
        int index = 0;
        while (index < line.length() && (line.charAt(index) == ' ' || line.charAt(index) == '\t')) {
            index++;
        }
        return index;
    }

    private record ListItem(int leadingIndent, int contentIndent) {
    }

    private record ContainerContext(List<ContainerKind> containers) {
        private ContainerContext {
            containers = List.copyOf(containers);
        }

        private boolean isPrefixOf(ContainerContext other) {
            return containers.size() <= other.containers.size()
                    && containers.equals(other.containers.subList(0, containers.size()));
        }

        private int listDepth() {
            return (int) containers.stream().filter(ContainerKind.LIST::equals).count();
        }

        private boolean containsOnlyLists() {
            return containers.stream().allMatch(ContainerKind.LIST::equals);
        }
    }

    private enum ContainerKind {
        LIST,
        BLOCKQUOTE
    }

    private record NestedListContinuation(
            ContainerContext parentContext,
            ContainerContext containerContext,
            int minimumIndent
    ) {
    }

    private record MathBlock(
            String closeDelimiter,
            ContainerContext containerContext,
            int minimumClosingIndent
    ) {
    }
}
