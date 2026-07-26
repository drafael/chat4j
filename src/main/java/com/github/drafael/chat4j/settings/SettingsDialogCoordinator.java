package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.util.SingleInstanceWindowTracker;
import lombok.NonNull;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class SettingsDialogCoordinator {

    private final SingleInstanceWindowTracker<DialogHandle> tracker = new SingleInstanceWindowTracker<>();

    public void open(@NonNull DialogFactory dialogFactory, @NonNull Runnable onDialogClosed) {

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
            if (tracker.get() == dialog) {
                tracker.clear();
            }
            onDialogClosed.run();
        });
        dialog.setVisible(true);
    }

    public void requestApplicationExit(long deadlineNanos, @NonNull Runnable whenNoDialog) {
        DialogHandle dialog = tracker.get();
        if (dialog != null && dialog.isDisplayable()) {
            dialog.requestApplicationExit(deadlineNanos);
            return;
        }
        if (dialog != null && tracker.get() == dialog) {
            tracker.clear();
        }
        whenNoDialog.run();
    }

    @FunctionalInterface
    public interface DialogFactory {
        DialogHandle create();
    }

    public interface DialogHandle {

        static DialogHandle forSettingsDialog(@NonNull SettingsDialog dialog) {

            return new DialogHandle() {
                @Override
                public boolean isDisplayable() {
                    return dialog.isDisplayable();
                }

                @Override
                public boolean isVisible() {
                    return dialog.isVisible();
                }

                @Override
                public void toFront() {
                    dialog.toFront();
                }

                @Override
                public void requestFocus() {
                    dialog.requestFocus();
                }

                @Override
                public void setVisible(boolean visible) {
                    dialog.setVisible(visible);
                }

                @Override
                public void onClosed(@NonNull Runnable callback) {
                    dialog.addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent e) {
                            callback.run();
                        }
                    });
                }

                @Override
                public void requestApplicationExit(long deadlineNanos) {
                    dialog.requestApplicationExit(deadlineNanos);
                }
            };
        }

        boolean isDisplayable();

        boolean isVisible();

        void toFront();

        void requestFocus();

        void setVisible(boolean visible);

        void onClosed(Runnable callback);

        void requestApplicationExit(long deadlineNanos);
    }
}
