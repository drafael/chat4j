package com.github.drafael.chat4j.chat.conversation.webview.jcef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JcefBrowserViewTest {

    @Test
    @DisplayName("Programmatic internal Chat4J document navigation is allowed")
    void navigationDecision_whenProgrammaticInternalUrl_returnsAllow() {
        assertThat(JcefBrowserView.navigationDecision("https://chat4j.local/transcript/page.html", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.ALLOW);
    }

    @Test
    @DisplayName("User-gesture internal Chat4J document navigation is blocked")
    void navigationDecision_whenUserGestureInternalUrl_returnsBlock() {
        assertThat(JcefBrowserView.navigationDecision("https://chat4j.local/transcript/page.html", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Safe user links open externally")
    void navigationDecision_whenSafeUserLink_returnsOpenExternal() {
        assertThat(JcefBrowserView.navigationDecision("https://example.com/path", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.OPEN_EXTERNAL);
    }

    @Test
    @DisplayName("Safe non-user external navigation is blocked")
    void navigationDecision_whenSafeExternalNavigationWithoutUserGesture_returnsBlock() {
        assertThat(JcefBrowserView.navigationDecision("https://example.com/path", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Unsafe user links are blocked")
    void navigationDecision_whenUnsafeUserLink_returnsBlock() {
        assertThat(JcefBrowserView.navigationDecision("javascript:alert(1)", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
        assertThat(JcefBrowserView.navigationDecision("file:///Users/example/secrets.txt", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
        assertThat(JcefBrowserView.navigationDecision("data:text/html;base64,PHNjcmlwdD4=", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Safe mail links open externally only for user gestures")
    void navigationDecision_whenMailtoLink_returnsExternalPolicy() {
        assertThat(JcefBrowserView.navigationDecision("mailto:hello@example.com", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.OPEN_EXTERNAL);
        assertThat(JcefBrowserView.navigationDecision("mailto:hello@example.com", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Programmatic internal asset navigation is allowed")
    void navigationDecision_whenProgrammaticInternalAssetUrl_returnsAllow() {
        assertThat(JcefBrowserView.navigationDecision("https://chat4j.local/assets/mermaid/mermaid.min.js", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.ALLOW);
    }

    @Test
    @DisplayName("Internal URL matching ignores scheme and host case")
    void navigationDecision_whenInternalUrlHasDifferentCase_returnsInternalPolicy() {
        assertThat(JcefBrowserView.navigationDecision("HTTPS://CHAT4J.LOCAL/transcript/page.html", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.ALLOW);
        assertThat(JcefBrowserView.navigationDecision("HTTPS://CHAT4J.LOCAL/transcript/page.html", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Lookalike internal hosts are not treated as internal")
    void navigationDecision_whenInternalHostLookalike_returnsExternalPolicy() {
        assertThat(JcefBrowserView.navigationDecision("https://chat4j.local.evil.example/transcript/page.html", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.OPEN_EXTERNAL);
        assertThat(JcefBrowserView.navigationDecision("http://chat4j.local/transcript/page.html", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Blank malformed and relative URLs are blocked")
    void navigationDecision_whenUrlIsBlankMalformedOrRelative_returnsBlock() {
        assertThat(JcefBrowserView.navigationDecision("", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
        assertThat(JcefBrowserView.navigationDecision("not a url", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
        assertThat(JcefBrowserView.navigationDecision("/transcript/page.html", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Unsafe non-user navigation is blocked")
    void navigationDecision_whenUnsafeNavigationWithoutUserGesture_returnsBlock() {
        assertThat(JcefBrowserView.navigationDecision("data:text/html;base64,PHNjcmlwdD4=", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }
}
