package org.telegram.messenger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Durable baseline of cloud user dialogs that existed when forced
 * end-to-end encryption was enabled.
 *
 * <p>Plaintext mode has no confidentiality or integrity protection. An attacker
 * who can write the app's files can add peer ids and make those chats look
 * pre-existing. That mode is used only after the user accepts the warning on
 * a device without StrongBox. Encrypted mode authenticates the header as AAD,
 * so a swapped header or body fails open-read and the caller must fail closed.
 *
 * <p>Replacement writes a temp file and moves it over the destination. The
 * previous file is not deleted first. If the move fails, the previous file
 * is left in place.
 */
public final class OpengramSnapshotFile {

    public static final int MODE_STRONGBOX = 1;
    public static final int MODE_PLAINTEXT = 2;
    static final byte VERSION = 2;
    private static final byte[] MAGIC = new byte[] {'O', 'G', 'S', '2'};

    public enum Status {
        OK,
        MISSING,
        CORRUPT,
        MODE_MISMATCH,
        IO_ERROR
    }

    public static final class Record {
        public final int mode;
        public final long cutoffMillis;
        public final long[] accountIds;
        public final Set<String> peers;

        public Record(int mode, long cutoffMillis, long[] accountIds, Set<String> peers) {
            this.mode = mode;
            this.cutoffMillis = cutoffMillis;
            this.accountIds = accountIds == null ? new long[0] : accountIds;
            this.peers = peers == null ? new LinkedHashSet<>() : peers;
        }
    }

    public static final class ReadResult {
        public final Status status;
        public final Record record;

        ReadResult(Status status, Record record) {
            this.status = status;
            this.record = record;
        }
    }

    public interface Sealer {
        byte[] seal(byte[] aad, byte[] body) throws Exception;

        byte[] open(byte[] aad, byte[] sealed) throws Exception;
    }

    private OpengramSnapshotFile() {
    }

    public static boolean write(File file, Record record, Sealer sealer) {
        if (file == null || record == null) {
            return false;
        }
        if (record.mode != MODE_STRONGBOX && record.mode != MODE_PLAINTEXT) {
            return false;
        }
        if (record.mode == MODE_STRONGBOX && sealer == null) {
            return false;
        }
        try {
            byte[] header = header(record);
            byte[] body = encodePeers(record.peers);
            byte[] storedBody = record.mode == MODE_PLAINTEXT ? body : sealer.seal(header, body);
            byte[] payload = concat(header, storedBody);
            return replace(file, payload);
        } catch (Exception e) {
            return false;
        }
    }

    public static ReadResult read(File file, int expectedMode, Sealer sealer) {
        if (file == null || !file.exists()) {
            return new ReadResult(Status.MISSING, null);
        }
        try {
            byte[] payload = readFully(file);
            if (payload.length < MAGIC.length + 1 + 1 + 8 + 2) {
                return new ReadResult(Status.CORRUPT, null);
            }
            for (int i = 0; i < MAGIC.length; i++) {
                if (payload[i] != MAGIC[i]) {
                    return new ReadResult(Status.CORRUPT, null);
                }
            }
            int offset = MAGIC.length;
            int version = payload[offset++] & 0xff;
            int mode = payload[offset++] & 0xff;
            if (version != VERSION || (mode != MODE_STRONGBOX && mode != MODE_PLAINTEXT)) {
                return new ReadResult(Status.CORRUPT, null);
            }
            if (mode != expectedMode) {
                return new ReadResult(Status.MODE_MISMATCH, null);
            }
            long cutoff = readLong(payload, offset);
            offset += 8;
            int accountCount = ((payload[offset] & 0xff) << 8) | (payload[offset + 1] & 0xff);
            offset += 2;
            if (accountCount < 0 || payload.length < offset + accountCount * 8L) {
                return new ReadResult(Status.CORRUPT, null);
            }
            long[] accounts = new long[accountCount];
            for (int i = 0; i < accountCount; i++) {
                accounts[i] = readLong(payload, offset);
                offset += 8;
            }
            byte[] header = Arrays.copyOf(payload, offset);
            byte[] storedBody = Arrays.copyOfRange(payload, offset, payload.length);
            byte[] body;
            if (mode == MODE_PLAINTEXT) {
                body = storedBody;
            } else {
                if (sealer == null) {
                    return new ReadResult(Status.CORRUPT, null);
                }
                body = sealer.open(header, storedBody);
            }
            Set<String> peers = decodePeers(body);
            return new ReadResult(Status.OK, new Record(mode, cutoff, accounts, peers));
        } catch (Exception e) {
            return new ReadResult(Status.CORRUPT, null);
        }
    }

    public static boolean delete(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        File tmp = tempFile(file);
        if (tmp.exists() && !tmp.delete()) {
            return false;
        }
        return file.delete();
    }

    private static boolean replace(File dest, byte[] payload) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        File tmp = tempFile(dest);
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(payload);
            out.getFD().sync();
        } finally {
            out.close();
        }
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
    }

    private static File tempFile(File dest) {
        return new File(dest.getParentFile(), dest.getName() + ".tmp");
    }

    private static byte[] header(Record record) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(MAGIC.length + 12 + record.accountIds.length * 8);
        out.write(MAGIC, 0, MAGIC.length);
        out.write(VERSION);
        out.write(record.mode);
        writeLong(out, record.cutoffMillis);
        int count = record.accountIds.length;
        out.write((count >>> 8) & 0xff);
        out.write(count & 0xff);
        for (long accountId : record.accountIds) {
            writeLong(out, accountId);
        }
        return out.toByteArray();
    }

    static byte[] encodePeers(Set<String> peers) {
        StringBuilder builder = new StringBuilder();
        if (peers != null) {
            for (String peer : peers) {
                if (peer == null || peer.indexOf('\n') >= 0 || peer.indexOf('\r') >= 0) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(peer);
            }
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Set<String> decodePeers(byte[] body) {
        LinkedHashSet<String> peers = new LinkedHashSet<>();
        if (body == null || body.length == 0) {
            return peers;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i != text.length() && text.charAt(i) != '\n') {
                continue;
            }
            String line = text.substring(start, i).trim();
            if (!line.isEmpty()) {
                peers.add(line);
            }
            start = i + 1;
        }
        return peers;
    }

    private static byte[] concat(byte[] header, byte[] body) {
        byte[] payload = Arrays.copyOf(header, header.length + body.length);
        System.arraycopy(body, 0, payload, header.length, body.length);
        return payload;
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((value >>> shift) & 0xff));
        }
    }

    private static long readLong(byte[] payload, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (payload[offset + i] & 0xffL);
        }
        return value;
    }

    private static byte[] readFully(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (count > 0) {
                    out.write(buffer, 0, count);
                }
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
