package com.github.drafael.chat4j.chat.composer;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.drafael.chat4j.util.Fonts;
import java.awt.*;
import java.awt.event.ActionListener;
import java.net.URL;
import java.time.Duration;
import javax.swing.*;

public class InputRecordingPanel extends JPanel {

    private final RecordingWaveformTimeline waveform = new RecordingWaveformTimeline();
    private static final int STOP_ICON_SIZE = 16;

    private final JLabel timerLabel = new JLabel("0:00");
    private final JLabel statusLabel = new JLabel("Recording");
    private final JButton stopButton = new JButton();
    private final ActionListener stopListener;
    private final ActionListener cancelListener;
    private boolean transcribing;
    private long startedAtMillis;
    private final Timer timer;

    public InputRecordingPanel(ActionListener stopListener, ActionListener cancelListener) {
        super(new BorderLayout(8, 0));
        this.stopListener = stopListener;
        this.cancelListener = cancelListener;
        setOpaque(false);
        setVisible(false);
        setAlignmentX(LEFT_ALIGNMENT);

        Fonts.apply(timerLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        timerLabel.getAccessibleContext().setAccessibleName("Recording timer");
        timerLabel.setToolTipText("Recording timer");
        Fonts.apply(statusLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        statusLabel.getAccessibleContext().setAccessibleName("Speech to Text status");

        stopButton.putClientProperty("JButton.buttonType", "toolBarButton");
        stopButton.putClientProperty(FlatClientProperties.STYLE, "focusWidth:1;innerFocusWidth:1;arc:8");
        stopButton.setPreferredSize(new Dimension(28, 28));
        stopButton.setIcon(stopIcon());
        stopButton.addActionListener(e -> activeControlListener().actionPerformed(e));
        applyRecordingControlText();

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(statusLabel);
        right.add(timerLabel);
        right.add(stopButton);

        add(waveform, BorderLayout.CENTER);
        add(right, BorderLayout.EAST);
        timer = new Timer(250, e -> updateTimer());
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (stopButton != null) {
            stopButton.setIcon(stopIcon());
        }
    }

    public void startRecording() {
        transcribing = false;
        startedAtMillis = System.currentTimeMillis();
        timerLabel.setText("0:00");
        statusLabel.setText("Recording");
        applyRecordingControlText();
        waveform.clear();
        setVisible(true);
        timer.start();
    }

    public void setTranscribing() {
        transcribing = true;
        timer.stop();
        statusLabel.setText("Transcribing...");
        applyTranscribingControlText();
        setVisible(true);
    }

    public void stop() {
        transcribing = false;
        timer.stop();
        setVisible(false);
        waveform.clear();
    }

    public void addLevel(double rms, double peak) {
        waveform.addLevel(rms, peak);
    }

    private ActionListener activeControlListener() {
        return transcribing ? cancelListener : stopListener;
    }

    private void applyRecordingControlText() {
        stopButton.setToolTipText("Stop recording and transcribe");
        stopButton.getAccessibleContext().setAccessibleName("Stop recording and transcribe");
    }

    private void applyTranscribingControlText() {
        stopButton.setToolTipText("Cancel transcription");
        stopButton.getAccessibleContext().setAccessibleName("Cancel transcription");
    }

    private void updateTimer() {
        Duration elapsed = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - startedAtMillis));
        long minutes = elapsed.toMinutes();
        long seconds = elapsed.minusMinutes(minutes).toSeconds();
        timerLabel.setText("%d:%02d".formatted(minutes, seconds));
    }

    private Icon stopIcon() {
        URL iconUrl = InputRecordingPanel.class.getResource("/icons/chat/square.svg");
        if (iconUrl == null) {
            return UIManager.getIcon("OptionPane.errorIcon");
        }
        FlatSVGIcon icon = new FlatSVGIcon(iconUrl).derive(STOP_ICON_SIZE, STOP_ICON_SIZE);
        Color tint = UIManager.getColor("Label.foreground");
        if (tint == null) {
            tint = new Color(90, 90, 90);
        }
        Color finalTint = tint;
        icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> new Color(
                finalTint.getRed(),
                finalTint.getGreen(),
                finalTint.getBlue(),
                color.getAlpha()
        )));
        return icon;
    }
}
