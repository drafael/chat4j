package com.github.drafael.chat4j.chat.content;

import org.apache.commons.lang3.StringUtils;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Collections.synchronizedMap;

public final class KatexMathRenderer {

    private static final KatexMathRenderer INSTANCE = new KatexMathRenderer();
    private static final int MAX_CACHE_ENTRIES = 512;
    private static final String KATEX_SCRIPT = resourceText("/web/katex/katex.min.js");
    private static final String MHCHEM_SCRIPT = resourceText("/web/katex/contrib/mhchem.min.js");

    private final Map<RenderKey, Optional<String>> cache = synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<RenderKey, Optional<String>> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    });
    private Context context;
    private Value renderFunction;
    private boolean unavailable;

    private KatexMathRenderer() {
    }

    public static KatexMathRenderer instance() {
        return INSTANCE;
    }

    public Optional<String> render(String source, boolean fallbackDisplayMode) {
        MathSource mathSource = MathSource.parse(source, fallbackDisplayMode);
        if (mathSource.tex().isBlank()) {
            return Optional.empty();
        }

        RenderKey key = new RenderKey(mathSource.tex(), mathSource.displayMode());
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

    private Optional<String> renderUncached(RenderKey key) {
        synchronized (this) {
            if (unavailable) {
                return Optional.empty();
            }

            try {
                Value renderer = renderer();
                Optional<String> rendered = renderWithFallback(renderer, key.tex(), key.displayMode());
                if (rendered.isPresent()) {
                    return rendered;
                }

                String recoveredTex = removeUnmatchedClosingBraces(key.tex());
                if (!recoveredTex.equals(key.tex())) {
                    return renderWithFallback(renderer, recoveredTex, key.displayMode());
                }

                return Optional.empty();
            } catch (Throwable t) {
                if (!(t instanceof PolyglotException)) {
                    unavailable = true;
                    closeContext();
                }
                return Optional.empty();
            }
        }
    }

    private Optional<String> renderWithFallback(Value renderer, String tex, boolean displayMode) {
        String html = renderer.execute(tex, displayMode).asString();
        if (StringUtils.isBlank(html) || html.contains("class=\"katex-error\"")) {
            return Optional.empty();
        }
        return Optional.of(html);
    }

    private String removeUnmatchedClosingBraces(String tex) {
        StringBuilder repaired = new StringBuilder(tex.length());
        int depth = 0;
        boolean escaped = false;
        for (int index = 0; index < tex.length(); index++) {
            char current = tex.charAt(index);
            if (escaped) {
                repaired.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                repaired.append(current);
                escaped = true;
                continue;
            }
            if (current == '{') {
                depth++;
                repaired.append(current);
                continue;
            }
            if (current == '}') {
                if (depth > 0) {
                    depth--;
                    repaired.append(current);
                }
                continue;
            }
            repaired.append(current);
        }
        return repaired.toString();
    }

    private Value renderer() {
        if (renderFunction != null) {
            return renderFunction;
        }

        context = Context.newBuilder("js")
                .allowAllAccess(false)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        context.eval("js", KATEX_SCRIPT);
        context.eval("js", MHCHEM_SCRIPT);
        renderFunction = context.eval("js", """
                (function(tex, displayMode) {
                    return katex.renderToString(String(tex || ''), {
                        displayMode: displayMode === true,
                        throwOnError: false,
                        trust: false,
                        strict: 'warn'
                    });
                })
                """);
        return renderFunction;
    }

    private static String resourceText(String path) {
        try (InputStream input = KatexMathRenderer.class.getResourceAsStream(path)) {
            return input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
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

    record MathSource(String tex, boolean displayMode) {

        @Override
        public String toString() {
            return "MathSource[tex=<masked>, displayMode=%s]".formatted(displayMode);
        }

        static MathSource parse(String source, boolean fallbackDisplayMode) {
            String text = StringUtils.trimToEmpty(source);
            if (text.length() >= 4 && text.startsWith("$$") && text.endsWith("$$")) {
                return new MathSource(text.substring(2, text.length() - 2).trim(), true);
            }
            if (text.length() >= 4 && text.startsWith("\\[") && text.endsWith("\\]")) {
                return new MathSource(text.substring(2, text.length() - 2).trim(), true);
            }
            if (text.length() >= 4 && text.startsWith("\\(") && text.endsWith("\\)")) {
                return new MathSource(text.substring(2, text.length() - 2).trim(), false);
            }
            if (text.length() >= 2 && text.startsWith("$") && text.endsWith("$")) {
                return new MathSource(text.substring(1, text.length() - 1).trim(), false);
            }
            return new MathSource(text, fallbackDisplayMode);
        }
    }

    private record RenderKey(String tex, boolean displayMode) {
        @Override
        public String toString() {
            return "RenderKey[tex=<masked>, displayMode=%s]".formatted(displayMode);
        }
    }
}
