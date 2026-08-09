package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.KeyEvent;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileMenuFactoryTest {

    @Test
    @DisplayName("Create builds file menu with new chat item accelerator and callback")
    void create_whenCalled_buildsFileMenuWithNewChatItem() throws Exception {
        var subject = new FileMenuFactory();
        var newChatCalls = new AtomicInteger();

        var fileMenu = callOnEdt(() -> subject.create(KeyEvent.CTRL_DOWN_MASK, newChatCalls::incrementAndGet));

        assertThat(callOnEdt(fileMenu::getText)).isEqualTo("File");
        assertThat(callOnEdt(fileMenu::getItemCount)).isEqualTo(1);
        assertThat(callOnEdt(() -> fileMenu.getItem(0).getText())).isEqualTo("New Chat");
        assertThat(callOnEdt(() -> fileMenu.getItem(0).getAccelerator()))
                .isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK));

        callOnEdt(() -> {
            fileMenu.getItem(0).doClick();
            return null;
        });
        assertThat(newChatCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Export item refreshes enablement and invokes the shared callback")
    void addExportPdfItem_whenMenuOpens_refreshesEnablementAndInvokesCallback() throws Exception {
        var subject = new FileMenuFactory();
        var exportCalls = new AtomicInteger();
        var enabled = new AtomicReference<>(false);
        var fileMenu = callOnEdt(() -> subject.create(KeyEvent.CTRL_DOWN_MASK, () -> {
        }));
        var exportItem = callOnEdt(() -> subject.addExportPdfItem(fileMenu, exportCalls::incrementAndGet, enabled::get));

        assertThat(callOnEdt(exportItem::isEnabled)).isFalse();

        enabled.set(true);
        callOnEdt(() -> {
            fileMenu.getMenuListeners()[0].menuSelected(null);
            exportItem.doClick();
            return null;
        });

        assertThat(callOnEdt(exportItem::isEnabled)).isTrue();
        assertThat(exportCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Create validates callback")
    void create_whenCallbackMissing_throwsException() {
        var subject = new FileMenuFactory();

        assertThatThrownBy(() -> subject.create(KeyEvent.CTRL_DOWN_MASK, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("onNewChat");
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }
}
