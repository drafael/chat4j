package com.github.drafael.chat4j.stt.audio;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;

public class WavFileWriter implements Closeable {

    private static final int HEADER_BYTES = 44;

    private final Path path;
    private final AudioFormat format;
    private final RandomAccessFile file;
    private long dataBytes;
    private boolean finalized;

    public WavFileWriter(Path path, AudioFormat format) throws IOException {
        this.path = path;
        this.format = format;
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.file = new RandomAccessFile(path.toFile(), "rw");
        writeHeader(0);
    }

    public synchronized void write(byte[] buffer, int offset, int length) throws IOException {
        file.write(buffer, offset, length);
        dataBytes += length;
    }

    public synchronized CapturedAudio finalizeAudio(long durationMillis) throws IOException {
        if (!finalized) {
            file.seek(0);
            writeHeader(dataBytes);
            finalized = true;
        }
        return new CapturedAudio(path, durationMillis, HEADER_BYTES + dataBytes);
    }

    @Override
    public synchronized void close() throws IOException {
        file.close();
    }

    private void writeHeader(long dataSize) throws IOException {
        int sampleRate = Math.round(format.getSampleRate());
        int channels = format.getChannels();
        int bitsPerSample = format.getSampleSizeInBits();
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[] {'R', 'I', 'F', 'F'});
        header.putInt((int) (36 + dataSize));
        header.put(new byte[] {'W', 'A', 'V', 'E'});
        header.put(new byte[] {'f', 'm', 't', ' '});
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort((short) bitsPerSample);
        header.put(new byte[] {'d', 'a', 't', 'a'});
        header.putInt((int) dataSize);
        file.write(header.array());
    }
}
