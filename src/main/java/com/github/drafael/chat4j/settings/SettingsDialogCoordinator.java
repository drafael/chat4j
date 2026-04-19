package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.util.SingleInstanceWindowTracker;
import org.apache.commons.lang3.Validate;

import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class SettingsDialogCoordinator {

    private final SingleInstanceWindowTracker<DialogHandle> tracker = new SingleInstanceWindowTracker<>();

    public void open(DialogFactory dialogFactory, Runnable onDialogClosed) {
        Validate.notNull(dialogFactory, "dialogFactory must not be null");
        Validate.notNull(onDialogClosed, "onDialogClosed must not be null");

        DialogHandle existingDialog = tracker.get();
        if (existingDialog != null) {
            if (existingDialog.isDisplayable() && existingDialog.isVisible()) {
                existingDialog.toFront();
                existingDialog.requestFocus();
                return;
            }
            if (existingDialog.isDisplayable()) {
                existingDialog.setVisible(true);
                return;
            }
            tracker.clear();
        }

        DialogHandle dialog = dialogFactory.create();
        tracker.set(dialog);
        dialog.onClosed(() -> {
            tracker.clear();
            onDialogClosed.run();
        });
        dialog.setVisible(true);
    }

    @FunctionalInterface
    public interface DialogFactory {
        DialogHandle create();
    }

    public interface DialogHandle {

        static DialogHandle forWindow(Window window) {
            Validate.notNull(window, "window must not be null");

            return new DialogHandle() {
                @Override
                public boolean isDisplayable() {
                    return window.isDisplayable();
                }

                @Override
                public boolean isVisible() {
                    return window.isVisible();
                }

                @Override
                public void toFront() {
                    window.toFront();
                }

                @Override
                public void requestFocus() {
                    window.requestFocus();
                }

                @Override
                public void setVisible(boolean visible) {
                    window.setVisible(visible);
                }

                @Override
                public void onClosed(Runnable callback) {
                    Validate.notNull(callback, "callback must not be null");
                    window.addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent e) {
                            callback.run();
                        }
                    });
                }
            };
        }

        boolean isDisplayable();

        boolean isVisible();

        void toFront();

        void requestFocus();

        void setVisible(boolean visible);

        void onClosed(Runnable callback);
    }
}
