package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

public final class TranscriptUpdateScripts {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TranscriptUpdateScripts() {
    }

    public static String scrollToBottom() {
        return "window.scrollTo(0, document.documentElement.scrollHeight || document.body.scrollHeight || 0);";
    }

    public static String jumpButtonChrome(boolean jumpButtonVisible) {
        return """
                (function() {
                  var jump = document.getElementById('chat4j-jump-bottom');
                  if (!jump) {
                    return;
                  }
                  jump.setAttribute('data-streaming', %s);
                  jump.classList.toggle('streaming', %s);
                  if (window.chat4jUpdateJumpButton) {
                    window.chat4jUpdateJumpButton();
                  } else {
                    jump.style.display = 'none';
                  }
                })();
                """.formatted(
                jsonString(jumpButtonVisible ? "true" : "false"),
                jumpButtonVisible ? "true" : "false"
        );
    }

    public static String transcriptHtmlUpdate(String entriesHtml, boolean jumpButtonVisible, boolean scrollToBottom) {
        return """
                (function() {
                  var transcript = document.querySelector('.transcript');
                  if (transcript) {
                    transcript.innerHTML = %s;
                  }
                  if (window.chat4jRenderEnhancements) {
                    window.chat4jRenderEnhancements(transcript);
                  }
                  if (window.chat4jInstallTranscriptActions) {
                    window.chat4jInstallTranscriptActions();
                  }
                  if (window.chat4jUpdateFadeOverlays) {
                    window.chat4jUpdateFadeOverlays();
                  }
                  var jump = document.getElementById('chat4j-jump-bottom');
                  if (jump) {
                    jump.setAttribute('data-streaming', %s);
                    jump.classList.toggle('streaming', %s);
                    if (window.chat4jUpdateJumpButton) {
                      window.chat4jUpdateJumpButton();
                    } else {
                      jump.style.display = 'none';
                    }
                  }
                  if (%s) {
                    %s
                  }
                })();
                """.formatted(
                jsonString(entriesHtml),
                jsonString(jumpButtonVisible ? "true" : "false"),
                jumpButtonVisible ? "true" : "false",
                scrollToBottom ? "true" : "false",
                scrollToBottom()
        );
    }

    private static String jsonString(String value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(StringUtils.defaultString(value));
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
