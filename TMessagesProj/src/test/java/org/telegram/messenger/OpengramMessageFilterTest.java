package org.telegram.messenger;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpengramMessageFilterTest {

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
    public void parseKeywordsTrimsDedupesAndCaps() {
        List<String> keywords = OpengramMessageFilter.parseKeywords("  Foo, foo, , Bar \n,baz,");
        assertEquals(Arrays.asList("Foo", "Bar", "baz"), keywords);

        String longWord = new String(new char[OpengramMessageFilter.MAX_KEYWORD_LENGTH + 1]).replace('\0', 'a');
        String kept = new String(new char[OpengramMessageFilter.MAX_KEYWORD_LENGTH]).replace('\0', 'b');
        assertEquals(Collections.singletonList(kept), OpengramMessageFilter.parseKeywords(longWord + "," + kept));

        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < OpengramMessageFilter.MAX_KEYWORDS + 4; i++) {
            if (i > 0) {
                raw.append(',');
            }
            raw.append("word").append(i);
        }
        assertEquals(OpengramMessageFilter.MAX_KEYWORDS, OpengramMessageFilter.parseKeywords(raw.toString()).size());
        assertTrue(OpengramMessageFilter.parseKeywords(null).isEmpty());
        assertTrue(OpengramMessageFilter.parseKeywords(" , , ").isEmpty());
    }

    @Test
    public void matchesTextIsCaseInsensitive() {
        List<String> keywords = OpengramMessageFilter.parseKeywords("Sale, hello world");
        assertTrue(OpengramMessageFilter.matchesText("flash SALE today", keywords));
        assertTrue(OpengramMessageFilter.matchesText("Hello World", keywords));
        assertFalse(OpengramMessageFilter.matchesText("unrelated", keywords));
        assertFalse(OpengramMessageFilter.matchesText(null, keywords));
        assertFalse(OpengramMessageFilter.matchesText("", keywords));
        assertFalse(OpengramMessageFilter.matchesText("sale", Collections.<String>emptyList()));
    }

    @Test
    public void filterAppliesToChannelsAndBotsOnly() {
        assertTrue(OpengramMessageFilter.appliesToDialog(false, true, false));
        assertTrue(OpengramMessageFilter.appliesToDialog(false, false, true));
        assertFalse(OpengramMessageFilter.appliesToDialog(false, false, false));
        assertFalse(OpengramMessageFilter.appliesToDialog(true, false, false));
        assertFalse(OpengramMessageFilter.appliesToDialog(true, true, true));
    }

    @Test
    public void strongBoxSnapshotRoundTrips() throws Exception {
        File file = temp();
        List<String> keywords = OpengramMessageFilter.parseKeywords("Alpha, beta");
        assertTrue(OpengramMessageFilter.writeKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, keywords, sealer));
        OpengramMessageFilter.Snapshot read = OpengramMessageFilter.readKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, sealer);
        assertEquals(OpengramSnapshotFile.Status.OK, read.status);
        assertEquals(keywords, read.keywords);
    }

    @Test
    public void tamperedBodyFailsClosed() throws Exception {
        File file = temp();
        assertTrue(OpengramMessageFilter.writeKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, Arrays.asList("secret"), sealer));
        byte[] bytes = Files.readAllBytes(file.toPath());
        bytes[bytes.length - 1] ^= 0x11;
        Files.write(file.toPath(), bytes);
        OpengramMessageFilter.Snapshot read = OpengramMessageFilter.readKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, sealer);
        assertEquals(OpengramSnapshotFile.Status.CORRUPT, read.status);
        assertTrue(read.keywords.isEmpty());

        Files.write(file.toPath(), new byte[] {1, 2, 3});
        OpengramMessageFilter.Snapshot truncated = OpengramMessageFilter.readKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, sealer);
        assertEquals(OpengramSnapshotFile.Status.CORRUPT, truncated.status);
        assertTrue(truncated.keywords.isEmpty());
    }

    @Test
    public void modeMismatchAndWrongAccountFailClosed() throws Exception {
        File file = temp();
        assertTrue(OpengramMessageFilter.writeKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, Arrays.asList("secret"), sealer));
        OpengramMessageFilter.Snapshot mismatch = OpengramMessageFilter.readKeywords(file, OpengramSnapshotFile.MODE_PLAINTEXT, 7L, null);
        assertEquals(OpengramSnapshotFile.Status.MODE_MISMATCH, mismatch.status);
        assertTrue(mismatch.keywords.isEmpty());

        OpengramMessageFilter.Snapshot otherAccount = OpengramMessageFilter.readKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 9L, sealer);
        assertEquals(OpengramSnapshotFile.Status.CORRUPT, otherAccount.status);
        assertTrue(otherAccount.keywords.isEmpty());
    }

    private static File temp() throws Exception {
        File file = File.createTempFile("opengram-filter", ".bin");
        file.delete();
        file.deleteOnExit();
        return file;
    }
}
