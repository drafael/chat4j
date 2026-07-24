package com.github.drafael.chat4j.tts.audio;

import javazoom.jl.player.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class JavaSoundAudioPlaybackServiceTest {

    @Test
    @DisplayName("A superseded clip cannot register after a newer playback claims ownership")
    void play_whenOlderClipCreationFinishesLate_keepsNewerPlaybackOwner() throws Exception {
        Clip olderClip = completingClip();
        Clip newerClip = completingClip();
        var olderCreationStarted = new CountDownLatch(1);
        var releaseOlderCreation = new CountDownLatch(1);
        var creations = new AtomicInteger();
        var subject = new JavaSoundAudioPlaybackService(new JavaSoundAudioPlaybackService.PlaybackResourceFactory() {
            @Override
            public Clip createClip() throws Exception {
                if (creations.incrementAndGet() == 1) {
                    olderCreationStarted.countDown();
                    releaseOlderCreation.await(2, TimeUnit.SECONDS);
                    return olderClip;
                }
                return newerClip;
            }

            @Override
            public Player createPlayer(byte[] audio) {
                throw new AssertionError("MP3 player should not be created");
            }
        });
        var olderFailure = new AtomicReference<Throwable>();
        Thread olderPlayback = Thread.startVirtualThread(() -> {
            try {
                subject.play(wavAudio());
            } catch (Throwable t) {
                olderFailure.set(t);
            }
        });
        try {
            assertThat(olderCreationStarted.await(2, TimeUnit.SECONDS)).isTrue();

            subject.play(wavAudio());
            releaseOlderCreation.countDown();
            olderPlayback.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(olderPlayback.isAlive()).isFalse();
            assertThat(olderFailure.get()).isNull();
            verify(newerClip).start();
            verify(olderClip, never()).open(any(javax.sound.sampled.AudioInputStream.class));
            verify(olderClip, never()).start();
        } finally {
            releaseOlderCreation.countDown();
            olderPlayback.interrupt();
            olderPlayback.join(TimeUnit.SECONDS.toMillis(2));
            subject.stop();
        }
    }

    private static Clip completingClip() {
        Clip clip = mock(Clip.class);
        var listener = new AtomicReference<LineListener>();
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(clip).addLineListener(any(LineListener.class));
        doAnswer(invocation -> {
            LineListener registered = listener.get();
            if (registered != null) {
                registered.update(new LineEvent(clip, LineEvent.Type.STOP, 0));
            }
            return null;
        }).when(clip).start();
        return clip;
    }

    private static TextToSpeechAudio wavAudio() {
        byte[] wav = new byte[] {
                'R', 'I', 'F', 'F', 40, 0, 0, 0, 'W', 'A', 'V', 'E',
                'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 1, 0, -64, 93, 0, 0, -128, -69, 0, 0, 2, 0, 16, 0,
                'd', 'a', 't', 'a', 4, 0, 0, 0, 0, 0, 0, 0
        };
        return new TextToSpeechAudio(wav, "audio/wav", "wav");
    }
}
