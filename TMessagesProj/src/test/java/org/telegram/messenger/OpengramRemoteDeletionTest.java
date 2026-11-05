package org.telegram.messenger;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpengramRemoteDeletionTest {

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

    private static File tempFile() throws Exception {
        File file = File.createTempFile("opengram-deletions-", ".bin");
        file.delete();
        file.deleteOnExit();
        return file;
    }
}
