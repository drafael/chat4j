package com.github.drafael.chat4j.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FindingCardPanelTest {

    @Test
    @DisplayName("Finding card renders severity title and file reference")
    void constructor_whenFindingProvided_rendersContent() {
        var finding = new FindingCardPanel.Finding(
                "P1",
                "Symlinks bypass root confinement",
                "resolvePath normalizes lexical paths but never resolves real paths.",
                "LocalToolRuntime.java:258-269"
        );

        var subject = new FindingCardPanel(finding);

        assertThat(subject.severity()).isEqualTo("P1");
        assertThat(subject.title()).isEqualTo("Symlinks bypass root confinement");
        assertThat(findButtons(subject).stream().map(JButton::getText)).contains(
                "LocalToolRuntime.java:258-269",
                "Dismiss",
                "Open file",
                "Ask follow-up",
                "Apply fix"
        );
    }

    @Test
    @DisplayName("Finding card default dismiss removes card from parent")
    void dismissButton_whenDefaultAction_removesCardFromParent() {
        var parent = new javax.swing.JPanel();
        var subject = new FindingCardPanel(new FindingCardPanel.Finding("P2", "Title", "Body", "File.java:1"));
        parent.add(subject);

        clickButton(subject, "Dismiss");

        assertThat(parent.getComponents()).doesNotContain(subject);
    }

    @Test
    @DisplayName("Finding card action buttons dispatch callbacks")
    void actionButtons_whenClicked_dispatchCallbacks() {
        var subject = new FindingCardPanel(new FindingCardPanel.Finding("P2", "Title", "Body", "File.java:1"));
        var dismissed = new AtomicInteger();
        var opened = new AtomicInteger();
        var followUp = new AtomicInteger();
        var applied = new AtomicInteger();

        subject.setOnDismiss(dismissed::incrementAndGet);
        subject.setOnOpenFile(opened::incrementAndGet);
        subject.setOnAskFollowUp(followUp::incrementAndGet);
        subject.setOnApplyFix(applied::incrementAndGet);

        clickButton(subject, "Dismiss");
        clickButton(subject, "Open file");
        clickButton(subject, "Ask follow-up");
        clickButton(subject, "Apply fix");

        assertThat(dismissed).hasValue(1);
        assertThat(opened).hasValue(1);
        assertThat(followUp).hasValue(1);
        assertThat(applied).hasValue(1);
    }

    private void clickButton(Container root, String text) {
        JButton button = findButtons(root).stream()
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst()
                .orElseThrow();
        button.doClick();
    }

    private List<JButton> findButtons(Container root) {
        java.util.ArrayList<JButton> matches = new java.util.ArrayList<>();
        collectButtons(root, matches);
        return matches;
    }

    private void collectButtons(Container root, List<JButton> matches) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button) {
                matches.add(button);
            }
            if (component instanceof Container child) {
                collectButtons(child, matches);
            }
        }
    }
}
