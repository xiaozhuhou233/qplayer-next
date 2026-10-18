package dev.t1m3.qplayer.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The model manifest ({@link StemModel}): the two hashes the device actually holds, and
 * the rule that all three facts have to agree before a file is used.
 *
 * <p>The quarter hash is pinned deliberately. A previous handoff line recorded
 * {@code e4481383…} for it — a value from before that file was regenerated — and a build
 * carrying it would refuse the very file it had just been given. This test is what keeps
 * the manifest tied to the model on disk rather than to a comment.
 */
public class StemModelTest {

    @Test
    public void theQuarterHashIsTheOneTheDeviceHolds() {
        assertEquals("427b9588287d85d78f212d9f6f4acbc42b626e28ef9d2d948fed78311f4aec20",
                StemModel.QUARTER.sha256);
        assertEquals(97_978_156L, StemModel.QUARTER.bytes);
        assertEquals(86_016L, StemModel.QUARTER.segmentSamples);
        // The stale one must not be anywhere in the manifest.
        assertFalse(StemModel.manifest().contains("e4481383"));
    }

    @Test
    public void theHalfHashIsTheVerifiedOne() {
        assertEquals("099b5be76c1f6922124d07f850250f39d1f33f254a0b8cc90f4ec0dfd0912329",
                StemModel.HALF.sha256);
        assertEquals(108_644_650L, StemModel.HALF.bytes);
        assertEquals(172_032L, StemModel.HALF.segmentSamples);
    }

    @Test
    public void aFileIsUsedOnlyWhenNameSizeAndDigestAllAgree() {
        assertNotNull(StemModel.recognise(StemModel.QUARTER.fileName,
                StemModel.QUARTER.bytes, StemModel.QUARTER.sha256));
        assertNotNull(StemModel.recognise(StemModel.HALF.fileName,
                StemModel.HALF.bytes, StemModel.HALF.sha256.toUpperCase(java.util.Locale.US)));
        // A truncated download.
        assertNull(StemModel.recognise(StemModel.QUARTER.fileName,
                StemModel.QUARTER.bytes - 1, StemModel.QUARTER.sha256));
        // The right bytes under the wrong name (a renamed copy is not a claim we accept).
        assertNull(StemModel.recognise("htdemucs.onnx",
                StemModel.QUARTER.bytes, StemModel.QUARTER.sha256));
        // A file whose bytes are not the weights the manifest names.
        assertNull(StemModel.recognise(StemModel.QUARTER.fileName,
                StemModel.QUARTER.bytes,
                "0000000000000000000000000000000000000000000000000000000000000000"));
        // Nothing at all.
        assertNull(StemModel.recognise(null, 0, null));
        assertNull(StemModel.recognise(StemModel.QUARTER.fileName, StemModel.QUARTER.bytes, null));
        // A stale hash (the pre-regeneration value) is refused, which is the bug this
        // manifest exists to prevent.
        assertNull(StemModel.recognise(StemModel.QUARTER.fileName, StemModel.QUARTER.bytes,
                "e4481383bee7c9f2e0998b514f11e6ebb73bf6b8994c7591b963d5c875ca41f0"));
    }

    @Test
    public void theSessionConfigurationIsTheMeasuredOne() {
        // Round 6's device numbers, kept next to the code that must not drift from them.
        assertEquals(4, StemModel.INTRA_OP_THREADS);
        assertFalse(StemModel.CPU_ARENA_ALLOCATOR);
        assertEquals(44_100, StemModel.MODEL_RATE);
        assertEquals(2, StemModel.candidates().size());
        assertEquals(StemModel.QUARTER, StemModel.candidates().get(0));
    }

    @Test
    public void hexMatchesTheFormSha256sumPrints() {
        assertEquals("00ff10", StemModel.hex(new byte[]{0x00, (byte) 0xFF, 0x10}));
        assertNull(StemModel.hex(null));
        assertTrue(StemModel.manifest().contains("htdemucs-quarter.onnx"));
        assertTrue(StemModel.manifest().contains("htdemucs.onnx"));
    }
}
