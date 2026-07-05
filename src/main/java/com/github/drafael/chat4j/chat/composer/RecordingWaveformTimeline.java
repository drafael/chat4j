package com.github.drafael.chat4j.chat.composer;

import java.awt.*;
import java.util.Arrays;
import javax.swing.*;
import org.apache.commons.lang3.ObjectUtils;

public class RecordingWaveformTimeline extends JComponent {

    private static final int SAMPLE_COUNT = 128;
    private static final double IDLE_AMPLITUDE = 0.04;
    private static final double MIN_ACTIVE_AMPLITUDE = 0.18;
    private static final double TARGET_PEAK_AMPLITUDE = 0.72;
    private static final double RMS_GAIN = 14.0;
    private static final double PEAK_GAIN = 5.5;
    private static final double RESPONSE_CURVE = 0.42;
    private static final long FRAME_INTERVAL_NANOS = 33_000_000L;
    private final double[] samples = new double[SAMPLE_COUNT];
    private double lastAmplitude = IDLE_AMPLITUDE;
    private long lastLevelNanos;

    public RecordingWaveformTimeline() {
        Arrays.fill(samples, IDLE_AMPLITUDE);
        setPreferredSize(new Dimension(220, 28));
        setMinimumSize(new Dimension(120, 24));
        setName("Recording audio level");
        setToolTipText("Recording audio level");
    }

    public void addLevel(double rms, double peak) {
        double amplitude = displayAmplitude(rms, peak);
        long now = System.nanoTime();
        int frames = framesSinceLastLevel(now);
        for (int frame = 1; frame <= frames; frame++) {
            double progress = frame / (double) frames;
            appendSample(lastAmplitude + (amplitude - lastAmplitude) * progress);
        }
        lastAmplitude = amplitude;
        lastLevelNanos = now;
        repaint();
    }

    public void clear() {
        Arrays.fill(samples, IDLE_AMPLITUDE);
        lastAmplitude = IDLE_AMPLITUDE;
        lastLevelNanos = 0;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color accent = ObjectUtils.firstNonNull(
                    UIManager.getColor("Component.accentColor"),
                    UIManager.getColor("Label.foreground"),
                    new Color(80, 160, 220)
            );

            double[] displaySamples = visualSamples(smoothedSamples());
            int width = getWidth();
            int height = getHeight();
            int centerY = height / 2;
            double maxAmplitude = Math.max(2.0, (height - 4) / 2.0);
            double step = displaySamples.length <= 1 ? width : width / (double) (displaySamples.length - 1);
            float strokeWidth = (float) Math.max(2.2, Math.min(3.6, step * 0.36));

            g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < displaySamples.length; i++) {
                double sample = displaySamples[i];
                int sampleHeight = Math.max(3, (int) Math.round(sample * maxAmplitude * 2));
                int x = (int) Math.round(i * step);
                int y1 = centerY - sampleHeight / 2;
                int y2 = centerY + sampleHeight / 2;
                g.setColor(withAlpha(accent, sampleAlpha(sample)));
                g.drawLine(x, y1, x, y2);
            }
        } finally {
            g.dispose();
        }
    }

    private int framesSinceLastLevel(long now) {
        if (lastLevelNanos == 0) {
            return 1;
        }
        long elapsed = Math.max(FRAME_INTERVAL_NANOS, now - lastLevelNanos);
        return (int) Math.max(1, Math.min(8, Math.round(elapsed / (double) FRAME_INTERVAL_NANOS)));
    }

    private void appendSample(double sample) {
        System.arraycopy(samples, 1, samples, 0, samples.length - 1);
        samples[samples.length - 1] = sample;
    }

    private double[] visualSamples(double[] values) {
        double max = Arrays.stream(values).max().orElse(IDLE_AMPLITUDE);
        if (max <= IDLE_AMPLITUDE + 0.01) {
            return values;
        }

        double scale = Math.max(1.0, Math.min(4.0, TARGET_PEAK_AMPLITUDE / max));
        double[] visual = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            double sample = Math.min(1.0, values[i] * scale);
            visual[i] = sample <= IDLE_AMPLITUDE + 0.01 ? IDLE_AMPLITUDE : Math.max(MIN_ACTIVE_AMPLITUDE, sample);
        }
        return visual;
    }

    private double[] smoothedSamples() {
        double[] smoothed = new double[samples.length];
        for (int i = 0; i < samples.length; i++) {
            double previous = samples[Math.max(0, i - 1)];
            double current = samples[i];
            double next = samples[Math.min(samples.length - 1, i + 1)];
            smoothed[i] = previous * 0.2 + current * 0.6 + next * 0.2;
        }
        return smoothed;
    }

    private static double displayAmplitude(double rms, double peak) {
        double safeRms = finitePositive(rms);
        double safePeak = finitePositive(peak);
        double amplified = Math.max(safeRms * RMS_GAIN, safePeak * PEAK_GAIN);
        double clamped = Math.max(0, Math.min(1, amplified));
        return Math.max(IDLE_AMPLITUDE, Math.pow(clamped, RESPONSE_CURVE));
    }

    private static double finitePositive(double value) {
        return Double.isFinite(value) && value > 0 ? value : 0;
    }

    private static int sampleAlpha(double sample) {
        return (int) Math.round(110 + Math.min(1.0, sample) * 125);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }
}
