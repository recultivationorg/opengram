package org.telegram.messenger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;

import androidx.collection.LongSparseArray;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpengramRemoteDeletionTest {

    private File directory;
    private OpengramRemoteDeletion.Hook hook;

    @Before
    public void setUp() throws Exception {
        OpengramRemoteDeletion.resetForTest();
        directory = Files.createTempDirectory("opengram-deletions").toFile();
        directory.deleteOnExit();
        hook = new OpengramRemoteDeletion.Hook();
        hook.clientUserId = 7;
        hook.directory = directory;
        hook.sealer = sealer;
        hook.strongBoxAvailable = true;
        OpengramRemoteDeletion.hook = hook;
    }

    @After
    public void tearDown() {
        OpengramRemoteDeletion.resetForTest();
    }

    private final OpengramSnapshotFile.Sealer sealer = new OpengramSnapshotFile.Sealer() {
        @Override
        public byte[] seal(byte[] aad, byte[] body) {
            int sum = checksum(aad, body);
            byte[] out = new byte[body.length + 5];
            out[0] = (byte) (sum >>> 24);
            out[1] = (byte) (sum >>> 16);
            out[2] = (byte) (sum >>> 8);
            out[3] = (byte) sum;
            out[4] = (byte) (aad == null ? 0 : aad.length);
            System.arraycopy(body, 0, out, 5, body.length);
            return out;
        }

        @Override
        public byte[] open(byte[] aad, byte[] sealed) {
            if (sealed == null || sealed.length < 5 || sealed[4] != (byte) (aad == null ? 0 : aad.length)) {
                throw new IllegalStateException("bad seal");
            }
            byte[] body = new byte[sealed.length - 5];
            System.arraycopy(sealed, 5, body, 0, body.length);
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

    @Test
    public void roundTripKeepsMessageAndSecretRandomLines() throws Exception {
        File file = tempFile();
        long userId = 42;
        ArrayList<String> records = new ArrayList<>();
        records.add(OpengramRemoteDeletion.messageRecord(-100, 7));
        records.add(OpengramRemoteDeletion.randomRecord(-100, 9001L));
        records.add(OpengramRemoteDeletion.messageRecord(5, -3));
        assertTrue(OpengramRemoteDeletion.writeRecords(file, OpengramSnapshotFile.MODE_PLAINTEXT, userId, records, null));

        OpengramRemoteDeletion.Snapshot snapshot = OpengramRemoteDeletion.readRecords(file, userId, null);
        assertEquals(OpengramSnapshotFile.Status.OK, snapshot.status);
        assertEquals(records, snapshot.records);

        assertTrue(OpengramRemoteDeletion.writeRecords(file, OpengramSnapshotFile.MODE_STRONGBOX, userId, records, sealer));
        OpengramRemoteDeletion.Snapshot sealed = OpengramRemoteDeletion.readRecords(file, userId, sealer);
        assertEquals(OpengramSnapshotFile.Status.OK, sealed.status);
        assertEquals(OpengramSnapshotFile.MODE_STRONGBOX, sealed.mode);
        assertEquals(records, sealed.records);
    }

    @Test
    public void failedWriteLeavesThePreviousFile() throws Exception {
        File file = tempFile();
        long userId = 7;
        ArrayList<String> records = new ArrayList<>();
        records.add(OpengramRemoteDeletion.messageRecord(3, 8));
        assertTrue(OpengramRemoteDeletion.writeRecords(file, OpengramSnapshotFile.MODE_PLAINTEXT, userId, records, null));
        byte[] before = Files.readAllBytes(file.toPath());

        OpengramSnapshotFile.Sealer broken = new OpengramSnapshotFile.Sealer() {
            @Override
            public byte[] seal(byte[] aad, byte[] body) {
                throw new IllegalStateException("seal failed");
            }

            @Override
            public byte[] open(byte[] aad, byte[] sealed) {
                throw new IllegalStateException("open failed");
            }
        };
        assertFalse(OpengramRemoteDeletion.writeRecords(file, OpengramSnapshotFile.MODE_STRONGBOX, userId, records, broken));
        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
        OpengramRemoteDeletion.Snapshot snapshot = OpengramRemoteDeletion.readRecords(file, userId, null);
        assertEquals(OpengramSnapshotFile.Status.OK, snapshot.status);
        assertEquals(records, snapshot.records);
    }

    @Test
    public void corruptLineStopsTheSnapshotFromBeingTrusted() throws Exception {
        File file = tempFile();
        long userId = 9;
        LinkedHashSet<String> peers = new LinkedHashSet<>();
        peers.add(OpengramRemoteDeletion.messageRecord(1, 2));
        peers.add("broken");
        assertTrue(OpengramSnapshotFile.write(file, new OpengramSnapshotFile.Record(OpengramSnapshotFile.MODE_PLAINTEXT, 0, new long[]{userId}, peers), null));

        OpengramRemoteDeletion.Snapshot snapshot = OpengramRemoteDeletion.readRecords(file, userId, null);
        assertEquals(OpengramSnapshotFile.Status.CORRUPT, snapshot.status);
        assertTrue(snapshot.records.isEmpty());
    }

    @Test
    public void localRemovalDropsOnlyThatRecord() throws Exception {
        File file = tempFile();
        long userId = 11;
        String kept = OpengramRemoteDeletion.messageRecord(-20, 4);
        String removed = OpengramRemoteDeletion.messageRecord(-20, 5);
        String randomKept = OpengramRemoteDeletion.randomRecord(-20, 77L);
        ArrayList<String> records = new ArrayList<>();
        records.add(kept);
        records.add(removed);
        records.add(randomKept);
        assertTrue(OpengramRemoteDeletion.writeRecords(file, OpengramSnapshotFile.MODE_PLAINTEXT, userId, records, null));

        records.remove(removed);
        assertTrue(OpengramRemoteDeletion.writeRecords(file, OpengramSnapshotFile.MODE_PLAINTEXT, userId, records, null));
        OpengramRemoteDeletion.Snapshot snapshot = OpengramRemoteDeletion.readRecords(file, userId, null);
        assertEquals(OpengramSnapshotFile.Status.OK, snapshot.status);
        assertEquals(Arrays.asList(kept, randomKept), snapshot.records);
    }

    @Test
    public void fullWipeLeavesNoIds() throws Exception {
        File file = tempFile();
        long userId = 13;
        ArrayList<String> records = new ArrayList<>();
        records.add(OpengramRemoteDeletion.messageRecord(1, 2));
        records.add(OpengramRemoteDeletion.randomRecord(1, 3L));
        assertTrue(OpengramRemoteDeletion.writeRecords(file, OpengramSnapshotFile.MODE_PLAINTEXT, userId, records, null));
        assertTrue(OpengramSnapshotFile.delete(file));

        OpengramRemoteDeletion.Snapshot snapshot = OpengramRemoteDeletion.readRecords(file, userId, null);
        assertEquals(OpengramSnapshotFile.Status.MISSING, snapshot.status);
        assertTrue(snapshot.records.isEmpty());
        assertFalse(file.exists());
    }

    @Test
    public void corruptSnapshotDoesNotTurnTheSettingOn() throws Exception {
        File snapshot = snapshotFile();
        LinkedHashSet<String> peers = new LinkedHashSet<>();
        peers.add("broken");
        assertTrue(OpengramSnapshotFile.write(snapshot, new OpengramSnapshotFile.Record(OpengramSnapshotFile.MODE_PLAINTEXT, 0, new long[]{hook.clientUserId}, peers), null));

        assertFalse(OpengramRemoteDeletion.keeps(0));
        assertFalse(OpengramRemoteDeletion.enable(0, OpengramSnapshotFile.MODE_PLAINTEXT));
        assertFalse(OpengramRemoteDeletion.keeps(0));
        assertFalse(hook.remoteDeletion);
        assertFalse(snapshot.exists());

        assertTrue(OpengramRemoteDeletion.enable(0, OpengramSnapshotFile.MODE_PLAINTEXT));
        assertTrue(OpengramRemoteDeletion.keeps(0));
        assertEquals(OpengramSnapshotFile.MODE_PLAINTEXT, hook.remoteDeletionMode);
    }

    @Test
    public void failedSealDoesNotRecordTheDeletion() throws Exception {
        assertTrue(OpengramRemoteDeletion.enable(0, OpengramSnapshotFile.MODE_PLAINTEXT));
        ArrayList<Integer> kept = new ArrayList<>();
        kept.add(4);
        assertTrue(OpengramRemoteDeletion.markMessages(0, 3L, kept));
        assertTrue(OpengramRemoteDeletion.isMarked(0, 3L, 4));

        File blocked = new File(directory, "not-a-directory");
        assertTrue(blocked.createNewFile());
        hook.directory = blocked;
        ArrayList<Integer> added = new ArrayList<>();
        added.add(5);
        assertFalse(OpengramRemoteDeletion.markMessages(0, 3L, added));
        assertFalse(OpengramRemoteDeletion.isMarked(0, 3L, 5));
        assertTrue(OpengramRemoteDeletion.isMarked(0, 3L, 4));
    }

    @Test
    public void forgetMessagesDropsOnlyThatRecord() {
        assertTrue(OpengramRemoteDeletion.enable(0, OpengramSnapshotFile.MODE_PLAINTEXT));
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(4);
        ids.add(5);
        assertTrue(OpengramRemoteDeletion.markMessages(0, -20L, ids));
        ArrayList<Integer> removed = new ArrayList<>();
        removed.add(4);
        OpengramRemoteDeletion.forgetMessages(0, -20L, removed);

        assertFalse(OpengramRemoteDeletion.isMarked(0, -20L, 4));
        assertTrue(OpengramRemoteDeletion.isMarked(0, -20L, 5));
        OpengramRemoteDeletion.Snapshot snapshot = OpengramRemoteDeletion.readRecords(snapshotFile(), hook.clientUserId, null);
        assertEquals(Arrays.asList(OpengramRemoteDeletion.messageRecord(-20L, 5)), snapshot.records);
    }

    @Test
    public void disableDeletesTheSnapshotAndHidesMarks() {
        assertTrue(OpengramRemoteDeletion.enable(0, OpengramSnapshotFile.MODE_PLAINTEXT));
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(8);
        assertTrue(OpengramRemoteDeletion.markMessages(0, 1L, ids));
        OpengramRemoteDeletion.disable(0);

        assertFalse(OpengramRemoteDeletion.keeps(0));
        assertFalse(OpengramRemoteDeletion.isMarked(0, 1L, 8));
        assertFalse(snapshotFile().exists());
        assertEquals(0, hook.remoteDeletionMode);
    }

    @Test
    public void enableStrongBoxRewritesAPlaintextSnapshot() throws Exception {
        ArrayList<String> records = new ArrayList<>();
        records.add(OpengramRemoteDeletion.messageRecord(-100L, 7));
        assertTrue(OpengramRemoteDeletion.writeRecords(snapshotFile(), OpengramSnapshotFile.MODE_PLAINTEXT, hook.clientUserId, records, null));

        assertTrue(OpengramRemoteDeletion.enable(0, OpengramSnapshotFile.MODE_STRONGBOX));
        OpengramRemoteDeletion.Snapshot snapshot = OpengramRemoteDeletion.readRecords(snapshotFile(), hook.clientUserId, sealer);
        assertEquals(OpengramSnapshotFile.Status.OK, snapshot.status);
        assertEquals(OpengramSnapshotFile.MODE_STRONGBOX, snapshot.mode);
        assertEquals(records, snapshot.records);
        assertEquals(OpengramSnapshotFile.MODE_STRONGBOX, hook.remoteDeletionMode);
        assertTrue(OpengramRemoteDeletion.isMarked(0, -100L, 7));
    }

    @Test
    public void keepsFollowsThePreference() {
        assertFalse(OpengramRemoteDeletion.keeps(0));
        assertTrue(OpengramRemoteDeletion.enable(0, OpengramSnapshotFile.MODE_PLAINTEXT));
        assertTrue(OpengramRemoteDeletion.keeps(0));
        hook.remoteDeletion = false;
        assertFalse(OpengramRemoteDeletion.keeps(0));
        assertFalse(OpengramRemoteDeletion.isMarked(0, 1L, 1));
    }

    @Test
    public void channelPushUsesTheChannelDialog() {
        assertEquals(-42L, OpengramRemoteDeletion.deletionDialogId(99L, 42L));
        assertEquals(15L, OpengramRemoteDeletion.deletionDialogId(15L, 0L));
    }

    @Test
    public void historyClearSkipsLocalAndStillServedMessages() {
        assertTrue(OpengramRemoteDeletion.clearedByAvailableMin(5, 10));
        assertFalse(OpengramRemoteDeletion.clearedByAvailableMin(10, 10));
        assertFalse(OpengramRemoteDeletion.clearedByAvailableMin(-3, 10));
        assertFalse(OpengramRemoteDeletion.clearedByAvailableMin(0, 10));
    }

    @Test
    public void unresolvedDeleteIdsAreNotTreatedAsRecorded() {
        LongSparseArray<ArrayList<Integer>> found = new LongSparseArray<>();
        ArrayList<Integer> stored = new ArrayList<>();
        stored.add(1);
        stored.add(2);
        found.put(-5L, stored);
        ArrayList<Integer> requested = new ArrayList<>();
        requested.add(1);
        requested.add(2);
        requested.add(3);
        assertEquals(Arrays.asList(3), OpengramRemoteDeletion.idsNotFound(requested, found));
        assertTrue(OpengramRemoteDeletion.idsNotFound(requested, new LongSparseArray<>()).containsAll(requested));
    }

    private File snapshotFile() {
        return new File(directory, "opengram-deletions-" + hook.clientUserId + ".bin");
    }

    private static File tempFile() throws Exception {
        File file = File.createTempFile("opengram-deletions-", ".bin");
        file.delete();
        file.deleteOnExit();
        return file;
    }
}
