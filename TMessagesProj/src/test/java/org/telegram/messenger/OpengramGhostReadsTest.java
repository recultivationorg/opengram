package org.telegram.messenger;

import android.util.LongSparseArray;

import org.junit.Test;

import java.io.File;
import java.util.HashSet;
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
    public void newServerReactionsAreAddedToTheLocalCount() {
        assertEquals(3, OpengramGhostReads.reactionCountAfterServer(0, 8, 5));
        assertEquals(0, OpengramGhostReads.reactionCountAfterServer(0, 5, 5));
        assertEquals(1, OpengramGhostReads.reactionCountAfterServer(2, 4, 5));
        assertEquals(1, OpengramGhostReads.reactionCountAfterServer(1, 6, -1));
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
        OpengramGhostReads.Entry count = OpengramGhostReads.parse("c,-9,4");
        assertEquals(false, count.reaction);
        assertEquals(true, count.serverCount);
        assertEquals(4, count.value);
        OpengramGhostReads.Entry shortReaction = OpengramGhostReads.parse("r,1,2");
        assertEquals(true, shortReaction.reaction);
        assertEquals(1L, shortReaction.dialogId);
        assertEquals(2, shortReaction.value);
        assertNull(OpengramGhostReads.parse(null));
    }

    @Test
    public void diskBaselineWinsUntilTheLedgerIsLoaded() {
        LongSparseArray<Integer> counts = new LongSparseArray<>();
        assertFalse(OpengramGhostReads.rememberServerCount(false, counts, 5L, 9));
        assertNull(counts.get(5L));
        OpengramGhostReads.applyDiskCount(counts, 5L, 4);
        assertEquals(Integer.valueOf(4), counts.get(5L));
        assertTrue(OpengramGhostReads.rememberServerCount(true, counts, 5L, 9));
        assertEquals(Integer.valueOf(9), counts.get(5L));
        OpengramGhostReads.applyDiskCount(counts, 5L, 4);
        assertEquals(Integer.valueOf(9), counts.get(5L));
    }

    @Test
    public void topicCountLineIsSeparateFromTheDialogBaseline() {
        OpengramGhostReads.Entry topic = OpengramGhostReads.parse(OpengramGhostReads.topicCountLine(-3L, 8L, 2));
        assertEquals(true, topic.topic);
        assertEquals(false, topic.serverCount);
        assertEquals(-3L, topic.dialogId);
        assertEquals(8L, topic.topicId);
        assertEquals(2, topic.value);
        OpengramGhostReads.Entry dialog = OpengramGhostReads.parse(OpengramGhostReads.serverCountLine(-3L, 2));
        assertEquals(false, dialog.topic);
        assertEquals(true, dialog.serverCount);
        assertNull(OpengramGhostReads.parse("t,1,2"));
    }

    @Test
    public void unmarkDuringLoadDropsTheDiskReaction() {
        HashSet<Integer> messages = new HashSet<>();
        HashSet<Integer> tombstones = new HashSet<>();
        tombstones.add(7);
        OpengramGhostReads.applyReactionLine(messages, tombstones, 7);
        OpengramGhostReads.applyReactionLine(messages, tombstones, 8);
        assertFalse(messages.contains(7));
        assertTrue(messages.contains(8));
    }

    @Test
    public void missingLedgerFileIsCreated() throws Exception {
        File file = File.createTempFile("opengram-ghost", ".bin");
        assertTrue(file.delete());
        Set<String> lines = new LinkedHashSet<>();
        lines.add(OpengramGhostReads.reactionLine(4L, 9));
        assertTrue(OpengramGhostReads.ensureLedgerFile(file, OpengramSnapshotFile.MODE_PLAINTEXT, 50L, lines, null));
        assertTrue(file.exists());
        OpengramSnapshotFile.ReadResult read = OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_PLAINTEXT, null);
        assertEquals(OpengramSnapshotFile.Status.OK, read.status);
        assertTrue(read.record.peers.contains(OpengramGhostReads.reactionLine(4L, 9)));
        file.delete();
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
