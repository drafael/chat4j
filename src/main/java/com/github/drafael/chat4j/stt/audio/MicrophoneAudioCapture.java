package com.github.drafael.chat4j.stt.audio;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import lombok.NonNull;

public class MicrophoneAudioCapture {

    public static final AudioFormat TARGET_FORMAT = new AudioFormat(16_000f, 16, 1, true, false);
    public static final long MIN_DURATION_MILLIS = 500;
    public static final long MAX_CAPTURED_WAV_BYTES = 24L * 1024L * 1024L;
    public static final Duration STALE_TEMP_AGE = Duration.ofHours(24);
    private static final String TEMP_PREFIX = "chat4j-stt-";
    private static final String SPHINX4_CONVERTED_TEMP_PREFIX = "chat4j-sphinx4-converted-";
    private static final String TEMP_SUFFIX = ".wav";

    private final Path tempDirectory;

    public MicrophoneAudioCapture(@NonNull Path tempDirectory) {
        this.tempDirectory = tempDirectory;
    }

    public void cleanupStaleTempFiles() {
        try {
            Files.createDirectories(tempDirectory);
            Instant cutoff = Instant.now().minus(STALE_TEMP_AGE);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDirectory, "%s*%s".formatted(TEMP_PREFIX, TEMP_SUFFIX))) {
                stream.forEach(path -> deleteIfStale(path, cutoff));
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDirectory, "%s*%s".formatted(SPHINX4_CONVERTED_TEMP_PREFIX, TEMP_SUFFIX))) {
                stream.forEach(path -> deleteIfStale(path, cutoff));
            }
        } catch (Exception ignored) {
        }
    }

    public AudioCaptureSession start(int maxDurationSeconds, AudioLevelListener levelListener) throws Exception {
        Files.createDirectories(tempDirectory);
        applyOwnerOnlyDirectoryPermissions(tempDirectory);
        TargetDataLine line = openLine();
        Path tempFile = tempDirectory.resolve("%s%s%s".formatted(TEMP_PREFIX, UUID.randomUUID(), TEMP_SUFFIX));
        applyOwnerOnlyFilePermissions(Files.createFile(tempFile));
        CaptureSession session = new CaptureSession(line, tempFile, maxDurationSeconds, levelListener);
        session.start();
        return session;
    }

    private TargetDataLine openLine() throws Exception {
        AudioFormat[] candidates = new AudioFormat[] {
                TARGET_FORMAT,
                new AudioFormat(48_000f, 16, 1, true, false),
                new AudioFormat(44_100f, 16, 1, true, false),
                new AudioFormat(48_000f, 16, 2, true, false),
                new AudioFormat(44_100f, 16, 2, true, false),
                new AudioFormat(48_000f, 16, 1, true, true),
                new AudioFormat(44_100f, 16, 1, true, true),
                new AudioFormat(48_000f, 16, 2, true, true),
                new AudioFormat(44_100f, 16, 2, true, true)
        };
        for (AudioFormat candidate : candidates) {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, candidate);
            if (!AudioSystem.isLineSupported(info)) {
                continue;
            }
            if (!TARGET_FORMAT.matches(candidate) && !AudioSystem.isConversionSupported(TARGET_FORMAT, candidate)) {
                continue;
            }
            TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(candidate);
            return line;
        }
        throw new SpeechToTextException("The default microphone could not provide or convert to 16 kHz mono recording.");
    }

    private void deleteIfStale(Path path, Instant cutoff) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                return;
            }
            if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
        }
    }

    private void applyOwnerOnlyDirectoryPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            ));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private void applyOwnerOnlyFilePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static final class CaptureSession implements AudioCaptureSession {
        private final TargetDataLine line;
        private final Path tempFile;
        private final int maxDurationSeconds;
        private final AudioLevelListener levelListener;
        private final CompletableFuture<CapturedAudio> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();

        private CaptureSession(TargetDataLine line, Path tempFile, int maxDurationSeconds, AudioLevelListener levelListener) {
            this.line = line;
            this.tempFile = tempFile;
            this.maxDurationSeconds = maxDurationSeconds;
            this.levelListener = levelListener;
        }

        private void start() {
            Thread.startVirtualThread(this::capture);
        }

        @Override
        public CompletableFuture<CapturedAudio> completion() {
            return completion;
        }

        @Override
        public void stop() {
            stopped.set(true);
            line.stop();
            line.close();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            stop();
        }

        private void capture() {
            long started = System.nanoTime();
            try (
                    WavFileWriter writer = new WavFileWriter(tempFile, TARGET_FORMAT);
                    AudioInputStream sourceStream = new AudioInputStream(line);
                    AudioInputStream targetStream = TARGET_FORMAT.matches(line.getFormat())
                            ? sourceStream
                            : AudioSystem.getAudioInputStream(TARGET_FORMAT, sourceStream)
            ) {
                line.start();
                byte[] buffer = new byte[4096];
                long nextLevelNanos = 0L;
                while (!cancelled.get() && !stopped.get()) {
                    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    if (elapsedMillis >= maxDurationSeconds * 1000L) {
                        stopped.set(true);
                        break;
                    }
                    int read = readAudio(targetStream, buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    writer.write(buffer, 0, read);
                    if (System.nanoTime() >= nextLevelNanos) {
                        publishLevel(buffer, read);
                        nextLevelNanos = System.nanoTime() + Duration.ofMillis(33).toNanos();
                    }
                }
                line.stop();
                line.close();
                if (cancelled.get()) {
                    Files.deleteIfExists(tempFile);
                    completion.cancel(false);
                    return;
                }
                CapturedAudio audio = writer.finalizeAudio(Duration.ofNanos(System.nanoTime() - started).toMillis());
                completion.complete(audio);
            } catch (Exception e) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
                completion.completeExceptionally(e);
            }
        }

        private int readAudio(AudioInputStream targetStream, byte[] buffer) throws IOException {
            try {
                return targetStream.read(buffer, 0, buffer.length);
            } catch (IOException e) {
                if (stopped.get() || cancelled.get()) {
                    return -1;
                }
                throw e;
            } catch (RuntimeException e) {
                if (stopped.get() || cancelled.get()) {
                    return -1;
                }
                throw e;
            }
        }

        private void publishLevel(byte[] buffer, int read) {
            if (levelListener == null) {
                return;
            }
            double sum = 0;
            double peak = 0;
            int samples = Math.max(1, read / 2);
            for (int i = 0; i + 1 < read; i += 2) {
                int sample = (buffer[i] & 0xff) | (buffer[i + 1] << 8);
                double normalized = Math.abs(sample / 32768.0);
                sum += normalized * normalized;
                peak = Math.max(peak, normalized);
            }
            levelListener.onLevel(Math.sqrt(sum / samples), peak);
        }
    }
}
