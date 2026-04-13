package com.github.drafael.chat4j.chat;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

final class JcefChatView extends JPanel {

    private CefClient client;
    private CefBrowser browser;
    private boolean bridgeReady;
    private Timer retryTimer;
    private int retryAttempts;

    private String pendingThemeJson;
    private String pendingRenderMode;
    private String pendingMathOptionsJson;
    private final List<String> pendingCommands = new ArrayList<>();

    JcefChatView() {
        setLayout(new BorderLayout());
        setOpaque(false);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        initializeBrowserIfNeeded();
    }

    @Override
    public void removeNotify() {
        disposeBrowser();
        super.removeNotify();
    }

    private void initializeBrowserIfNeeded() {
        if (browser != null || GraphicsEnvironment.isHeadless() || !isDisplayable()) {
            return;
        }

        CefApp cefApp = JcefRuntimeManager.getAppOrThrow();
        client = cefApp.createClient();

        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public boolean onConsoleMessage(
                    CefBrowser cefBrowser,
                    org.cef.CefSettings.LogSeverity level,
                    String message,
                    String source,
                    int line
            ) {
                if (message != null && message.contains("chat4j-renderer")) {
                    System.err.println("[chat4j:jcef] " + message);
                }
                return false;
            }
        });

        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                SwingUtilities.invokeLater(() -> {
                    bridgeReady = true;
                    stopRetryTimer();
                    flushPendingState();
                });
            }
        });

        client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override
            public boolean onBeforeBrowse(
                    CefBrowser cefBrowser,
                    CefFrame frame,
                    CefRequest request,
                    boolean userGesture,
                    boolean isRedirect
            ) {
                String url = request.getURL();
                if (url == null) {
                    return false;
                }

                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                    openExternalLink(url);
                    return true;
                }

                return false;
            }
        });

        browser = client.createBrowser(JcefChatPage.url(), false, false);
        browser.createImmediately();
        add(browser.getUIComponent(), BorderLayout.CENTER);
        startRetryTimer();
        revalidate();
        repaint();
    }

    void addMessage(String id, String role, String text, boolean streaming) {
        String optionsJson = "{\"streaming\":" + streaming + "}";
        executeRendererCall("addMessage(%s,%s,%s,%s)",
                jsonString(id), jsonString(role), jsonString(text), optionsJson);
    }

    void updateMessage(String id, String text) {
        executeRendererCall("updateMessage(%s,%s)", jsonString(id), jsonString(text));
    }

    void finishMessage(String id) {
        executeRendererCall("finishMessage(%s)", jsonString(id));
    }

    void setRenderMode(AssistantRenderMode mode) {
        String modeValue = mode != null ? mode.settingValue() : "preview";
        pendingRenderMode = modeValue;
        executeRendererCall("setRenderMode(%s)", jsonString(modeValue));
    }

    void setMathOptions(MarkdownRenderOptions options) {
        String json = mathOptionsToJson(options);
        pendingMathOptionsJson = json;
        executeRendererCall("setMathOptions(%s)", json);
    }

    void setTheme(ChatTheme theme) {
        String json = themeToJson(theme);
        pendingThemeJson = json;
        executeRendererCall("setTheme(%s)", json);
    }

    void clearAll() {
        pendingCommands.clear();
        executeRendererCall("clearAll()");
    }

    void scrollToBottom() {
        executeRendererCall("scrollToBottom()");
    }

    private void executeRendererCall(String callTemplate, Object... args) {
        String call = "window.chat4jRenderer." + callTemplate.formatted(args);

        if (!bridgeReady || browser == null) {
            pendingCommands.add(call);
            return;
        }

        executeScript(call);
    }

    private void executeScript(String call) {
        if (browser == null) {
            return;
        }

        String script = "(function(){if(window.chat4jRenderer){" + call + ";}})();";
        CefFrame mainFrame = browser.getMainFrame();
        if (mainFrame != null) {
            mainFrame.executeJavaScript(script, browser.getURL(), 0);
        } else {
            browser.executeJavaScript(script, browser.getURL(), 0);
        }
    }

    private void flushPendingState() {
        if (pendingThemeJson != null) {
            executeScript("window.chat4jRenderer.setTheme(" + pendingThemeJson + ")");
        }
        if (pendingRenderMode != null) {
            executeScript("window.chat4jRenderer.setRenderMode(" + jsonString(pendingRenderMode) + ")");
        }
        if (pendingMathOptionsJson != null) {
            executeScript("window.chat4jRenderer.setMathOptions(" + pendingMathOptionsJson + ")");
        }

        List<String> queued = new ArrayList<>(pendingCommands);
        pendingCommands.clear();
        for (String call : queued) {
            executeScript(call);
        }

        scheduleBrowserRepaint();
    }

    void invalidateLayout() {
        scheduleBrowserRepaint();
    }

    private void scheduleBrowserRepaint() {
        if (browser == null) {
            return;
        }

        Component uiComponent = browser.getUIComponent();
        SwingUtilities.invokeLater(() -> {
            if (uiComponent.getWidth() > 0 && uiComponent.getHeight() > 0) {
                uiComponent.revalidate();
                uiComponent.repaint();
            }

            // Delayed second pass to catch cases where layout hasn't settled
            Timer repaintTimer = new Timer(100, e -> {
                uiComponent.revalidate();
                uiComponent.repaint();
            });
            repaintTimer.setRepeats(false);
            repaintTimer.start();
        });
    }

    private void openExternalLink(String url) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            return;
        }

        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            // ignore external open failures
        }
    }

    private void disposeBrowser() {
        stopRetryTimer();
        bridgeReady = false;
        pendingCommands.clear();

        if (browser != null) {
            try {
                browser.close(true);
            } catch (Exception e) {
                // ignore
            }
            browser = null;
        }

        if (client != null) {
            try {
                client.dispose();
            } catch (Exception e) {
                // ignore
            }
            client = null;
        }

        removeAll();
    }

    private void startRetryTimer() {
        stopRetryTimer();
        retryAttempts = 0;
        retryTimer = new Timer(250, event -> {
            retryAttempts++;
            if (bridgeReady || retryAttempts >= 20) {
                stopRetryTimer();
            }
        });
        retryTimer.setRepeats(true);
        retryTimer.start();
    }

    private void stopRetryTimer() {
        if (retryTimer != null) {
            retryTimer.stop();
            retryTimer = null;
        }
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }

        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
        return "\"" + escaped + "\"";
    }

    private static String mathOptionsToJson(MarkdownRenderOptions options) {
        if (options == null) {
            options = MarkdownRenderOptions.defaults();
        }

        return "{\"latexEnabled\":" + options.latexEnabled()
                + ",\"singleDollarEnabled\":" + options.singleDollarEnabled()
                + ",\"bracketDelimitersEnabled\":" + options.bracketDelimitersEnabled()
                + "}";
    }

    private static String themeToJson(ChatTheme theme) {
        if (theme == null) {
            return "{}";
        }

        return "{\"textColor\":" + jsonString(theme.textColor())
                + ",\"mutedTextColor\":" + jsonString(theme.mutedTextColor())
                + ",\"linkColor\":" + jsonString(theme.linkColor())
                + ",\"surfaceBg\":" + jsonString(theme.surfaceBg())
                + ",\"separatorColor\":" + jsonString(theme.separatorColor())
                + ",\"codeBg\":" + jsonString(theme.codeBg())
                + ",\"codeBorder\":" + jsonString(theme.codeBorder())
                + ",\"codeHeaderBg\":" + jsonString(theme.codeHeaderBg())
                + ",\"inlineCodeBg\":" + jsonString(theme.inlineCodeBg())
                + ",\"codeText\":" + jsonString(theme.codeText())
                + ",\"langColor\":" + jsonString(theme.langColor())
                + ",\"userBubbleBg\":" + jsonString(theme.userBubbleBg())
                + ",\"baseFontFamily\":" + jsonString(theme.baseFontFamily())
                + ",\"monoFontFamily\":" + jsonString(theme.monoFontFamily())
                + ",\"baseFontSize\":" + theme.baseFontSize()
                + ",\"monoFontSize\":" + theme.monoFontSize()
                + "}";
    }
}
