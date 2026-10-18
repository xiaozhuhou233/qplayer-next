package dev.t1m3.qplayer.android.playback;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import dev.t1m3.qplayer.audio.core.*;
import dev.t1m3.qplayer.audio.decode.*;
import java.nio.*;
import java.util.*;

/** Worker-only decoder for AutoMix analysis and rendering. Never owns audio focus. */
public final class AndroidAutoMixDecoder implements Decoder {
    private final MediaExtractor extractor = new MediaExtractor();
    private MediaCodec codec;
    private MediaFormat inputFormat;
    private TrackSpec spec;
    private FlacFrames flac;
    private boolean inputEnd, outputEnd;
    private boolean inputQueued, outputSeen;
    private long frames, seekUs;
    private int held = -1, encoding = AudioFormat.ENCODING_PCM_16BIT;
    private ByteBuffer pcm;

    public AndroidAutoMixDecoder(Context context, String source, Map<String,String> headers) {
        try {
            if (source.startsWith("content:") || source.startsWith("file:"))
                extractor.setDataSource(context, Uri.parse(source), headers);
            else extractor.setDataSource(source, headers == null ? Collections.emptyMap() : headers);
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i); format = candidate; break;
                }
            }
            if (format == null) throw AudioException.decode("No audio track");
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            Long total = format.containsKey(MediaFormat.KEY_DURATION)
                    ? Math.round(format.getLong(MediaFormat.KEY_DURATION) / 1_000_000d * rate) : null;
            String mime = format.getString(MediaFormat.KEY_MIME);
            spec = new TrackSpec(mime, rate, ChannelLayout.of(channels), 16, total,
                    "audio/flac".equals(mime) || "audio/raw".equals(mime));
            if ("audio/flac".equals(mime)) {
                flac = FlacFrames.tryOpen(source);
                if (flac != null) {
                    spec = flac.spec();
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,
                            Math.max(flac.maxPacketBytes(), format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                                    ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0));
                }
            }
            codec = MediaCodec.createDecoderByType(mime);
            inputFormat = format;
            codec.configure(format, null, null, 0);
            codec.start();
        } catch (Exception e) {
            close();
            throw AudioException.io("Cannot decode AutoMix source", e);
        }
    }
    @Override public TrackSpec spec() { return spec; }
    @Override public long framesDecoded() { return frames; }
    @Override public boolean isDrained() { return outputEnd && pcm == null; }
    @Override public double seek(double seconds) {
        releaseHeld();
        seekUs = Math.max(0, Math.round(seconds * 1_000_000));
        if (flac != null) flac.seek(seconds);
        else extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        // A fresh codec has no queued PCM to discard. Flushing before its first
        // output can lose the AAC/FLAC codec-specific configuration sent at start.
        if (inputQueued) {
            if (outputSeen) codec.flush();
            else {
                codec.reset();
                codec.configure(inputFormat, null, null, 0);
                codec.start();
            }
        }
        inputQueued = false; outputSeen = false;
        inputEnd = false; outputEnd = false;
        frames = Math.round(seconds * spec.sampleRate);
        return seconds;
    }
    @Override public int decodeInto(AudioBuf out) {
        int written = 0, channels = spec.layout.count();
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (written < out.capacityFrames()) {
            if (Thread.currentThread().isInterrupted()) throw AudioException.decode("AutoMix cancelled");
            if (pcm != null && pcm.hasRemaining()) {
                int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT
                        || encoding == AudioFormat.ENCODING_PCM_32BIT ? 4
                        : encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED ? 3
                        : encoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
                int count = Math.min(out.capacityFrames() - written, pcm.remaining() / (bytes * channels));
                for (int i = 0; i < count; i++) for (int c = 0; c < channels; c++) {
                    float sample;
                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) sample = pcm.getFloat();
                    else if (bytes == 4) sample = pcm.getInt() / 2147483648f;
                    else if (bytes == 3) {
                        int v = (pcm.get() & 255) | ((pcm.get() & 255) << 8) | (pcm.get() << 16);
                        sample = v / 8388608f;
                    } else if (bytes == 1) sample = ((pcm.get() & 255) - 128) / 128f;
                    else sample = pcm.getShort() / 32768f;
                    out.backingArray()[out.channelBase(c) + written + i] = sample;
                }
                written += count;
                if (pcm.remaining() < bytes * channels) releaseHeld();
                if (written > 0) break;
            } else {
                releaseHeld();
                if (outputEnd) break;
                if (System.nanoTime() > deadline) throw AudioException.decode("AutoMix decoder timed out");
                if (!inputEnd) {
                    int slot = codec.dequeueInputBuffer(10_000);
                    if (slot >= 0) {
                        ByteBuffer input = codec.getInputBuffer(slot);
                        input.clear();
                        int size;
                        try { size = flac == null ? extractor.readSampleData(input, 0) : flac.read(input); }
                        catch (java.io.IOException e) { throw AudioException.io("Cannot read FLAC frame", e); }
                        if (size < 0) {
                            codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnd = true;
                        } else {
                            codec.queueInputBuffer(slot, 0, size, flac == null ? extractor.getSampleTime() : flac.timeUs(), 0);
                            if (flac == null) extractor.advance(); else flac.advance();
                        }
                        inputQueued = true;
                    }
                }
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int slot = codec.dequeueOutputBuffer(info, 10_000);
                if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputSeen = true;
                    MediaFormat f = codec.getOutputFormat();
                    if (f.getInteger(MediaFormat.KEY_SAMPLE_RATE) != spec.sampleRate
                            || f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != channels)
                        throw AudioException.decode("Unexpected decoded audio format change");
                    encoding = f.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? f.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                } else if (slot >= 0) {
                    held = slot;
                    outputSeen = true;
                    outputEnd = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    // Empty EOS/config buffers carry no PCM; some codecs return
                    // null here. The EOS flag must survive releasing that slot.
                    if (info.size <= 0) { releaseHeld(); continue; }
                    ByteBuffer output = codec.getOutputBuffer(slot);
                    if (output == null) throw AudioException.decode("Missing decoded PCM buffer");
                    pcm = output.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    pcm.limit(info.offset + info.size); pcm.position(info.offset);
                    int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT
                            || encoding == AudioFormat.ENCODING_PCM_32BIT ? 4
                            : encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED ? 3
                            : encoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
                    long skip = Math.max(0, Math.round((seekUs - info.presentationTimeUs)
                            * spec.sampleRate / 1_000_000d));
                    int discard = (int) Math.min(info.size / (bytes * channels), skip);
                    pcm.position(info.offset + discard * bytes * channels);
                }
            }
        }
        out.setFrames(written); frames += written;
        return written;
    }
    private void releaseHeld() {
        if (held >= 0 && codec != null) codec.releaseOutputBuffer(held, false);
        held = -1; pcm = null;
    }
    @Override public void close() {
        if (codec != null) {
            try { codec.release(); } catch (Exception ignored) { }
            codec = null;
        }
        extractor.release(); pcm = null; held = -1;
        if (flac != null) {
            try { flac.close(); } catch (java.io.IOException ignored) { }
            flac = null;
        }
    }
}
