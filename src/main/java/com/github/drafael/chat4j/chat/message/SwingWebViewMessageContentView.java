package com.github.drafael.chat4j.chat.message;

import ca.weblite.webview.swing.WebViewComponent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.api.Role;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.IntSupplier;

@Slf4j
final class SwingWebViewMessageContentView implements MessageContentView {

    private static final int MIN_CONTENT_HEIGHT = 28;
    private static final int DEFAULT_CONTENT_HEIGHT = 96;
    private static final int HEIGHT_PADDING = 2;
    private static final String FALLBACK_CARD = "fallback";
    private static final String WEB_VIEW_CARD = "webview";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebViewComponent webView;
    private final JEditorPaneMessageContentView fallbackView;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel container;
    private final IntSupplier maxContentWidthSupplier;
    private String html = "";
    private String text = "";
    private int contentHeight = DEFAULT_CONTENT_HEIGHT;
    private boolean hasSelection;
    private boolean webViewVisible;
    private boolean disposed;

    SwingWebViewMessageContentView(Role role, IntSupplier maxContentWidthSupplier) {
        this.maxContentWidthSupplier = maxContentWidthSupplier;
        fallbackView = new JEditorPaneMessageContentView(role, maxContentWidthSupplier);
        webView = WebViewComponent.create();
        webView.setOpaque(false);
        configureJavaScriptBridge();

        container = new JPanel(cardLayout) {
            @Override
            public Dimension getPreferredSize() {
                if (!webViewVisible) {
                    return fallbackView.component().getPreferredSize();
                }

                int maxContentWidth = normalizedMaxContentWidth();
                return new Dimension(maxContentWidth, Math.max(contentHeight, fallbackPreferredHeight()));
            }

            @Override
            public Dimension getMaximumSize() {
                Dimension preferred = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, preferred.height);
            }

            @Override
            public Dimension getMinimumSize() {
                return new Dimension(0, MIN_CONTENT_HEIGHT);
            }
        };
        container.setOpaque(false);
        container.add(fallbackView.component(), FALLBACK_CARD);
        container.add(webView, WEB_VIEW_CARD);
        showFallbackView();
    }

    @Override
    public JComponent component() {
        return container;
    }

    @Override
    public void setHtml(String html) {
        this.html = StringUtils.defaultString(html);
        text = Jsoup.parse(this.html).text();
        hasSelection = false;
        fallbackView.setHtml(this.html);
        showFallbackView();
        contentHeight = Math.max(MIN_CONTENT_HEIGHT, contentHeight);
        webView.setUrl(toDataUrl(this.html));
        scheduleHeightRefresh();
    }

    @Override
    public String htmlSnapshot() {
        return html;
    }

    @Override
    public String textSnapshot() {
        return text;
    }

    @Override
    public void setContextMenu(JPopupMenu popupMenu) {
        container.setComponentPopupMenu(popupMenu);
        fallbackView.setContextMenu(popupMenu);
        webView.setComponentPopupMenu(popupMenu);
    }

    @Override
    public void installKeyBinding(KeyStroke keyStroke, String actionName, Action action) {
        container.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(keyStroke, actionName);
        container.getActionMap().put(actionName, action);
        fallbackView.installKeyBinding(keyStroke, actionName, action);
        webView.getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, actionName);
        webView.getActionMap().put(actionName, action);
    }

    @Override
    public void selectAll() {
        if (!webViewVisible) {
            fallbackView.selectAll();
            return;
        }

        webView.eval("window.getSelection().selectAllChildren(document.body);");
        hasSelection = true;
    }

    @Override
    public boolean hasSelection() {
        return webViewVisible ? hasSelection : fallbackView.hasSelection();
    }

    @Override
    public void copySelection() {
        if (!webViewVisible) {
            fallbackView.copySelection();
            return;
        }

        webView.evalAsync("return window.getSelection().toString();")
                .thenAccept(raw -> copyToClipboard(unwrapCallbackArg(raw)))
                .exceptionally(t -> {
                    log.debug("Failed to copy SwingWebView selection", t);
                    return null;
                });
    }

    @Override
    public void requestContentFocus() {
        if (!webViewVisible) {
            fallbackView.requestContentFocus();
            return;
        }
        webView.requestFocusInWindow();
    }

    @Override
    public void invalidateLayout() {
        fallbackView.invalidateLayout();
        container.invalidate();
        webView.invalidate();
        scheduleHeightRefresh();
    }

    @Override
    public void dispose() {
        disposed = true;
        container.setComponentPopupMenu(null);
        fallbackView.dispose();
        webView.setComponentPopupMenu(null);
        webView.dispose();
    }

    @Override
    public boolean isDisposed() {
        return disposed;
    }

    private void configureJavaScriptBridge() {
        webView.addOnBeforeLoad(bridgeScript());
        webView.addJavascriptCallback("chat4jOpenExternalLink", raw -> {
            String link = unwrapCallbackArg(raw);
            if (StringUtils.isBlank(link)) {
                return;
            }
            ExternalLinkSupport.openExternalLink(link);
        });
        webView.addJavascriptCallback("chat4jSelectionChanged", raw -> hasSelection = Boolean.parseBoolean(unwrapCallbackArg(raw)));
        webView.addJavascriptCallback("chat4jContentHeight", raw -> updateContentHeight(unwrapCallbackArg(raw)));
    }

    private String bridgeScript() {
        return """
                (function () {
                    function firstAnchor(node) {
                        if (!node) {
                            return null;
                        }
                        if (node.closest) {
                            return node.closest('a[href]');
                        }
                        while (node && node.tagName) {
                            if (String(node.tagName).toLowerCase() === 'a' && node.href) {
                                return node;
                            }
                            node = node.parentNode;
                        }
                        return null;
                    }
                    function reportHeight() {
                        var body = document.body;
                        if (!body) {
                            return;
                        }
                        var height = 0;
                        Array.prototype.forEach.call(body.children || [], function (child) {
                            var rect = child.getBoundingClientRect && child.getBoundingClientRect();
                            if (rect) {
                                height = Math.max(height, rect.bottom);
                            }
                        });
                        if (height <= 0) {
                            var bodyRect = body.getBoundingClientRect && body.getBoundingClientRect();
                            height = bodyRect ? bodyRect.height : 0;
                        }
                        height = Math.ceil(height);
                        if (window.chat4jContentHeight) {
                            window.chat4jContentHeight(String(height));
                        }
                    }
                    document.addEventListener('click', function (event) {
                        var anchor = firstAnchor(event.target);
                        if (!anchor) {
                            return;
                        }
                        event.preventDefault();
                        event.stopPropagation();
                        if (window.chat4jOpenExternalLink) {
                            window.chat4jOpenExternalLink(anchor.href);
                        }
                    }, true);
                    document.addEventListener('selectionchange', function () {
                        if (window.chat4jSelectionChanged) {
                            var selection = window.getSelection && window.getSelection();
                            window.chat4jSelectionChanged(String(!!selection && !selection.isCollapsed));
                        }
                    });
                    document.addEventListener('DOMContentLoaded', function () {
                        reportHeight();
                        setTimeout(reportHeight, 50);
                        setTimeout(reportHeight, 250);
                        if (window.ResizeObserver && document.body) {
                            new ResizeObserver(reportHeight).observe(document.body);
                        }
                    });
                    window.addEventListener('load', reportHeight);
                    window.addEventListener('resize', reportHeight);
                })();
                """;
    }

    private void scheduleHeightRefresh() {
        SwingUtilities.invokeLater(() -> webView.eval("setTimeout(function(){ var body = document.body; if (!body || !window.chat4jContentHeight) { return; } var height = 0; Array.prototype.forEach.call(body.children || [], function(child) { var rect = child.getBoundingClientRect && child.getBoundingClientRect(); if (rect) { height = Math.max(height, rect.bottom); } }); if (height <= 0) { var bodyRect = body.getBoundingClientRect && body.getBoundingClientRect(); height = bodyRect ? bodyRect.height : 0; } window.chat4jContentHeight(String(Math.ceil(height))); }, 0);"));
    }

    private void updateContentHeight(String rawHeight) {
        try {
            int measuredHeight = Integer.parseInt(StringUtils.defaultString(rawHeight).trim());
            if (measuredHeight <= 0) {
                return;
            }

            int resolvedHeight = Math.max(fallbackPreferredHeight(), measuredHeight + HEIGHT_PADDING);
            if (resolvedHeight != contentHeight) {
                contentHeight = resolvedHeight;
            }
            showWebView();
        } catch (NumberFormatException e) {
            log.debug("Ignoring invalid SwingWebView content height: {}", rawHeight);
        }
    }

    private void showFallbackView() {
        if (!webViewVisible) {
            return;
        }
        webViewVisible = false;
        cardLayout.show(container, FALLBACK_CARD);
        container.revalidate();
        container.repaint();
    }

    private void showWebView() {
        if (!webViewVisible) {
            webViewVisible = true;
            cardLayout.show(container, WEB_VIEW_CARD);
        }
        container.revalidate();
        container.repaint();
    }

    private int fallbackPreferredHeight() {
        return Math.max(MIN_CONTENT_HEIGHT, fallbackView.component().getPreferredSize().height + HEIGHT_PADDING);
    }

    private int normalizedMaxContentWidth() {
        int maxContentWidth = maxContentWidthSupplier.getAsInt();
        if (maxContentWidth <= 0 || maxContentWidth == Integer.MAX_VALUE) {
            return Math.max(1, container.getParent() != null ? container.getParent().getWidth() : 1);
        }
        return maxContentWidth;
    }

    private String toDataUrl(String html) {
        String encoded = Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
        return "data:text/html;charset=UTF-8;base64,%s".formatted(encoded);
    }

    private void copyToClipboard(String selectedText) {
        if (StringUtils.isBlank(selectedText)) {
            return;
        }
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(selectedText), null);
    }

    private String unwrapCallbackArg(String raw) {
        String value = StringUtils.defaultString(raw).trim();
        if (value.isEmpty()) {
            return "";
        }

        try {
            JsonNode node = OBJECT_MAPPER.readTree(value);
            if (node.has("args") && node.get("args").isArray() && !node.get("args").isEmpty()) {
                return node.get("args").get(0).asText("");
            }
            if (node.isArray() && !node.isEmpty()) {
                return node.get(0).asText("");
            }
            if (node.isTextual() || node.isNumber() || node.isBoolean()) {
                return node.asText("");
            }
        } catch (Exception ignored) {
            // Fall back to legacy raw string handling.
        }

        return StringUtils.unwrap(value, '"');
    }
}
