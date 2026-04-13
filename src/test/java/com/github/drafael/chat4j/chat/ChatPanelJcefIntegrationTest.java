package com.github.drafael.chat4j.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.drafael.chat4j.chat.TestReflection.readField;
import static org.assertj.core.api.Assertions.assertThat;

class ChatPanelJcefIntegrationTest {

    private ChatPanel subject;

    @BeforeEach
    void setUp() {
        subject = new ChatPanel();
    }

    @Test
    @DisplayName("Markdown render options rerender push updates JCEF view state")
    void setMarkdownRenderOptions_whenRerenderEnabled_updatesStateAndPushesPendingMathOptions() throws Exception {
        var options = MarkdownRenderOptions.latexDisabled();

        subject.setMarkdownRenderOptions(options, true);

        assertThat(subject.getMarkdownRenderOptions()).isEqualTo(options);
        assertThat(readPendingMathOptionsJson(subject))
                .isEqualTo("{\"latexEnabled\":false,\"singleDollarEnabled\":false,\"bracketDelimitersEnabled\":false}");
    }

    @Test
    @DisplayName("Markdown render options with rerender disabled keep JCEF math payload untouched")
    void setMarkdownRenderOptions_whenRerenderDisabled_updatesStateWithoutPushingMathOptions() throws Exception {
        var options = new MarkdownRenderOptions(false, true, true);

        subject.setMarkdownRenderOptions(options, false);

        assertThat(subject.getMarkdownRenderOptions()).isEqualTo(options);
        assertThat(readPendingMathOptionsJson(subject)).isNull();
    }

    @Test
    @DisplayName("Setting identical markdown render options does not trigger view update")
    void setMarkdownRenderOptions_whenValueUnchanged_doesNotPushMathOptions() throws Exception {
        var current = subject.getMarkdownRenderOptions();

        subject.setMarkdownRenderOptions(current, true);

        assertThat(subject.getMarkdownRenderOptions()).isEqualTo(current);
        assertThat(readPendingMathOptionsJson(subject)).isNull();
    }

    private static String readPendingMathOptionsJson(ChatPanel chatPanel) throws Exception {
        var chatView = readField(chatPanel, "chatView");
        return (String) readField(chatView, "pendingMathOptionsJson");
    }
}
