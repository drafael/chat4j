package com.github.drafael.chat4j.chat.conversation.webview.shared;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TranscriptResources {

    private static final Pattern CSS_FONT_URL_PATTERN = Pattern.compile("url\\((['\\\"]?)(fonts/([^)'\\\"?]+))\\1\\)");
    private static final Pattern TEMPLATE_TOKEN_PATTERN = Pattern.compile("\\{\\{[a-z0-9-]+}}");

    private TranscriptResources() {
    }

    public static String resourceText(String path) {
        byte[] bytes = resourceBytes(path);
        return bytes.length == 0 ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    public static String requiredResourceText(String path) {
        byte[] bytes = requiredResourceBytes(path);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static byte[] resourceBytes(String path) {
        try (InputStream input = TranscriptResources.class.getResourceAsStream(path)) {
            return input == null ? new byte[0] : input.readAllBytes();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    public static byte[] requiredResourceBytes(String path) {
        try (InputStream input = TranscriptResources.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Required transcript resource is missing: %s".formatted(path));
            }
            return input.readAllBytes();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Required transcript resource could not be read: %s".formatted(path), e);
        }
    }

    public static String safeScriptContent(String script) {
        return StringUtils.defaultString(script).replace("</script", "<\\/script");
    }

    public static String inlineStylesheetFonts(String css) {
        if (StringUtils.isBlank(css)) {
            return "";
        }

        Matcher matcher = CSS_FONT_URL_PATTERN.matcher(css);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String fontPath = "/web/katex/%s".formatted(matcher.group(2));
            String dataUri = fontDataUri(fontPath);
            if (StringUtils.isBlank(dataUri)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement("url(%s)".formatted(dataUri)));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String iconDataUri(String path) {
        byte[] bytes = resourceBytes(path);
        if (bytes.length == 0) {
            return "";
        }
        String encoded = Base64.getEncoder().encodeToString(bytes);
        return "data:image/svg+xml;base64,%s".formatted(encoded);
    }

    public static String resolveTemplate(String template, Map<String, String> values) {
        String source = StringUtils.defaultString(template);
        Matcher tokens = TEMPLATE_TOKEN_PATTERN.matcher(source);
        while (tokens.find()) {
            String token = tokens.group();
            String key = token.substring(2, token.length() - 2);
            if (!values.containsKey(key)) {
                throw new IllegalStateException("Unresolved transcript template token: %s".formatted(token));
            }
        }

        String resolved = source;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            resolved = resolved.replace("{{%s}}".formatted(entry.getKey()), StringUtils.defaultString(entry.getValue()));
        }
        return resolved;
    }

    private static String fontDataUri(String path) {
        byte[] bytes = resourceBytes(path);
        if (bytes.length == 0) {
            return "";
        }

        String mediaType = Strings.CI.endsWith(path, ".woff2")
                ? "font/woff2"
                : Strings.CI.endsWith(path, ".woff") ? "font/woff" : "font/ttf";
        String encoded = Base64.getEncoder().encodeToString(bytes);
        return "data:%s;base64,%s".formatted(mediaType, encoded);
    }
}
