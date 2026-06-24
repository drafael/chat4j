package com.github.drafael.chat4j.chat.render;

import java.util.regex.Pattern;

final class MarkdownMathHeuristics {

    private static final Pattern NUMERIC_RANGE_MATH_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?\\s*[-–—]\\s*\\d+(?:\\.\\d+)?");
    private static final Pattern NUMERIC_MATH_EXPRESSION_PATTERN = Pattern.compile("[0-9A-Za-z.\\s()+\\-*/=^_{}]+");
    private static final Pattern MATH_OPERATOR_PATTERN = Pattern.compile("[+*/=^_\\-]");
    private static final Pattern TRAILING_MATH_OPERATOR_PATTERN = Pattern.compile("[+*/=^_\\-]\\s*$");
    private static final Pattern MULTI_LETTER_WORD_PATTERN = Pattern.compile("[A-Za-z]{2,}");

    private MarkdownMathHeuristics() {
    }

    static boolean isNumericLeadingMathContent(String content) {
        String normalized = content.trim();
        if (normalized.contains("\\")) {
            return true;
        }
        if (NUMERIC_RANGE_MATH_PATTERN.matcher(normalized).matches()) {
            return true;
        }
        return MATH_OPERATOR_PATTERN.matcher(normalized).find()
                && !TRAILING_MATH_OPERATOR_PATTERN.matcher(normalized).find()
                && NUMERIC_MATH_EXPRESSION_PATTERN.matcher(normalized).matches()
                && !MULTI_LETTER_WORD_PATTERN.matcher(normalized).find();
    }
}
