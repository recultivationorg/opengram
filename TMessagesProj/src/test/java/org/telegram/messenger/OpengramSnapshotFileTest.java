package org.telegram.messenger;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpengramSnapshotFileTest {

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
    public void strongBoxModeRoundTrips() throws Exception {
        File file = temp();
        OpengramSnapshotFile.Record record = record(OpengramSnapshotFile.MODE_STRONGBOX, 5L, "5:9");
        assertTrue(OpengramSnapshotFile.write(file, record, sealer));
        OpengramSnapshotFile.ReadResult read = OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_STRONGBOX, sealer);
        assertEquals(OpengramSnapshotFile.Status.OK, read.status);
        assertTrue(read.record.peers.contains("5:9"));
        assertEquals(5L, read.record.accountIds[0]);
    }

    @Test
    public void plaintextModeRoundTripsOnlyAsPlaintext() throws Exception {
        File file = temp();
        assertTrue(OpengramSnapshotFile.write(file, record(OpengramSnapshotFile.MODE_PLAINTEXT, 8L, "8:1"), null));
        OpengramSnapshotFile.ReadResult read = OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_PLAINTEXT, null);
        assertEquals(OpengramSnapshotFile.Status.OK, read.status);
        assertTrue(read.record.peers.contains("8:1"));
    }

    @Test
    public void encryptedModeRejectsPlaintextFile() throws Exception {
        File file = temp();
        assertTrue(OpengramSnapshotFile.write(file, record(OpengramSnapshotFile.MODE_PLAINTEXT, 8L, "8:1"), null));
        OpengramSnapshotFile.ReadResult read = OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_STRONGBOX, sealer);
        assertEquals(OpengramSnapshotFile.Status.MODE_MISMATCH, read.status);
        assertNull(read.record);
    }

    @Test
    public void sealerFailureDoesNotReplaceExistingFile() throws Exception {
        File file = temp();
        assertTrue(OpengramSnapshotFile.write(file, record(OpengramSnapshotFile.MODE_STRONGBOX, 5L, "5:1"), sealer));
        byte[] before = Files.readAllBytes(file.toPath());
        OpengramSnapshotFile.Sealer failing = new OpengramSnapshotFile.Sealer() {
            @Override
            public byte[] seal(byte[] aad, byte[] body) {
                throw new IllegalStateException("keystore");
            }

            @Override
            public byte[] open(byte[] aad, byte[] sealed) {
                throw new IllegalStateException("keystore");
            }
        };
        assertFalse(OpengramSnapshotFile.write(file, record(OpengramSnapshotFile.MODE_STRONGBOX, 5L, "5:2"), failing));
        assertTrue(Arrays.equals(before, Files.readAllBytes(file.toPath())));
        OpengramSnapshotFile.ReadResult read = OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_STRONGBOX, sealer);
        assertEquals(OpengramSnapshotFile.Status.OK, read.status);
        assertTrue(read.record.peers.contains("5:1"));
        assertFalse(read.record.peers.contains("5:2"));
    }

    @Test
    public void corruptCiphertextIsDetected() throws Exception {
        File file = temp();
        assertTrue(OpengramSnapshotFile.write(file, record(OpengramSnapshotFile.MODE_STRONGBOX, 5L, "5:1"), sealer));
        byte[] bytes = Files.readAllBytes(file.toPath());
        bytes[bytes.length - 1] ^= 0x11;
        Files.write(file.toPath(), bytes);
        OpengramSnapshotFile.ReadResult read = OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_STRONGBOX, sealer);
        assertEquals(OpengramSnapshotFile.Status.CORRUPT, read.status);
    }

    @Test
    public void missingFileIsDistinctFromEmptyBaseline() throws Exception {
        File file = temp();
        assertEquals(OpengramSnapshotFile.Status.MISSING, OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_STRONGBOX, sealer).status);
        assertTrue(OpengramSnapshotFile.write(file, record(OpengramSnapshotFile.MODE_STRONGBOX, 5L), sealer));
        OpengramSnapshotFile.ReadResult read = OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_STRONGBOX, sealer);
        assertEquals(OpengramSnapshotFile.Status.OK, read.status);
        assertNotNull(read.record);
        assertTrue(read.record.peers.isEmpty());
    }

    @Test
    public void deleteRemovesTheFile() throws Exception {
        File file = temp();
        assertTrue(OpengramSnapshotFile.write(file, record(OpengramSnapshotFile.MODE_PLAINTEXT, 1L, "1:2"), null));
        assertTrue(OpengramSnapshotFile.delete(file));
        assertFalse(file.exists());
        assertTrue(OpengramSnapshotFile.delete(file));
    }

    private static File temp() throws Exception {
        File file = File.createTempFile("opengram-snapshot", ".bin");
        file.delete();
        file.deleteOnExit();
        return file;
    }

    private static OpengramSnapshotFile.Record record(int mode, long account, String... peers) {
        return new OpengramSnapshotFile.Record(mode, 100L, new long[] {account}, new LinkedHashSet<>(Arrays.asList(peers)));
    }
}
