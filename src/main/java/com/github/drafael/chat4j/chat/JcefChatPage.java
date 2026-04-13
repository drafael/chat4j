package com.github.drafael.chat4j.chat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class JcefChatPage {

    private static final Object LOCK = new Object();
    private static volatile String pageUrl;

    private JcefChatPage() {
    }

    static String url() {
        String existing = pageUrl;
        if (existing != null) {
            return existing;
        }

        synchronized (LOCK) {
            if (pageUrl != null) {
                return pageUrl;
            }

            try {
                Path pageDir = Files.createTempDirectory("chat4j-jcef-chat");
                copyResource(
                        "META-INF/resources/webjars/markdown-it/14.1.0/dist/markdown-it.min.js",
                        pageDir.resolve("markdown-it.min.js")
                );
                copyResource(
                        "META-INF/resources/webjars/katex/0.16.11/dist/katex.min.js",
                        pageDir.resolve("katex.min.js")
                );
                copyResource(
                        "META-INF/resources/webjars/katex/0.16.11/dist/katex.min.css",
                        pageDir.resolve("katex.min.css")
                );
                copyKatexFonts(pageDir.resolve("fonts"));

                Path pageFile = pageDir.resolve("chat.html");
                Files.writeString(pageFile, chatHtml(), StandardCharsets.UTF_8);
                pageUrl = pageFile.toUri().toString();
                return pageUrl;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to prepare JCEF chat page", e);
            }
        }
    }

    private static String chatHtml() {
        return """
                <!doctype html>
                <html>
                <head>
                    <meta charset="utf-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                    <link rel="stylesheet" href="./katex.min.css" />
                    <style>
                        :root {
                            --text-color: #1d1d1f;
                            --muted-text-color: #8e8e93;
                            --link-color: #535353;
                            --surface-bg: #f5f5f7;
                            --separator-color: #e0e0e4;
                            --code-bg: #f5f5f5;
                            --code-border: #e0e0e4;
                            --code-header-bg: #ebebeb;
                            --inline-code-bg: #ededef;
                            --code-text: #1d1d1f;
                            --lang-color: #8e8e93;
                            --user-bubble-bg: #efeff1;
                            --base-font-family: -apple-system, BlinkMacSystemFont, '.AppleSystemUIFont', 'Helvetica Neue', 'Apple Color Emoji', 'Segoe UI Emoji', 'Noto Color Emoji', sans-serif;
                            --mono-font-family: Monaco, Menlo, Consolas, 'Courier New', monospace;
                            --base-font-size: 13px;
                            --mono-font-size: 12px;
                        }
                        html, body {
                            margin: 0;
                            padding: 0;
                            width: 100%;
                            height: 100%;
                            overflow: hidden;
                            background: transparent;
                            font-family: var(--base-font-family);
                            font-size: var(--base-font-size);
                            line-height: 1.4;
                            color: var(--text-color);
                            direction: ltr;
                            -webkit-font-smoothing: antialiased;
                            text-rendering: optimizeLegibility;
                        }
                        #chat-messages {
                            display: flex;
                            flex-direction: column;
                            position: absolute;
                            top: 0;
                            left: 0;
                            right: 0;
                            bottom: 0;
                            padding: 12px;
                            box-sizing: border-box;
                            overflow-y: auto;
                            overflow-x: hidden;
                        }
                        .message {
                            margin: 4px 0;
                            word-wrap: break-word;
                            overflow-wrap: break-word;
                        }
                        .message.user {
                            align-self: flex-end;
                            max-width: 70%;
                            background: var(--user-bubble-bg);
                            border-radius: 12px;
                            padding: 6px 12px;
                        }
                        .message.assistant {
                            align-self: flex-start;
                            max-width: 85%;
                            padding: 4px 2px;
                        }
                        .message-content {
                            overflow-wrap: break-word;
                            word-break: break-word;
                        }
                        .message-content.raw {
                            white-space: pre-wrap;
                            font-family: var(--mono-font-family);
                            font-size: var(--mono-font-size);
                        }
                        .spacer {
                            flex: 1 1 auto;
                        }
                        h1, h2, h3, h4, h5, h6 {
                            margin-left: 0;
                            margin-right: 0;
                            font-weight: bold;
                        }
                        h1 { font-size: 1.4em; margin-top: 14px; margin-bottom: 6px; }
                        h2 { font-size: 1.2em; margin-top: 12px; margin-bottom: 5px; }
                        h3 { font-size: 1.1em; margin-top: 10px; margin-bottom: 4px; }
                        h4 { font-size: 1.0em; margin-top: 9px; margin-bottom: 4px; }
                        h5 { font-size: 0.9em; margin-top: 8px; margin-bottom: 3px; }
                        h6 { font-size: 0.9em; margin-top: 8px; margin-bottom: 3px; }
                        p { margin: 5px 0; }
                        ul, ol { margin: 4px 0; padding-left: 20px; }
                        li { margin: 1px 0; }
                        a { text-decoration: none; color: var(--link-color); }
                        a:hover { text-decoration: underline; }
                        hr { border: none; border-top: 1px solid var(--separator-color); margin: 10px 0; height: 0; }
                        blockquote {
                            margin: 6px 0;
                            padding: 4px 10px;
                            border-left: 3px solid var(--separator-color);
                            color: var(--muted-text-color);
                        }
                        table { border-collapse: collapse; margin: 6px 0; width: 100%; }
                        th, td { border-bottom: 1px solid var(--separator-color); padding: 6px 10px; text-align: left; }
                        th { font-weight: 600; }
                        pre {
                            margin: 6px 0;
                            padding: 8px 12px;
                            background: var(--code-bg);
                            border: 1px solid var(--code-border);
                            border-radius: 6px;
                            overflow-x: auto;
                            white-space: pre;
                            font-family: var(--mono-font-family);
                            font-size: var(--mono-font-size);
                            color: var(--code-text);
                        }
                        pre code {
                            background: none;
                            padding: 0;
                            border-radius: 0;
                        }
                        code {
                            padding: 1px 4px;
                            border-radius: 4px;
                            background: var(--inline-code-bg);
                            font-family: var(--mono-font-family);
                            font-size: var(--mono-font-size);
                        }
                        .katex-display-wrapper {
                            margin: 8px 0;
                            overflow-x: auto;
                        }
                        .katex-error {
                            color: var(--muted-text-color) !important;
                            font-family: var(--mono-font-family) !important;
                            font-size: var(--mono-font-size) !important;
                        }
                    </style>
                    <script src="./markdown-it.min.js"></script>
                    <script src="./katex.min.js"></script>
                </head>
                <body>
                    <div id="chat-messages">
                        <div class="spacer"></div>
                    </div>
                    <script>
                        (function () {
                            var container = document.getElementById('chat-messages');
                            var md = window.markdownit({ html: false, linkify: true, breaks: true, typographer: true });

                            var state = {
                                renderMode: 'preview',
                                mathOptions: { latexEnabled: true, singleDollarEnabled: true, bracketDelimitersEnabled: true },
                                messages: {}
                            };

                            var mathTimers = {};

                            var escapeHtml = function (value) {
                                return (value || '')
                                    .replace(/&/g, '&amp;')
                                    .replace(/</g, '&lt;')
                                    .replace(/>/g, '&gt;')
                                    .replace(/"/g, '&quot;');
                            };

                            var LATEX_CMD = /^\\\\(?:text|frac|sqrt|sum|prod|int|lim|begin|end|left|right|big|Big|bigg|Bigg|boxed|underbrace|overbrace|overset|underset|hat|bar|vec|dot|ddot|tilde|mathbb|mathbf|mathcal|mathrm|mathit|mathsf|mathfrak|operatorname|huge|Huge|large|Large|LARGE|small|tiny|quad|qquad|hspace|vspace|rightarrow|leftarrow|Rightarrow|Leftarrow|longrightarrow|hookrightarrow|implies|iff|forall|exists|nabla|partial|infty|alpha|beta|gamma|delta|epsilon|theta|lambda|mu|pi|sigma|omega|phi|psi|cdot|cdots|ldots|times|div|pm|mp|leq|geq|neq|approx|equiv|sim|propto|subset|supset|cup|cap|in|notin|log|ln|sin|cos|tan|exp|min|max|sup|inf|det|dim|ker|hom|binom|dbinom|tbinom|cancel|color|bold|mathring)[^a-zA-Z]/;

                            var wrapStandaloneLatex = function (text) {
                                if (!state.mathOptions.latexEnabled || !text) return text;
                                var lines = text.split('\\n');
                                var result = [];
                                for (var i = 0; i < lines.length; i++) {
                                    var line = lines[i];
                                    var trimmed = line.trim();
                                    if (trimmed.length > 2
                                            && LATEX_CMD.test(trimmed)
                                            && trimmed.indexOf('$$') === -1
                                            && trimmed.indexOf('$') === -1) {
                                        result.push('$$' + trimmed + '$$');
                                    } else {
                                        result.push(line);
                                    }
                                }
                                return result.join('\\n');
                            };

                            var normalizeBracketDelimiters = function (text) {
                                if (!state.mathOptions.bracketDelimitersEnabled || !text) return text;
                                var output = '';
                                var i = 0;
                                while (i < text.length) {
                                    if (text.substr(i, 2) === '\\\\[') {
                                        var end = text.indexOf('\\\\]', i + 2);
                                        if (end >= 0) { output += '$$' + text.substring(i + 2, end) + '$$'; i = end + 2; continue; }
                                    }
                                    if (text.substr(i, 2) === '\\\\(') {
                                        var end2 = text.indexOf('\\\\)', i + 2);
                                        if (end2 >= 0) { output += '$' + text.substring(i + 2, end2) + '$'; i = end2 + 2; continue; }
                                    }
                                    output += text.charAt(i);
                                    i++;
                                }
                                return output;
                            };

                            var extractMathTokensFromText = function (text, blocks, inlines) {
                                var working = normalizeBracketDelimiters(wrapStandaloneLatex(text));
                                var blockRegex = /\\$\\$([\\s\\S]+?)\\$\\$/g;
                                working = working.replace(blockRegex, function (m, expr) {
                                    var token = '@@MATH_BLOCK_' + blocks.length + '@@';
                                    blocks.push((expr || '').trim());
                                    return token;
                                });

                                if (state.mathOptions.singleDollarEnabled) {
                                    working = working.replace(/(^|[^\\\\])\\$([^\\n$]+?)\\$/g, function (m, prefix, expr) {
                                        var token = '@@MATH_INLINE_' + inlines.length + '@@';
                                        inlines.push((expr || '').trim());
                                        return prefix + token;
                                    });
                                }

                                return working;
                            };

                            var renderMarkdownTemplateWithMathTokens = function (text) {
                                var source = text || '';
                                if (!state.mathOptions.latexEnabled) {
                                    return { html: md.render(source), blocks: [], inlines: [] };
                                }

                                var blocks = [];
                                var inlines = [];
                                var parsedTokens = md.parse(source, {});

                                parsedTokens.forEach(function (token) {
                                    if (token.type !== 'inline' || !token.children) {
                                        return;
                                    }

                                    token.children.forEach(function (child) {
                                        if (child.type === 'text' && child.content) {
                                            child.content = extractMathTokensFromText(child.content, blocks, inlines);
                                        }
                                    });
                                });

                                return {
                                    html: md.renderer.render(parsedTokens, md.options, {}),
                                    blocks: blocks,
                                    inlines: inlines
                                };
                            };

                            var balanceBraces = function (expr) {
                                var open = 0;
                                for (var i = 0; i < expr.length; i++) {
                                    if (expr.charAt(i) === '{' && (i === 0 || expr.charAt(i - 1) !== '\\\\')) open++;
                                    if (expr.charAt(i) === '}' && (i === 0 || expr.charAt(i - 1) !== '\\\\')) open--;
                                }
                                if (open > 0) {
                                    for (var j = 0; j < open; j++) expr += '}';
                                } else if (open < 0) {
                                    var excess = -open;
                                    var result = '';
                                    for (var k = expr.length - 1; k >= 0 && excess > 0; k--) {
                                        if (expr.charAt(k) === '}' && (k === 0 || expr.charAt(k - 1) !== '\\\\')) {
                                            excess--;
                                        } else {
                                            break;
                                        }
                                    }
                                    expr = expr.substring(0, expr.length + open);
                                }
                                return expr;
                            };

                            var renderKatex = function (expression, displayMode) {
                                try {
                                    var html = window.katex.renderToString(expression, {
                                        displayMode: displayMode,
                                        throwOnError: true,
                                        strict: 'ignore'
                                    });
                                    return html;
                                } catch (e) {
                                    // Retry with balanced braces
                                    var balanced = balanceBraces(expression);
                                    if (balanced !== expression) {
                                        try {
                                            return window.katex.renderToString(balanced, {
                                                displayMode: displayMode,
                                                throwOnError: false,
                                                strict: 'ignore'
                                            });
                                        } catch (e2) {}
                                    }
                                    return window.katex.renderToString(expression, {
                                        displayMode: displayMode,
                                        throwOnError: false,
                                        strict: 'ignore'
                                    });
                                }
                            };

                            var injectMathTokens = function (html, tokens) {
                                var output = html;
                                tokens.blocks.forEach(function (expr, i) {
                                    output = output.split('@@MATH_BLOCK_' + i + '@@').join(
                                        '<div class="katex-display-wrapper">' + renderKatex(expr, true) + '</div>'
                                    );
                                });
                                tokens.inlines.forEach(function (expr, i) {
                                    output = output.split('@@MATH_INLINE_' + i + '@@').join(renderKatex(expr, false));
                                });
                                return output;
                            };

                            var renderMarkdownWithMath = function (text, contentEl, skipMath) {
                                var rendered = renderMarkdownTemplateWithMathTokens(text);
                                var html = rendered.html;

                                if (skipMath) {
                                    rendered.blocks.forEach(function (expr, i) {
                                        html = html.split('@@MATH_BLOCK_' + i + '@@').join(
                                            '<div class="katex-display-wrapper"><code>' + escapeHtml(expr) + '</code></div>'
                                        );
                                    });
                                    rendered.inlines.forEach(function (expr, i) {
                                        html = html.split('@@MATH_INLINE_' + i + '@@').join('<code>' + escapeHtml(expr) + '</code>');
                                    });
                                    contentEl.innerHTML = html;
                                    contentEl._pendingTokens = rendered;
                                } else {
                                    contentEl.innerHTML = injectMathTokens(html, rendered);
                                    contentEl._pendingTokens = null;
                                }

                                normalizeLinks(contentEl);
                            };

                            var flushMath = function (id) {
                                var msg = state.messages[id];
                                if (!msg) return;
                                var contentEl = msg.contentEl;
                                if (contentEl._pendingTokens) {
                                    var tokens = contentEl._pendingTokens;
                                    var html = contentEl.innerHTML;
                                    tokens.blocks.forEach(function (expr, i) {
                                        html = html.split('<div class="katex-display-wrapper"><code>' + escapeHtml(expr) + '</code></div>').join(
                                            '<div class="katex-display-wrapper">' + renderKatex(expr, true) + '</div>'
                                        );
                                    });
                                    tokens.inlines.forEach(function (expr, i) {
                                        html = html.split('<code>' + escapeHtml(expr) + '</code>').join(renderKatex(expr, false));
                                    });
                                    contentEl.innerHTML = html;
                                    contentEl._pendingTokens = null;
                                    normalizeLinks(contentEl);
                                }
                            };

                            var scheduleMathFlush = function (id) {
                                if (mathTimers[id]) clearTimeout(mathTimers[id]);
                                mathTimers[id] = setTimeout(function () {
                                    flushMath(id);
                                    delete mathTimers[id];
                                }, 150);
                            };

                            var normalizeLinks = function (el) {
                                el.querySelectorAll('a[href]').forEach(function (a) {
                                    a.setAttribute('target', '_blank');
                                    a.setAttribute('rel', 'noopener noreferrer');
                                });
                            };

                            var renderMessage = function (msg) {
                                var contentEl = msg.contentEl;
                                var text = msg.text || '';

                                if (msg.role === 'user') {
                                    contentEl.className = 'message-content';
                                    contentEl.innerHTML = escapeHtml(text).replace(/\\n/g, '<br>');
                                    return;
                                }

                                var useRaw = state.renderMode === 'markdown';
                                if (useRaw) {
                                    contentEl.className = 'message-content raw';
                                    contentEl.textContent = text;
                                    return;
                                }

                                contentEl.className = 'message-content';
                                var debounce = msg.streaming;
                                renderMarkdownWithMath(text, contentEl, debounce);
                                if (debounce) {
                                    scheduleMathFlush(msg.id);
                                }
                            };

                            window.chat4jRenderer = {
                                addMessage: function (id, role, text, options) {
                                    var el = document.createElement('div');
                                    el.id = id;
                                    el.className = 'message ' + role;
                                    var contentEl = document.createElement('div');
                                    contentEl.className = 'message-content';
                                    el.appendChild(contentEl);

                                    var spacer = container.querySelector('.spacer');
                                    if (spacer) {
                                        container.insertBefore(el, spacer);
                                    } else {
                                        container.appendChild(el);
                                    }

                                    var msg = {
                                        id: id,
                                        role: role,
                                        text: text || '',
                                        streaming: options && options.streaming,
                                        el: el,
                                        contentEl: contentEl
                                    };
                                    state.messages[id] = msg;
                                    renderMessage(msg);
                                },

                                updateMessage: function (id, text) {
                                    var msg = state.messages[id];
                                    if (!msg) return;
                                    msg.text = text || '';
                                    renderMessage(msg);
                                },

                                finishMessage: function (id) {
                                    var msg = state.messages[id];
                                    if (!msg) return;
                                    msg.streaming = false;
                                    if (mathTimers[id]) {
                                        clearTimeout(mathTimers[id]);
                                        delete mathTimers[id];
                                    }
                                    renderMessage(msg);
                                },

                                setRenderMode: function (mode) {
                                    state.renderMode = mode || 'preview';
                                    Object.keys(state.messages).forEach(function (id) {
                                        renderMessage(state.messages[id]);
                                    });
                                },

                                setMathOptions: function (options) {
                                    if (options) {
                                        state.mathOptions = options;
                                        Object.keys(state.messages).forEach(function (id) {
                                            renderMessage(state.messages[id]);
                                        });
                                    }
                                },

                                setTheme: function (theme) {
                                    if (!theme) return;
                                    var root = document.documentElement;
                                    if (theme.textColor) root.style.setProperty('--text-color', theme.textColor);
                                    if (theme.mutedTextColor) root.style.setProperty('--muted-text-color', theme.mutedTextColor);
                                    if (theme.linkColor) root.style.setProperty('--link-color', theme.linkColor);
                                    if (theme.surfaceBg) root.style.setProperty('--surface-bg', theme.surfaceBg);
                                    if (theme.separatorColor) root.style.setProperty('--separator-color', theme.separatorColor);
                                    if (theme.codeBg) root.style.setProperty('--code-bg', theme.codeBg);
                                    if (theme.codeBorder) root.style.setProperty('--code-border', theme.codeBorder);
                                    if (theme.codeHeaderBg) root.style.setProperty('--code-header-bg', theme.codeHeaderBg);
                                    if (theme.inlineCodeBg) root.style.setProperty('--inline-code-bg', theme.inlineCodeBg);
                                    if (theme.codeText) root.style.setProperty('--code-text', theme.codeText);
                                    if (theme.langColor) root.style.setProperty('--lang-color', theme.langColor);
                                    if (theme.userBubbleBg) root.style.setProperty('--user-bubble-bg', theme.userBubbleBg);
                                    if (theme.baseFontFamily) root.style.setProperty('--base-font-family', theme.baseFontFamily);
                                    if (theme.monoFontFamily) root.style.setProperty('--mono-font-family', theme.monoFontFamily);
                                    if (theme.baseFontSize) root.style.setProperty('--base-font-size', theme.baseFontSize + 'px');
                                    if (theme.monoFontSize) root.style.setProperty('--mono-font-size', theme.monoFontSize + 'px');
                                    document.body.style.color = theme.textColor || '';
                                },

                                clearAll: function () {
                                    Object.keys(mathTimers).forEach(function (id) { clearTimeout(mathTimers[id]); });
                                    mathTimers = {};
                                    state.messages = {};
                                    container.innerHTML = '<div class="spacer"></div>';
                                },

                                scrollToBottom: function () {
                                    container.scrollTop = container.scrollHeight;
                                }
                            };
                        })();
                    </script>
                </body>
                </html>
                """;
    }

    private static void copyKatexFonts(Path fontsDirectory) {
        try {
            Files.createDirectories(fontsDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create KaTeX fonts directory", e);
        }

        String[] fontFiles = {
                "KaTeX_AMS-Regular.woff2",
                "KaTeX_Caligraphic-Bold.woff2",
                "KaTeX_Caligraphic-Regular.woff2",
                "KaTeX_Fraktur-Bold.woff2",
                "KaTeX_Fraktur-Regular.woff2",
                "KaTeX_Main-Bold.woff2",
                "KaTeX_Main-BoldItalic.woff2",
                "KaTeX_Main-Italic.woff2",
                "KaTeX_Main-Regular.woff2",
                "KaTeX_Math-BoldItalic.woff2",
                "KaTeX_Math-Italic.woff2",
                "KaTeX_SansSerif-Bold.woff2",
                "KaTeX_SansSerif-Italic.woff2",
                "KaTeX_SansSerif-Regular.woff2",
                "KaTeX_Script-Regular.woff2",
                "KaTeX_Size1-Regular.woff2",
                "KaTeX_Size2-Regular.woff2",
                "KaTeX_Size3-Regular.woff2",
                "KaTeX_Size4-Regular.woff2",
                "KaTeX_Typewriter-Regular.woff2"
        };

        for (String fontFile : fontFiles) {
            copyResource(
                    "META-INF/resources/webjars/katex/0.16.11/dist/fonts/" + fontFile,
                    fontsDirectory.resolve(fontFile)
            );
        }
    }

    private static void copyResource(String resourcePath, Path destinationFile) {
        try (InputStream stream = JcefChatPage.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing renderer resource: " + resourcePath);
            }
            Files.copy(stream, destinationFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to copy renderer resource: " + resourcePath, e);
        }
    }
}
