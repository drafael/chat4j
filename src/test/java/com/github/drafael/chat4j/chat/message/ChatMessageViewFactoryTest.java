package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.chat.content.MessageHtmlRenderer;
import com.github.drafael.chat4j.chat.content.MessageContentView;
import com.github.drafael.chat4j.provider.api.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageViewFactoryTest {

    @Test
    @DisplayName("Factory creates message views with injected content view provider")
    void create_whenProviderInjected_buildsMessageViewWithProviderContentView() {
        FakeMessageContentView contentView = new FakeMessageContentView();
        AtomicReference<Role> capturedRole = new AtomicReference<>();
        AtomicReference<IntSupplier> capturedWidthSupplier = new AtomicReference<>();
        ChatMessageViewFactory subject = new ChatMessageViewFactory((role, widthSupplier) -> {
            capturedRole.set(role);
            capturedWidthSupplier.set(widthSupplier);
            return contentView;
        }, new MessageHtmlRenderer());

        ChatMessageView view = subject.create(Role.USER);
        view.setMaxContentWidth(240);
        view.setText("hello");
        view.dispose();

        assertThat(view.component()).isInstanceOf(MessageBubble.class);
        assertThat(view.getRole()).isEqualTo(Role.USER);
        assertThat(capturedRole).hasValue(Role.USER);
        assertThat(capturedWidthSupplier.get().getAsInt()).isEqualTo(240);
        assertThat(contentView.invalidated).isTrue();
        assertThat(contentView.html).contains("hello");
        assertThat(view.isDisposed()).isTrue();
        assertThat(contentView.disposed).isTrue();
    }

    private static final class FakeMessageContentView implements MessageContentView {

        private final JPanel component = new JPanel();
        private String html = "";
        private boolean invalidated;
        private boolean disposed;

        @Override
        public JComponent component() {
            return component;
        }

        @Override
        public void setHtml(String html) {
            this.html = html;
        }

        @Override
        public String htmlSnapshot() {
            return html;
        }

        @Override
        public String textSnapshot() {
            return html;
        }

        @Override
        public void setContextMenu(JPopupMenu popupMenu) {
        }

        @Override
        public void installKeyBinding(KeyStroke keyStroke, String actionName, Action action) {
        }

        @Override
        public void selectAll() {
        }

        @Override
        public boolean hasSelection() {
            return false;
        }

        @Override
        public void copySelection() {
        }

        @Override
        public void requestContentFocus() {
        }

        @Override
        public void invalidateLayout() {
            invalidated = true;
        }

        @Override
        public void dispose() {
            disposed = true;
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }
}
