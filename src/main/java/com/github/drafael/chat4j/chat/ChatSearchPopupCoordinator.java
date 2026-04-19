package com.github.drafael.chat4j.chat;

import org.apache.commons.lang3.Validate;

import java.awt.Component;

public class ChatSearchPopupCoordinator {

    private PopupHandle popup;

    public void toggle(Component relativeTo, PopupFactory popupFactory) {
        Validate.notNull(popupFactory, "popupFactory must not be null");

        if (popup == null || !popup.isDisplayable()) {
            popup = popupFactory.create();
        }

        if (popup.isVisible()) {
            popup.hide();
            return;
        }

        popup.show(relativeTo);
    }

    @FunctionalInterface
    public interface PopupFactory {
        PopupHandle create();
    }

    public interface PopupHandle {

        static PopupHandle forPopup(ChatSearchPopup popup) {
            Validate.notNull(popup, "popup must not be null");

            return new PopupHandle() {
                @Override
                public boolean isDisplayable() {
                    return popup.isDisplayable();
                }

                @Override
                public boolean isVisible() {
                    return popup.isVisible();
                }

                @Override
                public void show(Component relativeTo) {
                    popup.show(relativeTo);
                }

                @Override
                public void hide() {
                    popup.hidePopup();
                }
            };
        }

        boolean isDisplayable();

        boolean isVisible();

        void show(Component relativeTo);

        void hide();
    }
}
