package com.github.drafael.chat4j.chat.message;

import org.apache.commons.lang3.StringUtils;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static java.util.Collections.synchronizedMap;

final class HighlightJsCodeRenderer {

    private static final HighlightJsCodeRenderer INSTANCE = new HighlightJsCodeRenderer();
    private static final int MAX_CACHE_ENTRIES = 512;
    private static final String HIGHLIGHT_SCRIPT = resourceText("/web/highlight/highlight.min.js");
    private static final Pattern JAVA_PRIMITIVE_TYPE_SPAN = Pattern.compile(
            "<span class=\"hljs-type\">(boolean|byte|char|double|float|int|long|short|void)</span>"
    );
    private static final Map<String, String> LANGUAGE_ALIASES = Map.ofEntries(
            Map.entry("html", "xml"),
            Map.entry("xhtml", "xml"),
            Map.entry("svg", "xml"),
            Map.entry("js", "javascript"),
            Map.entry("jsx", "javascript"),
            Map.entry("mjs", "javascript"),
            Map.entry("cjs", "javascript"),
            Map.entry("ts", "typescript"),
            Map.entry("tsx", "typescript"),
            Map.entry("sh", "bash"),
            Map.entry("shell", "bash"),
            Map.entry("zsh", "bash"),
            Map.entry("md", "markdown"),
            Map.entry("yml", "yaml"),
            Map.entry("kt", "kotlin"),
            Map.entry("py", "python"),
            Map.entry("text", "plaintext"),
            Map.entry("txt", "plaintext"),
            Map.entry("c++", "cpp"),
            Map.entry("cc", "cpp"),
            Map.entry("c#", "csharp")
    );

    private final Map<RenderKey, Optional<String>> cache = synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<RenderKey, Optional<String>> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    });
    private Context context;
    private Value renderFunction;
    private boolean unavailable;

    private HighlightJsCodeRenderer() {
    }

    static HighlightJsCodeRenderer instance() {
        return INSTANCE;
    }

    Optional<String> render(String source, String language) {
        String normalizedLanguage = normalizeLanguage(language);
        if (StringUtils.isBlank(source) || StringUtils.isBlank(normalizedLanguage)) {
            return Optional.empty();
        }

        RenderKey key = new RenderKey(source, normalizedLanguage);
        Optional<String> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        Optional<String> rendered = renderUncached(key);
        cache.put(key, rendered);
        return rendered;
    }

    int cacheSize() {
        return cache.size();
    }

    String normalizeLanguage(String language) {
        String normalized = StringUtils.trimToEmpty(language).toLowerCase();
        int whitespaceIndex = StringUtils.indexOfAny(normalized, ' ', '\t', '\n', '\r');
        if (whitespaceIndex >= 0) {
            normalized = normalized.substring(0, whitespaceIndex);
        }
        if (normalized.startsWith("language-")) {
            normalized = normalized.substring("language-".length());
        }
        if (normalized.startsWith("lang-")) {
            normalized = normalized.substring("lang-".length());
        }
        return LANGUAGE_ALIASES.getOrDefault(normalized, normalized);
    }

    private Optional<String> renderUncached(RenderKey key) {
        synchronized (this) {
            if (unavailable) {
                return Optional.empty();
            }

            try {
                String html = renderer().execute(key.source(), key.language()).asString();
                html = normalizeHighlightedHtml(html, key.language());
                return StringUtils.isBlank(html) ? Optional.empty() : Optional.of(html);
            } catch (Throwable t) {
                if (!(t instanceof PolyglotException)) {
                    unavailable = true;
                    closeContext();
                }
                return Optional.empty();
            }
        }
    }

    private String normalizeHighlightedHtml(String html, String language) {
        if (StringUtils.isBlank(html)) {
            return html;
        }
        if ("java".equals(language) || "kotlin".equals(language)) {
            return JAVA_PRIMITIVE_TYPE_SPAN.matcher(html)
                    .replaceAll("<span class=\"hljs-keyword chat4j-primitive\">$1</span>");
        }
        return html;
    }

    private Value renderer() {
        if (renderFunction != null) {
            return renderFunction;
        }

        context = Context.newBuilder("js")
                .allowAllAccess(false)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        context.eval("js", HIGHLIGHT_SCRIPT);
        renderFunction = context.eval("js", """
                (function(source, language) {
                    var lang = String(language || '');
                    if (!lang || !hljs.getLanguage(lang)) {
                        return '';
                    }
                    var result = hljs.highlight(String(source || ''), {
                        language: lang,
                        ignoreIllegals: true
                    });
                    return result && result.value ? result.value : '';
                })
                """);
        return renderFunction;
    }

    private void closeContext() {
        if (context != null) {
            try {
                context.close(true);
            } catch (Exception ignored) {
                // Ignore cleanup failures; fallback rendering remains available.
            }
        }
        context = null;
        renderFunction = null;
    }

    private static String resourceText(String path) {
        try (InputStream input = HighlightJsCodeRenderer.class.getResourceAsStream(path)) {
            if (input == null) {
                return "";
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private record RenderKey(String source, String language) {
        @Override
        public String toString() {
            return "RenderKey[source=<masked>, language=%s]".formatted(language);
        }
    }
}
