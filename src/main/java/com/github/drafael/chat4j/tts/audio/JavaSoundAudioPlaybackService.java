package com.github.drafael.chat4j.tts.audio;

import javazoom.jl.player.Player;
import org.apache.commons.lang3.StringUtils;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

public class JavaSoundAudioPlaybackService implements AudioPlaybackService {

    private final Object lock = new Object();
    private final PlaybackResourceFactory resourceFactory;
    private long playbackGeneration;
    private Clip currentClip;
    private Player currentMp3Player;

    public JavaSoundAudioPlaybackService() {
        this(new DefaultPlaybackResourceFactory());
    }

    JavaSoundAudioPlaybackService(PlaybackResourceFactory resourceFactory) {
        this.resourceFactory = resourceFactory;
    }

    @Override
    public void play(TextToSpeechAudio audio) throws Exception {
        play(audio, () -> false);
    }

    @Override
    public void play(TextToSpeechAudio audio, BooleanSupplier isCancelled) throws Exception {
        PlaybackResources previous;
        long generation;
        synchronized (lock) {
            if (isCancelled.getAsBoolean()) {
                return;
            }
            generation = ++playbackGeneration;
            previous = detachCurrentLocked();
        }
        close(previous);

        if (isMp3(audio)) {
            playMp3(audio, generation, isCancelled);
            return;
        }
        playJavaSound(audio, generation, isCancelled);
    }

    @Override
    public void stop() {
        stopAsync();
    }

    @Override
    public CompletableFuture<Void> stopAsync() {
        PlaybackResources previous;
        synchronized (lock) {
            playbackGeneration++;
            previous = detachCurrentLocked();
        }
        if (previous.empty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(
                () -> close(previous),
                command -> Thread.ofVirtual().name("chat4j-audio-playback-close").start(command)
        );
    }

    private void playJavaSound(TextToSpeechAudio audio, long generation, BooleanSupplier isCancelled) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Clip clip = null;
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(normalizedWavBytes(audio.bytes())))) {
            clip = resourceFactory.createClip();
            Clip candidate = clip;
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP || event.getType() == LineEvent.Type.CLOSE) {
                    done.countDown();
                }
            });
            synchronized (lock) {
                if (generation != playbackGeneration || isCancelled.getAsBoolean()) {
                    return;
                }
                currentClip = candidate;
            }
            clip.open(stream);
            if (!isPlaybackCurrent(generation) || isCancelled.getAsBoolean()) {
                stopOwnedClip(clip);
                return;
            }
            clip.start();
            if (!isPlaybackCurrent(generation) || isCancelled.getAsBoolean()) {
                stopOwnedClip(clip);
            } else {
                done.await();
            }
        } finally {
            if (clip != null) {
                clearCurrentClip(clip);
                clip.close();
            }
        }
    }

    private void playMp3(TextToSpeechAudio audio, long generation, BooleanSupplier isCancelled) throws Exception {
        Player player = resourceFactory.createPlayer(audio.bytes());
        synchronized (lock) {
            if (generation != playbackGeneration || isCancelled.getAsBoolean()) {
                player.close();
                return;
            }
            currentMp3Player = player;
        }
        try {
            if (isPlaybackCurrent(generation) && !isCancelled.getAsBoolean()) {
                player.play();
            }
        } finally {
            clearCurrentPlayer(player);
            player.close();
        }
    }

    private boolean isPlaybackCurrent(long generation) {
        synchronized (lock) {
            return generation == playbackGeneration;
        }
    }

    private void stopOwnedClip(Clip clip) {
        synchronized (lock) {
            if (currentClip != clip) {
                return;
            }
            currentClip = null;
        }
        clip.stop();
    }

    private PlaybackResources detachCurrentLocked() {
        PlaybackResources resources = new PlaybackResources(currentClip, currentMp3Player);
        currentClip = null;
        currentMp3Player = null;
        return resources;
    }

    private static void close(PlaybackResources resources) {
        if (resources.clip() != null) {
            try {
                resources.clip().stop();
            } catch (RuntimeException ignored) {
            }
            try {
                resources.clip().close();
            } catch (RuntimeException ignored) {
            }
        }
        if (resources.player() != null) {
            try {
                resources.player().close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void clearCurrentClip(Clip clip) {
        synchronized (lock) {
            if (currentClip == clip) {
                currentClip = null;
            }
        }
    }

    private void clearCurrentPlayer(Player player) {
        synchronized (lock) {
            if (currentMp3Player == player) {
                currentMp3Player = null;
            }
        }
    }

    interface PlaybackResourceFactory {
        Clip createClip() throws Exception;

        Player createPlayer(byte[] audio) throws Exception;
    }

    private static final class DefaultPlaybackResourceFactory implements PlaybackResourceFactory {
        @Override
        public Clip createClip() throws Exception {
            return AudioSystem.getClip();
        }

        @Override
        public Player createPlayer(byte[] audio) throws Exception {
            return new Player(new ByteArrayInputStream(audio));
        }
    }

    private record PlaybackResources(Clip clip, Player player) {
        private boolean empty() {
            return clip == null && player == null;
        }
    }

    static byte[] normalizedWavBytes(byte[] bytes) {
        // Groq may stream RIFF/WAVE with 0xFFFFFFFF placeholder sizes; Java Sound requires concrete chunk sizes.
        if (!isRiffWave(bytes)) {
            return bytes;
        }

        byte[] normalized = bytes.clone();
        writeLittleEndianInt(normalized, 4, normalized.length - 8);
        int offset = 12;
        while (offset + 8 <= normalized.length) {
            String chunkId = new String(normalized, offset, 4, StandardCharsets.US_ASCII);
            long chunkSize = unsignedLittleEndianInt(normalized, offset + 4);
            int dataStart = offset + 8;
            if ("data".equals(chunkId) && chunkSize == 0xFFFF_FFFFL) {
                writeLittleEndianInt(normalized, offset + 4, normalized.length - dataStart);
                return normalized;
            }
            long next = dataStart + chunkSize + (chunkSize & 1L);
            if (chunkSize == 0xFFFF_FFFFL || next <= offset || next > normalized.length) {
                return normalized;
            }
            offset = (int) next;
        }
        return normalized;
    }

    private static boolean isRiffWave(byte[] bytes) {
        // RIFF/WAVE files start with a 12-byte container header: "RIFF", size, then "WAVE".
        return bytes != null
                && bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'A'
                && bytes[10] == 'V'
                && bytes[11] == 'E';
    }

    private static long unsignedLittleEndianInt(byte[] bytes, int offset) {
        // WAV chunk lengths are unsigned 32-bit little-endian values; keep them in a long for comparison.
        return ((long) bytes[offset] & 0xFF)
                | (((long) bytes[offset + 1] & 0xFF) << 8)
                | (((long) bytes[offset + 2] & 0xFF) << 16)
                | (((long) bytes[offset + 3] & 0xFF) << 24);
    }

    private static void writeLittleEndianInt(byte[] bytes, int offset, int value) {
        // RIFF stores container and chunk lengths as little-endian 32-bit integers.
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static boolean isMp3(TextToSpeechAudio audio) {
        String format = StringUtils.defaultString(audio.format()).toLowerCase(Locale.ROOT);
        String contentType = StringUtils.defaultString(audio.contentType()).toLowerCase(Locale.ROOT);
        return format.contains("mp3") || contentType.contains("mpeg") || contentType.contains("mp3");
    }
}
