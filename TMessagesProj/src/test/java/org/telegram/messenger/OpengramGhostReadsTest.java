package org.telegram.messenger;

import org.junit.Test;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpengramGhostReadsTest {

    @Test
    public void serverReactionSnapshotDoesNotRaiseLocalCount() {
        assertEquals(0, OpengramGhostReads.reactionCountFromServer(0, 4));
        assertEquals(1, OpengramGhostReads.reactionCountFromServer(1, 6));
    }

    @Test
    public void lowerServerReactionCountIsKept() {
        assertEquals(0, OpengramGhostReads.reactionCountFromServer(3, 0));
        assertEquals(2, OpengramGhostReads.reactionCountFromServer(5, 2));
    }

    @Test
    public void negativeReactionCountsClampToZero() {
        assertEquals(0, OpengramGhostReads.reactionCountFromServer(-1, -4));
    }

    @Test
    public void storyReadIdKeepsTheFurtherLocalRead() {
        assertEquals(40, OpengramGhostReads.storyReadId(40, 12));
        assertEquals(40, OpengramGhostReads.storyReadId(12, 40));
    }

    @Test
    public void keystoreErrorDoesNotSelectPlaintext() {
        assertEquals(OpengramSnapshotFile.MODE_STRONGBOX, OpengramGhostReads.modeForProbe(OpengramStrongBox.Probe.AVAILABLE));
        assertEquals(OpengramSnapshotFile.MODE_PLAINTEXT, OpengramGhostReads.modeForProbe(OpengramStrongBox.Probe.UNSUPPORTED));
        assertEquals(0, OpengramGhostReads.modeForProbe(OpengramStrongBox.Probe.ERROR));
    }

    @Test
    public void ghostKeysArePerUserAndNotTheEndToEndKey() {
        assertFalse(OpengramStrongBox.forGhost(50L).alias().equals(OpengramStrongBox.forGhost(80L).alias()));
        assertFalse(OpengramStrongBox.forGhost(50L).alias().equals(OpengramStrongBox.SEALER.alias()));
        assertFalse(OpengramStrongBox.SEALER.alias().contains("ghost"));
    }

    @Test
    public void ledgerLinesRoundTripAndRejectForeignRecords() {
        String reaction = OpengramGhostReads.reactionLine(-9L, 77);
        OpengramGhostReads.Entry entry = OpengramGhostReads.parse(reaction);
        assertEquals(true, entry.reaction);
        assertEquals(-9L, entry.dialogId);
        assertEquals(77, entry.value);

        String story = OpengramGhostReads.storyLine(3L, 40);
        OpengramGhostReads.Entry storyEntry = OpengramGhostReads.parse(story);
        assertEquals(false, storyEntry.reaction);
        assertEquals(40, storyEntry.value);

        assertNull(OpengramGhostReads.parse("5:9"));
        assertNull(OpengramGhostReads.parse("r,1,50,-9,77"));
        assertNull(OpengramGhostReads.parse("r,1,2"));
        assertNull(OpengramGhostReads.parse(null));
    }

    @Test
    public void ledgerRoundTripsThroughPlaintextSnapshot() throws Exception {
        assertRoundTrip(OpengramSnapshotFile.MODE_PLAINTEXT, null);
    }

    @Test
    public void ledgerRoundTripsThroughSealedSnapshot() throws Exception {
        assertRoundTrip(OpengramSnapshotFile.MODE_STRONGBOX, sealer());
    }

    private static void assertRoundTrip(int mode, OpengramSnapshotFile.Sealer sealer) throws Exception {
        Set<String> lines = new LinkedHashSet<>();
        lines.add(OpengramGhostReads.reactionLine(-9L, 77));
        lines.add(OpengramGhostReads.storyLine(3L, 40));
        File file = File.createTempFile("opengram-ghost", ".bin");
        file.deleteOnExit();
        assertTrue(OpengramSnapshotFile.write(file, new OpengramSnapshotFile.Record(mode, 0L, new long[] {50L}, lines), sealer));
        OpengramSnapshotFile.ReadResult read = OpengramSnapshotFile.read(file, mode, sealer);
        assertEquals(OpengramSnapshotFile.Status.OK, read.status);
        assertTrue(read.record.peers.contains(OpengramGhostReads.reactionLine(-9L, 77)));
        assertTrue(read.record.peers.contains(OpengramGhostReads.storyLine(3L, 40)));
        assertEquals(50L, read.record.accountIds[0]);
        file.delete();
    }

    private static OpengramSnapshotFile.Sealer sealer() {
        return new OpengramSnapshotFile.Sealer() {
            @Override
            public byte[] seal(byte[] aad, byte[] body) {
                int sum = checksum(aad, body);
                byte[] out = new byte[body.length + 4];
                out[0] = (byte) (sum >>> 24);
                out[1] = (byte) (sum >>> 16);
                out[2] = (byte) (sum >>> 8);
                out[3] = (byte) sum;
                System.arraycopy(body, 0, out, 4, body.length);
                return out;
            }

            @Override
            public byte[] open(byte[] aad, byte[] sealed) {
                if (sealed == null || sealed.length < 4) {
                    throw new IllegalStateException("bad seal");
                }
                byte[] body = new byte[sealed.length - 4];
                System.arraycopy(sealed, 4, body, 0, body.length);
                int sum = checksum(aad, body);
                int stored = ((sealed[0] & 0xff) << 24) | ((sealed[1] & 0xff) << 16) | ((sealed[2] & 0xff) << 8) | (sealed[3] & 0xff);
                if (sum != stored) {
                    throw new IllegalStateException("bad seal");
                }
                return body;
            }

            private int checksum(byte[] aad, byte[] body) {
                int sum = 1;
                if (aad != null) {
                    for (byte value : aad) {
                        sum = sum * 31 + value;
                    }
                }
                if (body != null) {
                    for (byte value : body) {
                        sum = sum * 31 + value;
                    }
                }
                return sum;
            }
        };
    }
}
