package com.github.drafael.chat4j.chat.export.pdf;

import java.util.regex.Pattern;

final class PublicationMathDelimiterNormalizer {

    private static final Pattern LIST_ITEM = Pattern.compile("^(?:[-+*]|\\d+[.)])\\s+.*");

    private PublicationMathDelimiterNormalizer() {
    }

    static String normalize(String markdown) {
        String[] lines = markdown.split("\\n", -1);
        boolean[] fenced = fencedLines(lines);
        normalizeDisplayBlocks(lines, fenced);

        int inlineCodeDelimiterLength = 0;
        for (int index = 0; index < lines.length; index++) {
            if (fenced[index]) {
                continue;
            }
            InlineResult result = normalizeInlineDelimiters(
                    lines[index],
                    inlineCodeDelimiterLength,
                    isNestedListItem(lines, fenced, index)
            );
            lines[index] = result.text();
            inlineCodeDelimiterLength = result.codeDelimiterLength();
        }
        return String.join("\n", lines);
    }

    private static boolean[] fencedLines(String[] lines) {
        boolean[] fenced = new boolean[lines.length];
        Fence active = null;
        for (int index = 0; index < lines.length; index++) {
            if (active == null) {
                active = openingFence(lines[index]);
                fenced[index] = active != null;
                continue;
            }
            fenced[index] = true;
            if (isClosingFence(lines[index], active)) {
                active = null;
            }
        }
        return fenced;
    }

    private static Fence openingFence(String line) {
        String content = line.stripLeading();
        int indentation = line.length() - content.length();
        if (indentation > 3 || content.isEmpty()) {
            return null;
        }
        char marker = content.charAt(0);
        if (marker != '`' && marker != '~') {
            return null;
        }
        int count = markerCount(content, marker);
        return count >= 3 ? new Fence(marker, count) : null;
    }

    private static boolean isClosingFence(String line, Fence fence) {
        String content = line.stripLeading();
        int indentation = line.length() - content.length();
        if (indentation > 3 || content.isEmpty() || content.charAt(0) != fence.marker()) {
            return false;
        }
        int count = markerCount(content, fence.marker());
        return count >= fence.length() && content.substring(count).isBlank();
    }

    private static int markerCount(String value, char marker) {
        int count = 0;
        while (count < value.length() && value.charAt(count) == marker) {
            count++;
        }
        return count;
    }

    private static void normalizeDisplayBlocks(String[] lines, boolean[] fenced) {
        for (int openingIndex = 0; openingIndex < lines.length; openingIndex++) {
            if (fenced[openingIndex]
                    || isIndentedCode(lines[openingIndex])
                    || !"\\[".equals(lines[openingIndex].trim())
            ) {
                continue;
            }
            int closingIndex = closingDisplayIndex(lines, fenced, openingIndex + 1);
            if (closingIndex < 0) {
                continue;
            }
            lines[openingIndex] = replaceTrimmed(lines[openingIndex], "$$");
            lines[closingIndex] = replaceTrimmed(lines[closingIndex], "$$");
            openingIndex = closingIndex;
        }
    }

    private static int closingDisplayIndex(String[] lines, boolean[] fenced, int startIndex) {
        for (int index = startIndex; index < lines.length; index++) {
            if (fenced[index] || "\\[".equals(lines[index].trim())) {
                return -1;
            }
            if ("\\]".equals(lines[index].trim())) {
                return isIndentedCode(lines[index]) ? -1 : index;
            }
        }
        return -1;
    }

    private static boolean isIndentedCode(String line) {
        return line.startsWith("    ") || line.startsWith("\t");
    }

    private static String replaceTrimmed(String line, String replacement) {
        int start = 0;
        while (start < line.length() && Character.isWhitespace(line.charAt(start))) {
            start++;
        }
        int end = line.length();
        while (end > start && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return "%s%s%s".formatted(line.substring(0, start), replacement, line.substring(end));
    }

    private static boolean isNestedListItem(String[] lines, boolean[] fenced, int index) {
        String line = lines[index];
        int indentation = line.length() - line.stripLeading().length();
        if (indentation == 0 || !LIST_ITEM.matcher(line.stripLeading()).matches()) {
            return false;
        }
        for (int previous = index - 1; previous >= 0; previous--) {
            if (fenced[previous]) {
                return false;
            }
            if (lines[previous].isBlank()) {
                continue;
            }
            String previousLine = lines[previous];
            int previousIndentation = previousLine.length() - previousLine.stripLeading().length();
            return previousIndentation < indentation
                    && LIST_ITEM.matcher(previousLine.stripLeading()).matches();
        }
        return false;
    }

    private static InlineResult normalizeInlineDelimiters(
            String line,
            int initialCodeDelimiterLength,
            boolean nestedListItem
    ) {
        if (isIndentedCode(line) && !nestedListItem) {
            return new InlineResult(line, initialCodeDelimiterLength);
        }
        StringBuilder normalized = new StringBuilder(line.length());
        int codeDelimiterLength = initialCodeDelimiterLength;
        int segmentStart = 0;
        int index = 0;
        while (index < line.length()) {
            if (line.charAt(index) != '`') {
                index++;
                continue;
            }
            int runLength = markerCount(line.substring(index), '`');
            if (codeDelimiterLength == 0 || runLength == codeDelimiterLength) {
                if (codeDelimiterLength == 0) {
                    normalized.append(normalizeTextSegment(line.substring(segmentStart, index)));
                    codeDelimiterLength = runLength;
                } else {
                    normalized.append(line, segmentStart, index);
                    codeDelimiterLength = 0;
                }
                normalized.append(line, index, index + runLength);
                segmentStart = index + runLength;
            }
            index += runLength;
        }
        String remainder = line.substring(segmentStart);
        normalized.append(codeDelimiterLength == 0 ? normalizeTextSegment(remainder) : remainder);
        return new InlineResult(normalized.toString(), codeDelimiterLength);
    }

    private static String normalizeTextSegment(String segment) {
        StringBuilder normalized = new StringBuilder(segment.length());
        int index = 0;
        while (index < segment.length()) {
            Delimiter delimiter = delimiterAt(segment, index);
            if (delimiter == null) {
                normalized.append(segment.charAt(index++));
                continue;
            }
            int closingIndex = closingDelimiterIndex(segment, index + 2, delimiter);
            if (closingIndex < 0) {
                normalized.append(segment.charAt(index++));
                continue;
            }
            String content = segment.substring(index + 2, closingIndex);
            if (content.isBlank()) {
                normalized.append(segment, index, closingIndex + 2);
            } else {
                normalized.append(delimiter.replacement())
                        .append(content.strip())
                        .append(delimiter.replacement());
            }
            index = closingIndex + 2;
        }
        return normalized.toString();
    }

    private static Delimiter delimiterAt(String value, int index) {
        if (!isUnescapedSlash(value, index) || index + 1 >= value.length()) {
            return null;
        }
        return switch (value.charAt(index + 1)) {
            case '(' -> Delimiter.INLINE;
            case '[' -> Delimiter.DISPLAY;
            default -> null;
        };
    }

    private static int closingDelimiterIndex(String value, int startIndex, Delimiter delimiter) {
        for (int index = startIndex; index + 1 < value.length(); index++) {
            if (isUnescapedSlash(value, index) && value.charAt(index + 1) == delimiter.closingCharacter()) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isUnescapedSlash(String value, int index) {
        if (value.charAt(index) != '\\') {
            return false;
        }
        int precedingSlashes = 0;
        for (int previous = index - 1; previous >= 0 && value.charAt(previous) == '\\'; previous--) {
            precedingSlashes++;
        }
        return precedingSlashes % 2 == 0;
    }

    private enum Delimiter {
        INLINE(')', "$"),
        DISPLAY(']', "$$");

        private final char closingCharacter;
        private final String replacement;

        Delimiter(char closingCharacter, String replacement) {
            this.closingCharacter = closingCharacter;
            this.replacement = replacement;
        }

        char closingCharacter() {
            return closingCharacter;
        }

        String replacement() {
            return replacement;
        }
    }

    private record Fence(char marker, int length) {
    }

    private record InlineResult(String text, int codeDelimiterLength) {
    }
}
