package com.github.drafael.chat4j.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSearchPopupCoordinatorTest {

    @Test
    @DisplayName("Toggle creates popup once and alternates show/hide")
    void toggle_whenCalledRepeatedly_reusesPopupAndAlternatesVisibility() {
        var subject = new ChatSearchPopupCoordinator();
        var createdPopups = new ArrayList<FakePopupHandle>();
        Component anchor = new JPanel();

        subject.toggle(anchor, () -> createPopup(createdPopups));
        subject.toggle(anchor, () -> createPopup(createdPopups));
        subject.toggle(anchor, () -> createPopup(createdPopups));

        assertThat(createdPopups).hasSize(1);
        FakePopupHandle popup = createdPopups.getFirst();
        assertThat(popup.showCalls.get()).isEqualTo(2);
        assertThat(popup.hideCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Toggle recreates popup when previous instance is not displayable")
    void toggle_whenTrackedPopupIsNotDisplayable_createsNewPopup() {
        var subject = new ChatSearchPopupCoordinator();
        var createdPopups = new ArrayList<FakePopupHandle>();

        subject.toggle(null, () -> createPopup(createdPopups));
        FakePopupHandle firstPopup = createdPopups.getFirst();
        firstPopup.displayable = false;

        subject.toggle(null, () -> createPopup(createdPopups));

        assertThat(createdPopups).hasSize(2);
        assertThat(createdPopups.get(1).showCalls.get()).isEqualTo(1);
    }

    private FakePopupHandle createPopup(List<FakePopupHandle> createdPopups) {
        FakePopupHandle popup = new FakePopupHandle();
        createdPopups.add(popup);
        return popup;
    }

    private static class FakePopupHandle implements ChatSearchPopupCoordinator.PopupHandle {

        private final AtomicInteger showCalls = new AtomicInteger();
        private final AtomicInteger hideCalls = new AtomicInteger();
        private boolean visible;
        private boolean displayable = true;

        @Override
        public boolean isDisplayable() {
            return displayable;
        }

        @Override
        public boolean isVisible() {
            return visible;
        }

        @Override
        public void show(Component relativeTo) {
            visible = true;
            showCalls.incrementAndGet();
        }

        @Override
        public void hide() {
            visible = false;
            hideCalls.incrementAndGet();
        }
    }
}
