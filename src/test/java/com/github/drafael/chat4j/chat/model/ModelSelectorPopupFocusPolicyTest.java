package com.github.drafael.chat4j.chat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.event.WindowEvent;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSelectorPopupFocusPolicyTest {

    @Test
    @DisplayName("Popup stays open when activation or focus moves between owner and popup")
    void shouldHideForWindowEvent_whenWindowTransferStaysWithinOwnerPopupPair_keepsPopup() {
        Object owner = new Object();
        Object popup = new Object();

        assertThat(ModelSelectorPopup.shouldHideForWindowEvent(
                WindowEvent.WINDOW_DEACTIVATED,
                owner,
                popup,
                owner,
                popup
        )).isFalse();
        assertThat(ModelSelectorPopup.shouldHideForWindowEvent(
                WindowEvent.WINDOW_LOST_FOCUS,
                popup,
                popup,
                owner,
                owner
        )).isFalse();
    }

    @Test
    @DisplayName("Popup hides when activation or focus leaves owner and popup")
    void shouldHideForWindowEvent_whenWindowTransferLeavesOwnerPopupPair_hidesPopup() {
        Object owner = new Object();
        Object popup = new Object();
        Object otherWindow = new Object();

        assertThat(ModelSelectorPopup.shouldHideForWindowEvent(
                WindowEvent.WINDOW_DEACTIVATED,
                owner,
                popup,
                owner,
                otherWindow
        )).isTrue();
        assertThat(ModelSelectorPopup.shouldHideForWindowEvent(
                WindowEvent.WINDOW_LOST_FOCUS,
                popup,
                popup,
                owner,
                otherWindow
        )).isTrue();
        assertThat(ModelSelectorPopup.shouldHideForWindowEvent(
                WindowEvent.WINDOW_LOST_FOCUS,
                owner,
                popup,
                owner,
                null
        )).isTrue();
    }

    @Test
    @DisplayName("Unrelated window events do not affect popup")
    void shouldHideForWindowEvent_whenUnrelatedWindowChangesFocus_ignoresEvent() {
        Object owner = new Object();
        Object popup = new Object();
        Object unrelatedSource = new Object();

        assertThat(ModelSelectorPopup.shouldHideForWindowEvent(
                WindowEvent.WINDOW_DEACTIVATED,
                unrelatedSource,
                popup,
                owner,
                null
        )).isFalse();
        assertThat(ModelSelectorPopup.shouldHideForWindowEvent(
                WindowEvent.WINDOW_LOST_FOCUS,
                unrelatedSource,
                popup,
                owner,
                null
        )).isFalse();
    }

    @Test
    @DisplayName("Popup without owner hides when focus leaves to no opposite window")
    void shouldHideForWindowEvent_whenPopupHasNoOwnerAndFocusLeaves_hidesPopup() {
        Object popup = new Object();

        assertThat(ModelSelectorPopup.shouldHideForWindowEvent(
                WindowEvent.WINDOW_LOST_FOCUS,
                popup,
                popup,
                null,
                null
        )).isTrue();
    }

    @Test
    @DisplayName("Non-focus window events do not hide popup")
    void shouldHideForWindowEvent_whenWindowEventIsNotFocusOrActivation_ignoresEvent() {
        Object owner = new Object();
        Object popup = new Object();

        assertThat(ModelSelectorPopup.shouldHideForWindowEvent(
                WindowEvent.WINDOW_OPENED,
                owner,
                popup,
                owner,
                null
        )).isFalse();
    }
}
