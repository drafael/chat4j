package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.github.drafael.chat4j.json.JsonCodec;
import org.apache.commons.lang3.StringUtils;

public final class TranscriptUpdateScripts {

    private static final JsonCodec JSON_CODEC = JsonCodec.standard();

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

    public static String readAloudChrome(int messageIndex, boolean active) {
        return """
                (function() {
                  var buttons = document.querySelectorAll('button[data-action="read-aloud"][data-message-index="%d"]');
                  Array.prototype.forEach.call(buttons, function(button) {
                    button.setAttribute('data-read-aloud-active', %s);
                    button.setAttribute('title', %s);
                    var icon = button.querySelector('.icon');
                    if (icon) {
                      icon.classList.toggle('read-aloud', %s);
                      icon.classList.toggle('player-stop', %s);
                    }
                  });
                })();
                """.formatted(
                messageIndex,
                jsonString(active ? "true" : "false"),
                jsonString(active ? "Stop" : "Read aloud"),
                active ? "false" : "true",
                active ? "true" : "false"
        );
    }

    public static String transcriptHtmlUpdate(String entriesHtml, boolean jumpButtonVisible, boolean scrollToBottom) {
        return """
                (function() {
                  var transcript = document.querySelector('.transcript');
                  if (transcript) {
                    function directTypingRow(root) {
                      for (var child = root.firstElementChild; child; child = child.nextElementSibling) {
                        if (child.classList.contains('row') && child.classList.contains('typing')) {
                          return child;
                        }
                      }
                      return null;
                    }
                    var currentTyping = directTypingRow(transcript);
                    var template = document.createElement('template');
                    template.innerHTML = %s;
                    var nextTyping = directTypingRow(template.content);
                    var sameTypingSession = currentTyping && nextTyping
                        && currentTyping.getAttribute('data-stream-session-id')
                            === nextTyping.getAttribute('data-stream-session-id');
                    if (sameTypingSession) {
                      nextTyping.remove();
                      while (transcript.firstChild && transcript.firstChild !== currentTyping) {
                        transcript.removeChild(transcript.firstChild);
                      }
                      while (currentTyping.nextSibling) {
                        transcript.removeChild(currentTyping.nextSibling);
                      }
                      transcript.insertBefore(template.content, currentTyping);
                    } else {
                      transcript.innerHTML = '';
                      transcript.appendChild(template.content);
                    }
                  }
                  if (window.chat4jInstallTranscriptActions) {
                    window.chat4jInstallTranscriptActions();
                  } else if (window.chat4jRenderEnhancements) {
                    window.chat4jRenderEnhancements(transcript);
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
            return JSON_CODEC.writeString(StringUtils.defaultString(value));
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
