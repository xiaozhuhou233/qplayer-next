package dev.t1m3.qplayer.audio.decode;

import dev.t1m3.qplayer.audio.core.ChannelLayout;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** Sample-addressed packets for cached native FLAC. Avoids vendor extractor byte chunking. */
public final class FlacFrames implements AutoCloseable {
    private static final int[] CRC16 = crc16Table();
    private final RandomAccessFile file;
    private final TrackSpec spec;
    private final ArrayList<Long> offsets = new ArrayList<>(), samples = new ArrayList<>();
    private int cursor, maxPacketBytes;

    /** Unsupported sources stay on the platform extractor; malformed native FLAC fails explicitly. */
    public static FlacFrames tryOpen(String source) throws IOException {
        Path path;
        try {
            if (source.startsWith("file:")) path = Paths.get(java.net.URI.create(source));
            else if (source.contains("://") || source.startsWith("content:")) return null;
            else path = Paths.get(source);
        } catch (IllegalArgumentException e) { return null; }
        if (!Files.isRegularFile(path)) return null;
        RandomAccessFile input = new RandomAccessFile(path.toFile(), "r");
        boolean owned = false;
        try {
            if (input.length() < 4 || input.readInt() != 0x664c6143) return null;
            FlacFrames result = new FlacFrames(input);
            owned = true;
            return result;
        } finally { if (!owned) input.close(); }
    }

    private FlacFrames(RandomAccessFile input) throws IOException {
        file = input;
        byte[] stream = null;
        boolean last;
        do {
            int type = file.readUnsignedByte();
            last = (type & 128) != 0;
            int length = (file.readUnsignedByte() << 16) | file.readUnsignedShort();
            if (file.getFilePointer() + length > file.length()) throw new EOFException("Truncated FLAC metadata");
            if ((type & 127) == 0) {
                if (length != 34 || stream != null) throw new IOException("Invalid FLAC STREAMINFO");
                stream = new byte[length]; file.readFully(stream);
            } else file.seek(file.getFilePointer() + length);
        } while (!last);
        if (stream == null) throw new IOException("Missing FLAC STREAMINFO");
        int rate = ((stream[10] & 255) << 12) | ((stream[11] & 255) << 4) | ((stream[12] & 255) >> 4);
        int channels = ((stream[12] & 14) >> 1) + 1;
        int bits = (((stream[12] & 1) << 4) | ((stream[13] & 255) >> 4)) + 1;
        long total = stream[13] & 15;
        for (int i = 14; i < 18; i++) total = (total << 8) | (stream[i] & 255);
        int block = ((stream[2] & 255) << 8) | (stream[3] & 255);
        if (rate <= 0 || total <= 0 || block <= 0) throw new IOException("Invalid FLAC duration or block size");
        spec = new TrackSpec("audio/flac", rate, ChannelLayout.of(channels), bits, total, true);
        long scan = file.getFilePointer(), expected = 0, end = file.length();
        byte[] data = new byte[65536];
        while (scan < end && expected < total) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("AutoMix cancelled");
            int count = (int) Math.min(data.length, end - scan);
            file.seek(scan); file.readFully(data, 0, count);
            int limit = scan + count == end ? count : count - 32;
            for (int i = 0; i < limit && expected < total; i++) {
                if ((data[i] & 255) != 255) continue;
                Header header = header(data, i, count, block);
                if (header == null || header.sample != expected || expected + header.frames > total) continue;
                offsets.add(scan + i); samples.add(expected);
                expected += header.frames;
            }
            scan += limit;
        }
        if (expected != total || offsets.isEmpty()) throw new EOFException("FLAC audio ended before declared duration");
        offsets.add(end);
        for (int i = 0; i < samples.size(); i++) {
            long size = offsets.get(i + 1) - offsets.get(i);
            if (size <= 0 || size > 16 * 1024 * 1024) throw new IOException("Invalid FLAC packet size");
            maxPacketBytes = Math.max(maxPacketBytes, (int) size);
        }
    }

    public TrackSpec spec() { return spec; }
    public int maxPacketBytes() { return maxPacketBytes; }
    public void seek(double seconds) {
        long target = Math.max(0, Math.round(seconds * spec.sampleRate));
        int low = 0, high = samples.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (samples.get(mid) <= target) low = mid; else high = mid - 1;
        }
        cursor = target >= spec.totalFrames ? samples.size() : low;
    }
    public long timeUs() {
        return Math.round((cursor < samples.size() ? samples.get(cursor) : spec.totalFrames) * 1_000_000d / spec.sampleRate);
    }
    public int read(ByteBuffer destination) throws IOException {
        if (cursor >= samples.size()) return -1;
        int count = (int) (offsets.get(cursor + 1) - offsets.get(cursor));
        if (count > destination.remaining()) throw new IOException("FLAC packet exceeds decoder input buffer");
        byte[] packet = new byte[count];
        file.seek(offsets.get(cursor)); file.readFully(packet);
        int crc = 0;
        for (byte value : packet) crc = ((crc << 8) ^ CRC16[((crc >>> 8) ^ (value & 255)) & 255]) & 65535;
        if (crc != 0) throw new IOException("FLAC packet checksum mismatch");
        destination.put(packet);
        return count;
    }
    public void advance() { if (cursor < samples.size()) cursor++; }
    @Override public void close() throws IOException { file.close(); }

    private static int[] crc16Table() {
        int[] result = new int[256];
        for (int i = 0; i < result.length; i++) {
            int value = i << 8;
            for (int bit = 0; bit < 8; bit++) value = ((value << 1) ^ ((value & 0x8000) != 0 ? 0x8005 : 0)) & 65535;
            result[i] = value;
        }
        return result;
    }

    private static final class Header {
        final long sample; final int frames;
        Header(long sample, int frames) { this.sample = sample; this.frames = frames; }
    }
    private static Header header(byte[] data, int at, int end, int fixedBlock) {
        int start = at;
        if (at + 6 > end || (data[at + 1] & 254) != 248 || (data[at + 3] & 1) != 0
                || ((data[at + 3] & 255) >> 4) > 10) return null;
        boolean variable = (data[at + 1] & 1) != 0;
        int code = (data[at + 2] & 255) >> 4, rateCode = data[at + 2] & 15;
        if (code == 0 || rateCode == 15) return null;
        at += 4;
        int first = data[at++] & 255, extra = 0;
        long number;
        if (first < 128) number = first;
        else {
            int mask = 128;
            while ((first & mask) != 0 && mask > 0) { extra++; mask >>= 1; }
            if (extra < 2 || extra > 7 || at + extra - 1 > end) return null;
            number = first & (mask - 1);
            for (int i = 1; i < extra; i++) {
                int next = data[at++] & 255;
                if ((next & 192) != 128) return null;
                number = (number << 6) | (next & 63);
            }
        }
        int frames = code == 1 ? 192 : code <= 5 ? 576 << (code - 2) : code >= 8 ? 256 << (code - 8) : 0;
        if (code == 6) { if (at >= end) return null; frames = (data[at++] & 255) + 1; }
        if (code == 7) {
            if (at + 2 > end) return null;
            frames = ((data[at] & 255) << 8 | (data[at + 1] & 255)) + 1; at += 2;
        }
        at += rateCode == 12 ? 1 : rateCode >= 13 ? 2 : 0;
        if (at >= end) return null;
        int crc = 0;
        for (int i = start; i <= at; i++) {
            crc ^= data[i] & 255;
            for (int bit = 0; bit < 8; bit++) crc = ((crc << 1) ^ ((crc & 128) != 0 ? 7 : 0)) & 255;
        }
        if (crc != 0) return null;
        return new Header(variable ? number : number * fixedBlock, frames);
    }
}
