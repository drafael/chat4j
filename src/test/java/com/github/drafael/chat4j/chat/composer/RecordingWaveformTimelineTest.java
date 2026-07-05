package com.github.drafael.chat4j.chat.composer;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingWaveformTimelineTest {

    @Test
    @DisplayName("Non-finite audio levels do not enter the waveform sample buffer")
    void addLevel_whenValuesAreNonFinite_sanitizesSamples() throws Exception {
        var subject = new RecordingWaveformTimeline();

        subject.addLevel(Double.NaN, Double.POSITIVE_INFINITY);
        subject.addLevel(Double.NEGATIVE_INFINITY, -1.0);

        assertThat(Arrays.stream(readSamples(subject)).allMatch(sample -> Double.isFinite(sample) && sample >= 0))
                .isTrue();
    }

    private double[] readSamples(RecordingWaveformTimeline timeline) throws Exception {
        Field field = RecordingWaveformTimeline.class.getDeclaredField("samples");
        field.setAccessible(true);
        return (double[]) field.get(timeline);
    }
}
