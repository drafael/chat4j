package com.github.drafael.chat4j.tts.audio;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javazoom.jl.player.Player;
import org.apache.commons.lang3.StringUtils;

public class JavaSoundAudioPlaybackService implements AudioPlaybackService {

    private final Object lock = new Object();
    private Clip currentClip;
    private Player currentMp3Player;

    @Override
    public void play(TextToSpeechAudio audio) throws Exception {
        stop();
        if (isMp3(audio)) {
            playMp3(audio);
            return;
        }
        playJavaSound(audio);
    }

    @Override
    public void stop() {
        Clip clip;
        Player player;
        synchronized (lock) {
            clip = currentClip;
            player = currentMp3Player;
            currentClip = null;
            currentMp3Player = null;
        }
        if (clip != null) {
            clip.stop();
            clip.close();
        }
        if (player != null) {
            player.close();
        }
    }

    private void playJavaSound(TextToSpeechAudio audio) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Clip clip = null;
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(normalizedWavBytes(audio.bytes())))) {
            clip = AudioSystem.getClip();
            synchronized (lock) {
                currentClip = clip;
            }
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP || event.getType() == LineEvent.Type.CLOSE) {
                    done.countDown();
                }
            });
            clip.open(stream);
            clip.start();
            done.await();
        } finally {
            if (clip != null) {
                clearCurrentClip(clip);
                clip.close();
            }
        }
    }

    private void playMp3(TextToSpeechAudio audio) throws Exception {
        Player player = new Player(new ByteArrayInputStream(audio.bytes()));
        synchronized (lock) {
            currentMp3Player = player;
        }
        try {
            player.play();
        } finally {
            clearCurrentPlayer(player);
            player.close();
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
