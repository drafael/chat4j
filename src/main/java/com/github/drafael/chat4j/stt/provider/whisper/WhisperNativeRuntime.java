package com.github.drafael.chat4j.stt.provider.whisper;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

@Slf4j
public class WhisperNativeRuntime {

    private static final WhisperNativeRuntime SHARED = new WhisperNativeRuntime(new WhisperJniBindingFactory());

    private final WhisperBindingFactory bindingFactory;
    private final ReentrantLock loadLock = new ReentrantLock();
    private final ReentrantLock operationLock = new ReentrantLock(true);
    private volatile boolean loadAttempted;
    private volatile boolean loaded;
    private volatile String statusMessage = "Whisper.cpp native runtime has not been checked yet.";

    public WhisperNativeRuntime(WhisperBindingFactory bindingFactory) {
        this.bindingFactory = bindingFactory;
    }

    public static WhisperNativeRuntime shared() {
        return SHARED;
    }

    public boolean ensureLoaded() {
        if (loaded) {
            return true;
        }
        loadLock.lock();
        try {
            if (loaded) {
                return true;
            }
            if (loadAttempted && !loaded) {
                return false;
            }
            loadAttempted = true;
            try (WhisperBinding binding = bindingFactory.create()) {
                binding.loadLibrary();
                loaded = true;
                statusMessage = "Whisper.cpp native runtime is ready.";
                return true;
            } catch (IOException | RuntimeException | LinkageError e) {
                loaded = false;
                statusMessage = "Whisper.cpp native runtime is unavailable on this platform.";
                log.warn("Whisper native runtime load failed: {}", ExceptionUtils.getMessage(e));
                return false;
            } catch (Exception e) {
                loaded = false;
                statusMessage = "Whisper.cpp native runtime is unavailable on this platform.";
                log.warn("Whisper native runtime load failed: {}", ExceptionUtils.getMessage(e));
                return false;
            }
        } finally {
            loadLock.unlock();
        }
    }

    public boolean ready() {
        return loaded;
    }

    public String statusMessage() {
        return statusMessage;
    }

    public WhisperBindingFactory bindingFactory() {
        return bindingFactory;
    }

    public <T> T callWithNativeLock(Callable<T> action) throws Exception {
        operationLock.lock();
        try {
            return action.call();
        } catch (IOException | RuntimeException | LinkageError e) {
            throw userSafeFailure(e);
        } finally {
            operationLock.unlock();
        }
    }

    public void runWithNativeLock(ThrowingRunnable action) throws Exception {
        callWithNativeLock(() -> {
            action.run();
            return null;
        });
    }

    private SpeechToTextException userSafeFailure(Throwable t) {
        String message = StringUtils.defaultIfBlank(t.getMessage(), t.getClass().getSimpleName());
        log.warn("Whisper native call failed: {}", message);
        if (t instanceof LinkageError || t instanceof UnsatisfiedLinkError) {
            return new SpeechToTextException("Whisper.cpp native runtime is unavailable on this platform.", t);
        }
        return new SpeechToTextException("Whisper.cpp transcription failed.", t);
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
