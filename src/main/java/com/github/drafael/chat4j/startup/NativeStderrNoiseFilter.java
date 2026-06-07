package com.github.drafael.chat4j.startup;

import com.sun.jna.LastErrorException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class NativeStderrNoiseFilter {

    private static final int STDERR_FILENO = 2;
    private static final int EINTR = 4;
    private static final int BUFFER_SIZE = 4096;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final byte[] APPKIT_THREAD_TOKEN = "Exception in thread \"AppKit Thread\"".getBytes(StandardCharsets.UTF_8);

    private NativeStderrNoiseFilter() {
    }

    public static void installMacOsAppKitThreadFilter() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        try {
            LibC libc = Native.load("c", LibC.class);
            int preservedStderr = libc.dup(STDERR_FILENO);
            if (preservedStderr < 0) {
                return;
            }

            int[] pipeFds = new int[2];
            if (libc.pipe(pipeFds) != 0) {
                libc.close(preservedStderr);
                return;
            }

            int readFd = pipeFds[0];
            int writeFd = pipeFds[1];
            if (libc.dup2(writeFd, STDERR_FILENO) < 0) {
                libc.close(readFd);
                libc.close(writeFd);
                libc.close(preservedStderr);
                return;
            }
            libc.close(writeFd);

            Thread forwarder = new Thread(
                    () -> forwardFilteredStderr(libc, readFd, preservedStderr),
                    "chat4j-native-stderr-filter"
            );
            forwarder.setDaemon(true);
            forwarder.start();
        } catch (Throwable t) {
            log.debug("Native stderr noise filter unavailable: {}", t.getMessage());
        }
    }

    private static void forwardFilteredStderr(LibC libc, int readFd, int preservedStderr) {
        byte[] buffer = new byte[BUFFER_SIZE];
        TokenFilter filter = new TokenFilter(libc, preservedStderr, APPKIT_THREAD_TOKEN);
        try {
            while (true) {
                int count = read(libc, readFd, buffer);
                if (count == InterruptedRead.CONTINUE) {
                    continue;
                }
                if (count <= 0) {
                    break;
                }
                filter.write(buffer, count);
            }
            filter.flushMatchedPrefix();
        } finally {
            libc.dup2(preservedStderr, STDERR_FILENO);
            libc.close(readFd);
            libc.close(preservedStderr);
        }
    }

    private static int read(LibC libc, int readFd, byte[] buffer) {
        try {
            int count = libc.read(readFd, buffer, buffer.length);
            if (count < 0 && Native.getLastError() == EINTR) {
                return InterruptedRead.CONTINUE;
            }
            return count;
        } catch (LastErrorException e) {
            if (e.getErrorCode() == EINTR) {
                return InterruptedRead.CONTINUE;
            }
            log.debug("Native stderr filter read failed: {}", e.getMessage());
            return -1;
        }
    }

    private static final class TokenFilter {
        private final LibC libc;
        private final int outputFd;
        private final byte[] token;
        private int matched;

        private TokenFilter(LibC libc, int outputFd, byte[] token) {
            this.libc = libc;
            this.outputFd = outputFd;
            this.token = token;
        }

        private void write(byte[] bytes, int length) {
            for (int index = 0; index < length; index++) {
                write(bytes[index]);
            }
        }

        private void write(byte value) {
            if (value == token[matched]) {
                matched++;
                if (matched == token.length) {
                    matched = 0;
                }
                return;
            }

            flushMatchedPrefix();
            writeToOutput(value);
        }

        private void flushMatchedPrefix() {
            if (matched == 0) {
                return;
            }
            writeToOutput(token, matched);
            matched = 0;
        }

        private void writeToOutput(byte value) {
            byte[] oneByte = {value};
            writeToOutput(oneByte, oneByte.length);
        }

        private void writeToOutput(byte[] bytes, int length) {
            byte[] output = length == bytes.length ? bytes : Arrays.copyOf(bytes, length);
            int offset = 0;
            while (offset < output.length) {
                byte[] remaining = Arrays.copyOfRange(output, offset, output.length);
                int written = write(libc, outputFd, remaining);
                if (written == InterruptedRead.CONTINUE) {
                    continue;
                }
                if (written <= 0) {
                    return;
                }
                offset += written;
            }
        }

        private int write(LibC libc, int outputFd, byte[] output) {
            try {
                int written = libc.write(outputFd, output, output.length);
                if (written < 0 && Native.getLastError() == EINTR) {
                    return InterruptedRead.CONTINUE;
                }
                return written;
            } catch (LastErrorException e) {
                return e.getErrorCode() == EINTR ? InterruptedRead.CONTINUE : -1;
            }
        }
    }

    private static final class InterruptedRead {
        private static final int CONTINUE = -2;

        private InterruptedRead() {
        }
    }

    private interface LibC extends Library {
        int pipe(int[] fds);

        int dup(int oldFd);

        int dup2(int oldFd, int newFd);

        int close(int fd);

        int read(int fd, byte[] buffer, int count) throws LastErrorException;

        int write(int fd, byte[] buffer, int count) throws LastErrorException;
    }
}
