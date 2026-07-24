package com.github.drafael.chat4j.stt.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MicrophoneAudioCaptureTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Temporary-file creation failure closes the opened microphone line")
    void start_whenTempFileCreationFails_closesLine() {
        TargetDataLine line = mock(TargetDataLine.class);
        var subject = new MicrophoneAudioCapture(
                tempDir,
                () -> line,
                ignored -> {
                    throw new IOException("forced temp-file failure");
                }
        );

        assertThatThrownBy(() -> subject.start(10, null))
                .isInstanceOf(IOException.class)
                .hasMessage("forced temp-file failure");

        verify(line).stop();
        verify(line).close();
    }

    @Test
    @DisplayName("Native capture failure settles the session and removes unfinished audio")
    void start_whenCaptureThrowsLinkageError_settlesAndCleansUp() throws Exception {
        TargetDataLine line = mock(TargetDataLine.class);
        when(line.getFormat()).thenReturn(MicrophoneAudioCapture.TARGET_FORMAT);
        doThrow(new NoClassDefFoundError("audio runtime missing")).when(line).start();
        var subject = new MicrophoneAudioCapture(
                tempDir,
                () -> line,
                directory -> Files.createFile(directory.resolve("capture.wav"))
        );

        AudioCaptureSession session = subject.start(10, null);

        assertThatThrownBy(() -> session.completion().get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(NoClassDefFoundError.class);
        assertThat(Files.exists(tempDir.resolve("capture.wav"))).isFalse();
        verify(line, timeout(2_000).atLeastOnce()).stop();
        verify(line, atLeastOnce()).close();
    }

    @Test
    @DisplayName("Capture worker initialization failure closes the microphone line")
    void start_whenWriterInitializationFails_closesLine() throws Exception {
        TargetDataLine line = mock(TargetDataLine.class);
        Path invalidOutput = Files.createDirectory(tempDir.resolve("output-directory"));
        var subject = new MicrophoneAudioCapture(
                tempDir,
                () -> line,
                ignored -> invalidOutput
        );

        AudioCaptureSession session = subject.start(10, null);

        assertThatThrownBy(() -> session.completion().get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        verify(line, timeout(2_000)).stop();
        verify(line, timeout(2_000)).close();
    }
}
