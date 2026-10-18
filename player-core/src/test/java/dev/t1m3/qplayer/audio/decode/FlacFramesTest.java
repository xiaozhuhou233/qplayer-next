package dev.t1m3.qplayer.audio.decode;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class FlacFramesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void fixedFramesSeekBeforeTargetAndKeepShortFinalPacket() throws Exception {
        Path path = source(false, 100);
        try (FlacFrames f = FlacFrames.tryOpen(path.toString())) {
            assertEquals(484, f.spec().totalFrames.longValue());
            assertEquals(44100, f.spec().sampleRate);
            f.seek(200d / 44100);
            assertEquals(Math.round(192 * 1_000_000d / 44100), f.timeUs());
            ByteBuffer buffer = ByteBuffer.allocate(f.maxPacketBytes());
            assertTrue(f.read(buffer) > 0); f.advance();
            assertEquals(Math.round(384 * 1_000_000d / 44100), f.timeUs());
            buffer.clear(); assertTrue(f.read(buffer) > 0); f.advance();
            buffer.clear(); assertEquals(-1, f.read(buffer));
            f.seek(0); assertEquals(0, f.timeUs());
        }
    }
    @Test public void variableHeadersAndHeadersAcrossScanBoundaryAreIndexed() throws Exception {
        Path path = source(true, 65525);
        try (FlacFrames f = FlacFrames.tryOpen(path.toUri().toString())) {
            ByteBuffer buffer = ByteBuffer.allocate(f.maxPacketBytes());
            for (int at = 0; at < 3; at++) {
                assertEquals(Math.round(at * 192 * 1_000_000d / 44100), f.timeUs());
                buffer.clear(); assertTrue(f.read(buffer) > 0); f.advance();
            }
            buffer.clear(); assertEquals(-1, f.read(buffer));
        }
    }
    @Test public void corruptedPayloadIsRejectedWithoutPadding() throws Exception {
        Path path = source(false, 100);
        byte[] bytes = Files.readAllBytes(path); bytes[55] ^= 16; Files.write(path, bytes);
        try (FlacFrames f = FlacFrames.tryOpen(path.toString())) {
            try { f.read(ByteBuffer.allocate(f.maxPacketBytes())); fail("corrupted FLAC accepted"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("checksum")); }
        }
    }
    @Test public void missingFramesAndTruncatedMetadataAreRejected() throws Exception {
        for (int length : new int[] {18, 160}) {
            Path path = source(false, 100);
            try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "rw")) { f.setLength(length); }
            try { FlacFrames.tryOpen(path.toString()); fail("truncated FLAC accepted"); }
            catch (EOFException expected) { }
        }
    }
    @Test public void unsupportedSourcesStayOnPlatformPath() throws Exception {
        Path path = temp.newFile().toPath(); Files.write(path, new byte[] {1,2,3,4});
        assertNull(FlacFrames.tryOpen(path.toString()));
        assertNull(FlacFrames.tryOpen("content://media/12"));
        assertNull(FlacFrames.tryOpen("https://audio.invalid/test.flac"));
    }
    private Path source(boolean variable, int firstPayload) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(0x664c6143); data.writeInt(0x80000022);
        byte[] stream = new byte[34]; stream[1] = 100; stream[3] = (byte)192;
        long packed = (44100L << 44) | (1L << 41) | (15L << 36) | 484L;
        for (int i = 17; i >= 10; i--) { stream[i] = (byte)packed; packed >>>= 8; }
        data.write(stream);
        for (int i = 0; i < 3; i++) {
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            packet.write(255); packet.write(variable ? 249 : 248);
            packet.write(i == 2 ? 0x60 : 0x10); packet.write(0x18);
            int number = variable ? i * 192 : i;
            if (number < 128) packet.write(number);
            else { packet.write(0xc0 | (number >> 6)); packet.write(0x80 | (number & 63)); }
            if (i == 2) packet.write(99);
            int crc8 = 0;
            for (byte value : packet.toByteArray()) {
                crc8 ^= value & 255;
                for (int b = 0; b < 8; b++) crc8 = ((crc8 << 1) ^ ((crc8 & 128) != 0 ? 7 : 0)) & 255;
            }
            packet.write(crc8);
            byte[] payload = new byte[i == 0 ? firstPayload : 100]; Arrays.fill(payload, (byte)0x55); packet.write(payload);
            int crc16 = 0;
            for (byte value : packet.toByteArray()) {
                crc16 ^= (value & 255) << 8;
                for (int b = 0; b < 8; b++) crc16 = ((crc16 << 1) ^ ((crc16 & 0x8000) != 0 ? 0x8005 : 0)) & 65535;
            }
            packet.write(crc16 >> 8); packet.write(crc16); data.write(packet.toByteArray());
        }
        Path path = temp.newFile().toPath(); Files.write(path, output.toByteArray()); return path;
    }
}
