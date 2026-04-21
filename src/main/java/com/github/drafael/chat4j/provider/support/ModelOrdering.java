package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static java.util.Collections.emptyList;

public final class ModelOrdering {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\d+|[a-zA-Z]+");
    private static final Pattern TRAILING_DATE_COMPACT_PATTERN = Pattern.compile("^(.*?)[-_](20\\d{2})(\\d{2})(\\d{2})$");
    private static final Pattern TRAILING_DATE_DELIMITED_PATTERN = Pattern.compile("^(.*?)[-_](20\\d{2})[-_](\\d{2})[-_](\\d{2})$");
    private static final Pattern COPILOT_GPT4O_DATED_ALIAS = Pattern.compile("gpt-4o[-_]20\\d{2}[-_]\\d{2}[-_]\\d{2}", Pattern.CASE_INSENSITIVE);
    private static final Pattern COPILOT_GPT4O_MINI_DATED_ALIAS = Pattern.compile("gpt-4o-mini[-_]20\\d{2}[-_]\\d{2}[-_]\\d{2}", Pattern.CASE_INSENSITIVE);

    private ModelOrdering() {
    }

    public static List<String> sanitizeAndSortByRecency(List<String> modelIds) {
        return sanitizeAndSortByProvider(null, modelIds);
    }

    public static List<String> sanitizeAndSortByProvider(String providerName, List<String> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return emptyList();
        }

        List<String> normalized = modelIds.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .map(modelId -> normalizeForProvider(providerName, modelId))
                .filter(modelId -> !modelId.isBlank())
                .distinct()
                .toList();

        List<String> filtered = applyProviderSpecificFiltering(providerName, normalized);
        return filtered.stream()
                .sorted(isOllama(providerName) ? ModelOrdering::compareNaturalAsc : ModelOrdering::compareByRecency)
                .toList();
    }

    public static int compareByRecency(String left, String right) {
        PrefixInfo leftPrefix = extractPrefix(left);
        PrefixInfo rightPrefix = extractPrefix(right);

        if (leftPrefix != null && rightPrefix != null) {
            int byPrefix = leftPrefix.prefix().compareToIgnoreCase(rightPrefix.prefix());
            if (byPrefix != 0) {
                return byPrefix;
            }

            int bySuffixRecency = compareWithoutPrefix(leftPrefix.suffix(), rightPrefix.suffix());
            if (bySuffixRecency != 0) {
                return bySuffixRecency;
            }

            return right.compareToIgnoreCase(left);
        }

        return compareWithoutPrefix(left, right);
    }

    private static PrefixInfo extractPrefix(String modelId) {
        int delimiterIdx = modelId.indexOf('/');
        if (delimiterIdx <= 0 || delimiterIdx >= modelId.length() - 1) {
            return null;
        }

        String prefix = modelId.substring(0, delimiterIdx).trim();
        String suffix = modelId.substring(delimiterIdx + 1).trim();
        if (prefix.isEmpty() || suffix.isEmpty()) {
            return null;
        }

        return new PrefixInfo(prefix, suffix);
    }

    private static int compareWithoutPrefix(String left, String right) {
        TrailingDateInfo leftInfo = trailingDateInfo(left);
        TrailingDateInfo rightInfo = trailingDateInfo(right);

        boolean leftHasTrailingDate = leftInfo != null;
        boolean rightHasTrailingDate = rightInfo != null;
        if (leftHasTrailingDate != rightHasTrailingDate) {
            return leftHasTrailingDate ? 1 : -1;
        }

        if (!leftHasTrailingDate) {
            int byNatural = compareNaturalDesc(left, right);
            return byNatural != 0
                    ? byNatural
                    : right.compareToIgnoreCase(left);
        }

        int byBaseVersion = compareNaturalDesc(leftInfo.baseId(), rightInfo.baseId());
        if (byBaseVersion != 0) {
            return byBaseVersion;
        }

        int byDate = Long.compare(rightInfo.dateScore(), leftInfo.dateScore());
        if (byDate != 0) {
            return byDate;
        }

        int byNatural = compareNaturalDesc(left, right);
        if (byNatural != 0) {
            return byNatural;
        }

        return right.compareToIgnoreCase(left);
    }

    private static TrailingDateInfo trailingDateInfo(String modelId) {
        TrailingDateInfo compact = trailingDateInfo(modelId, TRAILING_DATE_COMPACT_PATTERN);
        return compact != null
                ? compact
                : trailingDateInfo(modelId, TRAILING_DATE_DELIMITED_PATTERN);
    }

    private static TrailingDateInfo trailingDateInfo(String modelId, Pattern pattern) {
        Matcher matcher = pattern.matcher(modelId);
        if (!matcher.matches()) {
            return null;
        }

        int year = Integer.parseInt(matcher.group(2));
        int month = Integer.parseInt(matcher.group(3));
        int day = Integer.parseInt(matcher.group(4));
        if (!isValidDate(year, month, day)) {
            return null;
        }

        long dateScore = ((long) year * 10_000) + ((long) month * 100) + day;
        return new TrailingDateInfo(matcher.group(1), dateScore);
    }

    private static int compareNaturalDesc(String left, String right) {
        List<String> leftTokens = tokenize(left);
        List<String> rightTokens = tokenize(right);
        int limit = Math.min(leftTokens.size(), rightTokens.size());

        for (int i = 0; i < limit; i++) {
            String leftToken = leftTokens.get(i);
            String rightToken = rightTokens.get(i);
            boolean leftNumeric = Character.isDigit(leftToken.charAt(0));
            boolean rightNumeric = Character.isDigit(rightToken.charAt(0));

            if (leftNumeric && rightNumeric) {
                int comparison = Long.compare(parseLongSafely(rightToken), parseLongSafely(leftToken));
                if (comparison != 0) {
                    return comparison;
                }
                continue;
            }

            if (leftNumeric != rightNumeric) {
                return leftNumeric ? -1 : 1;
            }

            int comparison = rightToken.compareToIgnoreCase(leftToken);
            if (comparison != 0) {
                return comparison;
            }
        }

        return Integer.compare(rightTokens.size(), leftTokens.size());
    }

    private static int compareNaturalAsc(String left, String right) {
        List<String> leftTokens = tokenize(left);
        List<String> rightTokens = tokenize(right);
        int limit = Math.min(leftTokens.size(), rightTokens.size());

        for (int i = 0; i < limit; i++) {
            String leftToken = leftTokens.get(i);
            String rightToken = rightTokens.get(i);
            boolean leftNumeric = Character.isDigit(leftToken.charAt(0));
            boolean rightNumeric = Character.isDigit(rightToken.charAt(0));

            if (leftNumeric && rightNumeric) {
                int comparison = Long.compare(parseLongSafely(leftToken), parseLongSafely(rightToken));
                if (comparison != 0) {
                    return comparison;
                }
                continue;
            }

            if (leftNumeric != rightNumeric) {
                return leftNumeric ? -1 : 1;
            }

            int comparison = leftToken.compareToIgnoreCase(rightToken);
            if (comparison != 0) {
                return comparison;
            }
        }

        int byTokenCount = Integer.compare(leftTokens.size(), rightTokens.size());
        return byTokenCount != 0
                ? byTokenCount
                : left.compareToIgnoreCase(right);
    }

    private static String normalizeForProvider(String providerName, String modelId) {
        if (isGoogleAi(providerName) && modelId.regionMatches(true, 0, "models/", 0, "models/".length())) {
            return modelId.substring("models/".length()).trim();
        }
        return modelId;
    }

    private static List<String> applyProviderSpecificFiltering(String providerName, List<String> modelIds) {
        if (!isGitHubCopilot(providerName)) {
            return modelIds;
        }

        boolean hasCanonicalGpt4o = modelIds.stream().anyMatch("gpt-4o"::equalsIgnoreCase);
        boolean hasCanonicalGpt4oMini = modelIds.stream().anyMatch("gpt-4o-mini"::equalsIgnoreCase);

        return modelIds.stream()
                .filter(modelId -> {
                    String normalized = modelId.trim().toLowerCase();
                    if (normalized.startsWith("gpt-3.5")) {
                        return false;
                    }

                    if (hasCanonicalGpt4o && COPILOT_GPT4O_DATED_ALIAS.matcher(normalized).matches()) {
                        return false;
                    }

                    return !(hasCanonicalGpt4oMini && COPILOT_GPT4O_MINI_DATED_ALIAS.matcher(normalized).matches());
                })
                .toList();
    }

    private static boolean isOllama(String providerName) {
        return providerName != null && "ollama".equalsIgnoreCase(providerName.trim());
    }

    private static boolean isGoogleAi(String providerName) {
        return providerName != null && "google ai".equalsIgnoreCase(providerName.trim());
    }

    private static boolean isGitHubCopilot(String providerName) {
        return providerName != null && "github copilot".equalsIgnoreCase(providerName.trim());
    }

    private static List<String> tokenize(String value) {
        Matcher matcher = TOKEN_PATTERN.matcher(value);
        return matcher.results()
                .map(result -> result.group().toLowerCase())
                .toList();
    }

    private static long parseLongSafely(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private static boolean isValidDate(int year, int month, int day) {
        try {
            LocalDate.of(year, month, day);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    private record PrefixInfo(String prefix, String suffix) {
    }

    private record TrailingDateInfo(String baseId, long dateScore) {
    }
}
