package com.github.drafael.chat4j.chat.render;

import com.github.drafael.chat4j.util.Fonts;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class MarkdownBlockRenderer {

    private static final Pattern BARE_DISPLAY_LATEX_PATTERN = Pattern.compile(
            "^\\\\(?:ce|text|frac|sum|prod|int|iint|iiint|oint|oiint|nabla|partial|mathcal|mathbf|boldsymbol|vec|overline|underline|Phi|Delta|Omega|alpha|beta|gamma|lambda|mu|nu|theta|omega)\\b.*"
    );
    private static final Pattern LATEX_OPERATOR_PATTERN = Pattern.compile(
            ".*(?:[_^=+]|\\\\(?:to|rightarrow|leftarrow|xrightarrow|frac|cdot|times|ce)\\b|->|←|→|⟶).*"
    );

    private MarkdownBlockRenderer() {
    }

    static String render(String markdown, Palette palette) {
        if (StringUtils.isEmpty(markdown)) {
            return "";
        }

        LineCursor cursor = new LineCursor(markdown.split("\n", -1));
        RenderState state = new RenderState();

        while (cursor.hasNext()) {
            String line = cursor.next();

            if (state.inCodeBlock) {
                if (line.trim().startsWith("```")) {
                    handleCodeFenceClose(state, palette);
                } else {
                    handleCodeBlockBody(line, state);
                }
                continue;
            }
            dispatch(line, cursor, state, palette);
        }

        if (state.inCodeBlock && state.codeBuffer.length() > 0) {
            appendCodeBlock(state.html, state.codeBuffer.toString(), state.codeLang, palette);
        }
        state.closeListIfOpen();

        return state.html.toString();
    }

    private static void dispatch(String line, LineCursor cursor, RenderState state, Palette palette) {
        String trimmed = line.trim();
        if (trimmed.startsWith("```")) {
            handleCodeFenceOpen(trimmed, state);
            return;
        }

        if (trimmed.startsWith("$$")) {
            handleDisplayMathBlock(line, cursor, state, palette);
            return;
        }

        if (trimmed.startsWith("\\[")) {
            handleBracketDisplayMathBlock(line, cursor, state, palette);
            return;
        }

        if (isBareDisplayLatexLine(trimmed)) {
            handleBareDisplayMathBlock(trimmed, state, palette);
            return;
        }

        if (line.matches("^-{3,}$") || line.matches("^\\*{3,}$") || line.matches("^_{3,}$")) {
            handleHorizontalRule(state);
            return;
        }

        String leftTrimmed = line.stripLeading();

        if (headingLevel(leftTrimmed) > 0) {
            handleHeading(leftTrimmed, state, palette);
            return;
        }

        if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
            handleTable(line, cursor, state, palette);
            return;
        }

        if (leftTrimmed.startsWith(">")) {
            handleBlockquote(line, cursor, state, palette);
            return;
        }

        if (leftTrimmed.startsWith("- ") || leftTrimmed.startsWith("* ")) {
            handleUnorderedListItem(leftTrimmed, state, palette);
            return;
        }

        if (leftTrimmed.matches("^\\d+\\.\\s.*")) {
            handleOrderedListItem(leftTrimmed, state, palette);
            return;
        }

        if (state.inList) {
            state.closeListIfOpen();
        }

        if (line.isBlank()) {
            state.html.append("<br>");
            return;
        }

        handleParagraph(line, state, palette);
    }

    private static void handleCodeFenceOpen(String trimmed, RenderState state) {
        state.closeListIfOpen();
        state.inCodeBlock = true;
        state.codeLang = trimmed.length() > 3 ? HtmlEscaper.escape(trimmed.substring(3).trim()) : null;
        state.codeBuffer.setLength(0);
    }

    private static void handleCodeFenceClose(RenderState state, Palette palette) {
        state.inCodeBlock = false;
        appendCodeBlock(state.html, state.codeBuffer.toString(), state.codeLang, palette);
    }

    private static void handleCodeBlockBody(String line, RenderState state) {
        if (state.codeBuffer.length() > 0) {
            state.codeBuffer.append("\n");
        }
        state.codeBuffer.append(line);
    }

    private static void handleDisplayMathBlock(String firstLine, LineCursor cursor, RenderState state, Palette palette) {
        state.closeListIfOpen();

        String trimmedFirstLine = firstLine.trim();
        String afterOpen = trimmedFirstLine.substring(2);
        collectDisplayMathBlock(trimmedFirstLine, afterOpen, "$$", cursor, state, palette);
    }

    private static void handleBracketDisplayMathBlock(String firstLine, LineCursor cursor, RenderState state, Palette palette) {
        state.closeListIfOpen();

        String trimmedFirstLine = firstLine.trim();
        String afterOpen = trimmedFirstLine.substring(2);
        collectDisplayMathBlock(trimmedFirstLine, afterOpen, "\\]", cursor, state, palette);
    }

    private static void collectDisplayMathBlock(
            String fallbackFirstLine,
            String afterOpen,
            String closeDelimiter,
            LineCursor cursor,
            RenderState state,
            Palette palette
    ) {
        StringBuilder math = new StringBuilder();
        String trailingAfterClose = appendUntilCloseDelimiter(math, afterOpen, closeDelimiter);

        while (trailingAfterClose == null && cursor.hasNext()) {
            trailingAfterClose = appendUntilCloseDelimiter(math, cursor.next(), closeDelimiter);
        }

        String fallbackMathCode = math.toString().stripTrailing();
        if (fallbackMathCode.isBlank()) {
            handleParagraph(fallbackFirstLine, state, palette);
        } else {
            appendCodeBlock(state.html, fallbackMathCode.stripLeading(), "latex", palette);
        }

        if (StringUtils.isNotBlank(trailingAfterClose)) {
            handleParagraph(trailingAfterClose.trim(), state, palette);
        }
    }

    private static String appendUntilCloseDelimiter(StringBuilder math, String line, String closeDelimiter) {
        int closeIndex = line.indexOf(closeDelimiter);
        String mathLine = closeIndex >= 0 ? line.substring(0, closeIndex) : line;
        if (!mathLine.isBlank() || math.length() > 0) {
            if (math.length() > 0) {
                math.append("\n");
            }
            math.append(mathLine);
        }
        return closeIndex >= 0 ? line.substring(closeIndex + closeDelimiter.length()) : null;
    }

    private static void handleBareDisplayMathBlock(String trimmed, RenderState state, Palette palette) {
        state.closeListIfOpen();
        appendCodeBlock(state.html, trimmed, "latex", palette);
    }

    private static boolean isBareDisplayLatexLine(String trimmed) {
        return BARE_DISPLAY_LATEX_PATTERN.matcher(trimmed).matches()
                && LATEX_OPERATOR_PATTERN.matcher(trimmed).matches();
    }

    private static void handleHorizontalRule(RenderState state) {
        state.closeListIfOpen();
        state.html.append("<hr>");
    }

    private static void handleHeading(String leftTrimmed, RenderState state, Palette palette) {
        state.closeListIfOpen();
        int level = headingLevel(leftTrimmed);
        String headingText = leftTrimmed.substring(level).trim();
        state.html.append("<h").append(level).append(">");
        state.html.append(MarkdownInlineRenderer.render(headingText, palette));
        state.html.append("</h").append(level).append(">");
    }

    private static void handleTable(String firstLine, LineCursor cursor, RenderState state, Palette palette) {
        state.closeListIfOpen();

        List<String> tableLines = new ArrayList<>();
        tableLines.add(firstLine.trim());
        while (cursor.hasNext() && isTableLine(cursor.peek())) {
            tableLines.add(cursor.next().trim());
        }

        state.html.append("<table class=\"md-table\" width=\"100%\" cellpadding=\"6\" cellspacing=\"0\" border=\"0\"")
                .append(" style=\"margin: 6px 0;\">");

        boolean headerDone = false;
        for (int t = 0; t < tableLines.size(); t++) {
            String tableLine = tableLines.get(t);
            if (tableLine.matches("^\\|[\\s\\-:|]+\\|$")) {
                headerDone = true;
                continue;
            }

            List<String> cells = splitTableCells(tableLine);
            boolean isHeader = !headerDone && t == 0;

            state.html.append("<tr>");
            for (String rawCell : cells) {
                String cell = rawCell.trim();
                if (isHeader) {
                    state.html.append("<td style=\"padding: 6px 10px; border-bottom: 2px solid ")
                            .append(palette.codeBorder()).append(";\"><b>")
                            .append(MarkdownInlineRenderer.render(cell, palette)).append("</b></td>");
                } else {
                    state.html.append("<td style=\"padding: 6px 10px; border-bottom: 1px solid ")
                            .append(palette.codeBorder()).append(";\">")
                            .append(MarkdownInlineRenderer.render(cell, palette)).append("</td>");
                }
            }
            state.html.append("</tr>");
        }
        state.html.append("</table>");
    }

    private static List<String> splitTableCells(String tableLine) {
        String line = StringUtils.defaultString(tableLine).trim();
        if (line.startsWith("|")) {
            line = line.substring(1);
        }
        if (line.endsWith("|")) {
            line = line.substring(0, line.length() - 1);
        }

        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inInlineCode = false;
        int dollarMathDelimiterLength = 0;
        int mathBraceDepth = 0;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '\\' && index + 1 < line.length()) {
                char next = line.charAt(index + 1);
                if (next == '|' && dollarMathDelimiterLength == 0) {
                    cell.append(next);
                    index++;
                    continue;
                }
                cell.append(current);
                cell.append(next);
                index++;
                continue;
            }
            if (current == '`' && dollarMathDelimiterLength == 0) {
                inInlineCode = !inInlineCode;
                cell.append(current);
                continue;
            }
            if (!inInlineCode && current == '$') {
                int delimiterLength = dollarDelimiterLength(line, index, dollarMathDelimiterLength, mathBraceDepth);
                if (delimiterLength > 0) {
                    cell.append(current);
                    if (delimiterLength == 2) {
                        cell.append(line.charAt(index + 1));
                        index++;
                    }
                    dollarMathDelimiterLength = dollarMathDelimiterLength == 0 ? delimiterLength : 0;
                    mathBraceDepth = 0;
                    continue;
                }
            }
            if (dollarMathDelimiterLength > 0) {
                if (current == '{') {
                    mathBraceDepth++;
                } else if (current == '}') {
                    mathBraceDepth = Math.max(0, mathBraceDepth - 1);
                }
                cell.append(current);
                continue;
            }
            if (current == '|' && !inInlineCode) {
                cells.add(cell.toString());
                cell.setLength(0);
                continue;
            }
            cell.append(current);
        }
        cells.add(cell.toString());
        return cells;
    }

    private static int dollarDelimiterLength(String text, int index, int activeDelimiterLength, int braceDepth) {
        if (isEscaped(text, index)) {
            return 0;
        }
        if (activeDelimiterLength > 0) {
            if (braceDepth > 0) {
                return 0;
            }
            if (activeDelimiterLength == 2 && index + 1 < text.length() && text.charAt(index + 1) == '$') {
                return 2;
            }
            return activeDelimiterLength == 1 && (index + 1 >= text.length() || text.charAt(index + 1) != '$') ? 1 : 0;
        }
        if (index + 1 < text.length() && text.charAt(index + 1) == '$') {
            return findDollarMathClose(text, index + 2, true) >= 0 ? 2 : 0;
        }
        if (index + 1 >= text.length() || Character.isDigit(text.charAt(index + 1)) || text.charAt(index + 1) == '.' || text.charAt(index + 1) == ',') {
            return 0;
        }
        return findDollarMathClose(text, index + 1, false) >= 0 ? 1 : 0;
    }

    private static int findDollarMathClose(String text, int start, boolean display) {
        int braceDepth = 0;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\\') {
                index++;
                continue;
            }
            if (current == '{') {
                braceDepth++;
                continue;
            }
            if (current == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
                continue;
            }
            if (braceDepth == 0 && current == '$' && !isEscaped(text, index)) {
                if (display && index + 1 < text.length() && text.charAt(index + 1) == '$') {
                    return index;
                }
                if (!display && (index + 1 >= text.length() || text.charAt(index + 1) != '$')) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static boolean isEscaped(String text, int index) {
        int slashCount = 0;
        for (int cursor = index - 1; cursor >= 0 && text.charAt(cursor) == '\\'; cursor--) {
            slashCount++;
        }
        return slashCount % 2 == 1;
    }

    private static boolean isTableLine(String line) {
        String t = line.trim();
        return t.startsWith("|") && t.endsWith("|");
    }

    private static void handleBlockquote(String firstLine, LineCursor cursor, RenderState state, Palette palette) {
        state.closeListIfOpen();

        List<String> quoteLines = new ArrayList<>();
        quoteLines.add(extractQuoteContent(firstLine));
        while (cursor.hasNext() && cursor.peek().stripLeading().startsWith(">")) {
            quoteLines.add(extractQuoteContent(cursor.next()));
        }

        state.html.append("<blockquote>");
        for (int q = 0; q < quoteLines.size(); q++) {
            if (q > 0) {
                state.html.append("<br>");
            }
            state.html.append(MarkdownInlineRenderer.render(quoteLines.get(q), palette));
        }
        state.html.append("</blockquote>");
    }

    private static String extractQuoteContent(String line) {
        String stripped = line.stripLeading();
        String content = stripped.length() > 1 ? stripped.substring(1) : "";
        if (content.startsWith(" ")) {
            content = content.substring(1);
        }
        return content;
    }

    private static void handleUnorderedListItem(String leftTrimmed, RenderState state, Palette palette) {
        if (!state.inList || !"ul".equals(state.listType)) {
            state.closeListIfOpen();
            state.openList("ul");
        }
        state.html.append("<li>")
                .append(MarkdownInlineRenderer.render(leftTrimmed.substring(2), palette))
                .append("</li>");
    }

    private static void handleOrderedListItem(String leftTrimmed, RenderState state, Palette palette) {
        int listNumber = orderedListNumber(leftTrimmed);
        if (!state.inList || !"ol".equals(state.listType)) {
            state.closeListIfOpen();
            state.openOrderedList(listNumber);
        }
        int dotIndex = leftTrimmed.indexOf('.');
        state.html.append("<li>")
                .append(MarkdownInlineRenderer.render(leftTrimmed.substring(dotIndex + 2), palette))
                .append("</li>");
    }

    private static void handleParagraph(String line, RenderState state, Palette palette) {
        state.html.append("<p>").append(MarkdownInlineRenderer.render(line, palette)).append("</p>");
    }

    private static void appendCodeBlock(StringBuilder html, String code, String lang, Palette palette) {
        int languageFontSize = Math.max(9, Fonts.scale(Fonts.SIZE_MICRO) - 1);
        int codeFontSize = CodeFontResolver.resolveCodeFontSize();
        String blockBorder = palette.hrColor();
        String normalizedLang = StringUtils.trimToEmpty(lang);
        boolean latexFallback = Strings.CI.equals(normalizedLang, "latex");
        String borderStyle = latexFallback ? "dashed" : "solid";
        String tableClass = codeBlockClass(normalizedLang, latexFallback);
        String headerBackground = latexFallback ? palette.inlineCodeBg() : palette.codeHeaderBg();
        String languageAttribute = StringUtils.isNotEmpty(lang)
                ? " data-code-language=\"%s\"".formatted(lang)
                : "";

        html.append("<table class=\"").append(tableClass).append("\"").append(languageAttribute)
                .append(" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"")
                .append(" style=\"margin: 6px 0;\">");

        if (StringUtils.isNotEmpty(lang)) {
            html.append("<tr><td bgcolor=\"").append(headerBackground).append("\"")
                    .append(" style=\"border: 1px ").append(borderStyle).append(" ").append(blockBorder)
                    .append("; border-bottom: none; padding: 2px 8px; font-size: ").append(languageFontSize).append("px;\">");
            html.append("<font face=\"").append(palette.baseFontFamilyAttr()).append("\" color=\"")
                    .append(palette.mutedTextColor()).append("\">")
                    .append(lang)
                    .append("</font>");
            html.append("</td></tr>");
        }

        html.append("<tr><td bgcolor=\"").append(palette.codeBg()).append("\"")
                .append(" style=\"border: 1px ").append(borderStyle).append(" ").append(blockBorder)
                .append("; padding: 8px 12px;\">");
        html.append("<pre style=\"margin: 0;\"><font face=\"")
                .append(palette.monoFontFamilyAttr()).append("\" color=\"")
                .append(palette.codeText()).append("\" style=\"font-size: ")
                .append(codeFontSize).append("px;\">")
                .append(HtmlEscaper.escape(code))
                .append("</font></pre>");
        html.append("</td></tr>");
        html.append("</table>");
    }

    private static String codeBlockClass(String normalizedLang, boolean latexFallback) {
        if (latexFallback) {
            return "md-code-block md-latex-block";
        }
        if (Strings.CI.equals(normalizedLang, "mermaid")) {
            return "md-code-block md-diagram-block md-mermaid-block";
        }
        if (Strings.CI.equalsAny(normalizedLang, "smiles", "mol", "sdf")) {
            return "md-code-block md-diagram-block md-chem-block md-%s-block".formatted(normalizedLang.toLowerCase(Locale.ROOT));
        }
        return "md-code-block";
    }

    private static int orderedListNumber(String line) {
        int dotIndex = line.indexOf('.');
        if (dotIndex <= 0) {
            return 1;
        }

        try {
            return Integer.parseInt(line.substring(0, dotIndex));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int headingLevel(String line) {
        if (StringUtils.isEmpty(line) || line.charAt(0) != '#') {
            return 0;
        }

        int count = 0;
        while (count < line.length() && count < 6 && line.charAt(count) == '#') {
            count++;
        }

        if (count == 0 || count > 6 || count >= line.length()) {
            return 0;
        }

        return Character.isWhitespace(line.charAt(count)) ? count : 0;
    }

    private static final class LineCursor {
        private final String[] lines;
        private int index;

        LineCursor(String[] lines) {
            this.lines = lines;
        }

        boolean hasNext() {
            return index < lines.length;
        }

        String peek() {
            return lines[index];
        }

        String next() {
            return lines[index++];
        }
    }

    private static final class RenderState {
        final StringBuilder html = new StringBuilder();
        boolean inList;
        String listType;
        boolean inCodeBlock;
        String codeLang;
        final StringBuilder codeBuffer = new StringBuilder();

        void closeListIfOpen() {
            if (inList && listType != null) {
                html.append("</").append(listType).append(">");
            }
            inList = false;
            listType = null;
        }

        void openList(String type) {
            html.append("<").append(type).append(">");
            inList = true;
            listType = type;
        }

        void openOrderedList(int start) {
            if (start > 1) {
                html.append("<ol start=\"").append(start).append("\">");
            } else {
                html.append("<ol>");
            }
            inList = true;
            listType = "ol";
        }
    }
}
