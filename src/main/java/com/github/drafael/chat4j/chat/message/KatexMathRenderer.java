package com.github.drafael.chat4j.chat.message;

import org.apache.commons.lang3.StringUtils;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class KatexMathRenderer {

    private static final KatexMathRenderer INSTANCE = new KatexMathRenderer();

    private final ConcurrentMap<RenderKey, Optional<String>> cache = new ConcurrentHashMap<>();
    private Context context;
    private Value renderFunction;
    private boolean unavailable;

    private KatexMathRenderer() {
    }

    static KatexMathRenderer instance() {
        return INSTANCE;
    }

    Optional<String> render(String source, boolean fallbackDisplayMode) {
        MathSource mathSource = MathSource.parse(source, fallbackDisplayMode);
        if (mathSource.tex().isBlank()) {
            return Optional.empty();
        }

        RenderKey key = new RenderKey(mathSource.tex(), mathSource.displayMode());
        return cache.computeIfAbsent(key, this::renderUncached);
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
        context.eval("js", SwingWebViewTranscriptView.katexScript());
        context.eval("js", SwingWebViewTranscriptView.mhchemScript());
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
    }
}
