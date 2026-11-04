package org.telegram.messenger;

import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpengramContentFilterTest {

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
        List<String> keywords = OpengramContentFilter.parseKeywords("  Foo, foo, , Bar \n,baz,");
        assertEquals(Arrays.asList("Foo", "Bar", "baz"), keywords);

        String longWord = new String(new char[OpengramContentFilter.MAX_KEYWORD_LENGTH + 1]).replace('\0', 'a');
        String kept = new String(new char[OpengramContentFilter.MAX_KEYWORD_LENGTH]).replace('\0', 'b');
        assertEquals(Collections.singletonList(kept), OpengramContentFilter.parseKeywords(longWord + "," + kept));

        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < OpengramContentFilter.MAX_KEYWORDS + 4; i++) {
            if (i > 0) {
                raw.append(',');
            }
            raw.append("word").append(i);
        }
        assertEquals(OpengramContentFilter.MAX_KEYWORDS, OpengramContentFilter.parseKeywords(raw.toString()).size());
        assertTrue(OpengramContentFilter.parseKeywords(null).isEmpty());
        assertTrue(OpengramContentFilter.parseKeywords(" , , ").isEmpty());
    }

    @Test
    public void matchesTextIsCaseInsensitive() {
        List<String> keywords = OpengramContentFilter.parseKeywords("Sale, hello world");
        assertTrue(OpengramContentFilter.matchesText("flash SALE today", keywords));
        assertTrue(OpengramContentFilter.matchesText("Hello World", keywords));
        assertFalse(OpengramContentFilter.matchesText("unrelated", keywords));
        assertFalse(OpengramContentFilter.matchesText(null, keywords));
        assertFalse(OpengramContentFilter.matchesText("", keywords));
        assertFalse(OpengramContentFilter.matchesText("sale", Collections.<String>emptyList()));
    }

    @Test
    public void pluralsMatchTheSameWordAndNotADifferentWord() {
        List<String> story = OpengramContentFilter.parseKeywords("story");
        assertTrue(OpengramContentFilter.matchesText("two stories", story));
        assertTrue(OpengramContentFilter.matchesText("a story.", story));
        assertFalse(OpengramContentFilter.matchesText("history", story));
        List<String> stories = OpengramContentFilter.parseKeywords("stories");
        assertTrue(OpengramContentFilter.matchesText("one story", stories));

        List<String> cat = OpengramContentFilter.parseKeywords("cat");
        assertTrue(OpengramContentFilter.matchesText("cats", cat));
        assertTrue(OpengramContentFilter.matchesText("a cat.", cat));
        assertFalse(OpengramContentFilter.matchesText("category", cat));
        assertFalse(OpengramContentFilter.matchesText("education", cat));
        List<String> cats = OpengramContentFilter.parseKeywords("cats");
        assertTrue(OpengramContentFilter.matchesText("cat", cats));

        List<String> bus = OpengramContentFilter.parseKeywords("bus");
        assertTrue(OpengramContentFilter.matchesText("buses", bus));
        assertFalse(OpengramContentFilter.matchesText("business", bus));
        List<String> buses = OpengramContentFilter.parseKeywords("buses");
        assertTrue(OpengramContentFilter.matchesText("bus", buses));

        assertTrue(OpengramContentFilter.matchesText("knives", OpengramContentFilter.parseKeywords("knife")));
        assertTrue(OpengramContentFilter.matchesText("knife", OpengramContentFilter.parseKeywords("knives")));
        assertTrue(OpengramContentFilter.matchesText("children", OpengramContentFilter.parseKeywords("child")));
        assertFalse(OpengramContentFilter.matchesText("childhood", OpengramContentFilter.parseKeywords("child")));
        assertTrue(OpengramContentFilter.matchesText("child", OpengramContentFilter.parseKeywords("children")));

        assertTrue(OpengramContentFilter.matchesText("кот", OpengramContentFilter.parseKeywords("кот")));
        assertTrue(OpengramContentFilter.matchesText("коты", OpengramContentFilter.parseKeywords("кот")));
        assertTrue(OpengramContentFilter.matchesText("кот", OpengramContentFilter.parseKeywords("коты")));
        assertFalse(OpengramContentFilter.matchesText("котик", OpengramContentFilter.parseKeywords("кот")));
        assertTrue(OpengramContentFilter.matchesText("кошки", OpengramContentFilter.parseKeywords("кошка")));
        assertTrue(OpengramContentFilter.matchesText("кошка", OpengramContentFilter.parseKeywords("кошки")));
        assertTrue(OpengramContentFilter.matchesText("мамы", OpengramContentFilter.parseKeywords("мама")));
        assertTrue(OpengramContentFilter.matchesText("окна", OpengramContentFilter.parseKeywords("окно")));
        assertTrue(OpengramContentFilter.matchesText("окно", OpengramContentFilter.parseKeywords("окна")));
        assertTrue(OpengramContentFilter.matchesText("слова", OpengramContentFilter.parseKeywords("слово")));
        assertFalse(OpengramContentFilter.matchesText("славы", OpengramContentFilter.parseKeywords("слово")));
        assertTrue(OpengramContentFilter.matchesText("славы", OpengramContentFilter.parseKeywords("слава")));
        assertFalse(OpengramContentFilter.matchesText("слово", OpengramContentFilter.parseKeywords("слава")));
        assertTrue(OpengramContentFilter.matchesText("моря", OpengramContentFilter.parseKeywords("море")));
        assertTrue(OpengramContentFilter.matchesText("врачи", OpengramContentFilter.parseKeywords("врач")));
        assertTrue(OpengramContentFilter.matchesText("ночи", OpengramContentFilter.parseKeywords("ночь")));
        assertTrue(OpengramContentFilter.matchesText("музеи", OpengramContentFilter.parseKeywords("музей")));
        assertTrue(OpengramContentFilter.matchesText("истории", OpengramContentFilter.parseKeywords("история")));
        assertTrue(OpengramContentFilter.matchesText("дома", OpengramContentFilter.parseKeywords("дом")));
        assertFalse(OpengramContentFilter.matchesText("домы", OpengramContentFilter.parseKeywords("дом")));
        assertTrue(OpengramContentFilter.matchesText("люди", OpengramContentFilter.parseKeywords("человек")));
        assertTrue(OpengramContentFilter.matchesText("ребенок", OpengramContentFilter.parseKeywords("дети")));
        assertTrue(OpengramContentFilter.matchesText("ежи", OpengramContentFilter.parseKeywords("ёж")));
        assertTrue(OpengramContentFilter.matchesText("листы", OpengramContentFilter.parseKeywords("лист")));
        assertTrue(OpengramContentFilter.matchesText("листья", OpengramContentFilter.parseKeywords("лист")));
        assertFalse(OpengramContentFilter.matchesText("иванов", OpengramContentFilter.parseKeywords("иван")));
        assertFalse(OpengramContentFilter.matchesText("метра", OpengramContentFilter.parseKeywords("метро")));
        assertTrue(OpengramContentFilter.matchesText("x100y", OpengramContentFilter.parseKeywords("100")));
        assertFalse(OpengramContentFilter.matchesText("news", OpengramContentFilter.parseKeywords("new")));
        assertFalse(OpengramContentFilter.matchesText("new", OpengramContentFilter.parseKeywords("news")));
        assertTrue(OpengramContentFilter.matchesText("horses", OpengramContentFilter.parseKeywords("horse")));
        assertTrue(OpengramContentFilter.matchesText("horse", OpengramContentFilter.parseKeywords("horses")));
        assertTrue(OpengramContentFilter.matchesText("gases", OpengramContentFilter.parseKeywords("gas")));
        assertTrue(OpengramContentFilter.matchesText("gas", OpengramContentFilter.parseKeywords("gases")));
    }

    @Test
    public void missingChatWithLocalChannelIsAChannel() {
        assertTrue(OpengramContentFilter.missingChatIsChannel(null, true, false));
        assertFalse(OpengramContentFilter.missingChatIsChannel(null, true, true));
        assertFalse(OpengramContentFilter.missingChatIsChannel(null, false, false));
        TLRPC.Chat community = new TLRPC.Chat();
        community.broadcast = false;
        assertFalse(OpengramContentFilter.missingChatIsChannel(community, true, false));
        TLRPC.Chat channel = new TLRPC.Chat();
        channel.broadcast = true;
        assertTrue(OpengramContentFilter.missingChatIsChannel(channel, false, false));
    }

    @Test
    public void filterAppliesToChannelsAndBotsOnly() {
        assertTrue(OpengramContentFilter.appliesToDialog(false, true, false));
        assertTrue(OpengramContentFilter.appliesToDialog(false, false, true));
        assertFalse(OpengramContentFilter.appliesToDialog(false, false, false));
        assertFalse(OpengramContentFilter.appliesToDialog(true, false, false));
        assertFalse(OpengramContentFilter.appliesToDialog(true, true, true));
    }

    @Test
    public void strongBoxSnapshotRoundTrips() throws Exception {
        File file = temp();
        List<String> keywords = OpengramContentFilter.parseKeywords("Alpha, beta");
        assertTrue(OpengramContentFilter.writeKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, keywords, sealer));
        OpengramContentFilter.Snapshot read = OpengramContentFilter.readKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, sealer);
        assertEquals(OpengramSnapshotFile.Status.OK, read.status);
        assertEquals(keywords, read.keywords);
    }

    @Test
    public void tamperedBodyFailsClosed() throws Exception {
        File file = temp();
        assertTrue(OpengramContentFilter.writeKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, Arrays.asList("secret"), sealer));
        byte[] bytes = Files.readAllBytes(file.toPath());
        bytes[bytes.length - 1] ^= 0x11;
        Files.write(file.toPath(), bytes);
        OpengramContentFilter.Snapshot read = OpengramContentFilter.readKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, sealer);
        assertEquals(OpengramSnapshotFile.Status.CORRUPT, read.status);
        assertTrue(read.keywords.isEmpty());

        Files.write(file.toPath(), new byte[] {1, 2, 3});
        OpengramContentFilter.Snapshot truncated = OpengramContentFilter.readKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, sealer);
        assertEquals(OpengramSnapshotFile.Status.CORRUPT, truncated.status);
        assertTrue(truncated.keywords.isEmpty());
    }

    @Test
    public void modeMismatchAndWrongAccountFailClosed() throws Exception {
        File file = temp();
        assertTrue(OpengramContentFilter.writeKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, Arrays.asList("secret"), sealer));
        OpengramContentFilter.Snapshot mismatch = OpengramContentFilter.readKeywords(file, OpengramSnapshotFile.MODE_PLAINTEXT, 7L, null);
        assertEquals(OpengramSnapshotFile.Status.MODE_MISMATCH, mismatch.status);
        assertTrue(mismatch.keywords.isEmpty());

        OpengramContentFilter.Snapshot otherAccount = OpengramContentFilter.readKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 9L, sealer);
        assertEquals(OpengramSnapshotFile.Status.CORRUPT, otherAccount.status);
        assertTrue(otherAccount.keywords.isEmpty());
    }

    @Test
    public void oldKeywordFileOpensWithAnEmptyHiddenList() throws Exception {
        File file = temp();
        assertTrue(OpengramContentFilter.writeKeywords(file, OpengramSnapshotFile.MODE_PLAINTEXT, 7L, Arrays.asList("metro"), null));
        OpengramContentFilter.Snapshot read = OpengramContentFilter.readKeywords(file, OpengramSnapshotFile.MODE_PLAINTEXT, 7L, null);
        assertEquals(OpengramSnapshotFile.Status.OK, read.status);
        assertEquals(Arrays.asList("metro"), read.keywords);
        assertTrue(read.channels.isEmpty());
    }

    @Test
    public void keywordsAndChannelsRoundTrip() throws Exception {
        File file = temp();
        ArrayList<Long> channels = new ArrayList<>();
        channels.add(-42L);
        channels.add(-7L);
        assertTrue(OpengramContentFilter.writeFilter(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, Arrays.asList("alpha", "beta"), channels, sealer));
        OpengramContentFilter.Snapshot read = OpengramContentFilter.readKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, sealer);
        assertEquals(OpengramSnapshotFile.Status.OK, read.status);
        assertEquals(Arrays.asList("alpha", "beta"), read.keywords);
        assertEquals(Arrays.asList(-42L, -7L), read.channels);
    }

    @Test
    public void savingKeywordsKeepsChannels() throws Exception {
        File file = temp();
        ArrayList<Long> channels = new ArrayList<>();
        channels.add(-15L);
        assertTrue(OpengramContentFilter.writeFilter(file, OpengramSnapshotFile.MODE_PLAINTEXT, 7L, Arrays.asList("keep"), channels, null));
        assertTrue(OpengramContentFilter.writeFilter(file, OpengramSnapshotFile.MODE_PLAINTEXT, 7L, Arrays.asList("next"), channels, null));
        OpengramContentFilter.Snapshot read = OpengramContentFilter.readKeywords(file, OpengramSnapshotFile.MODE_PLAINTEXT, 7L, null);
        assertEquals(Arrays.asList("next"), read.keywords);
        assertEquals(channels, read.channels);
    }

    @Test
    public void savingChannelsKeepsKeywords() throws Exception {
        File file = temp();
        List<String> keywords = Arrays.asList("stay");
        ArrayList<Long> first = new ArrayList<>();
        first.add(-3L);
        assertTrue(OpengramContentFilter.writeFilter(file, OpengramSnapshotFile.MODE_PLAINTEXT, 7L, keywords, first, null));
        ArrayList<Long> next = new ArrayList<>();
        next.add(-9L);
        assertTrue(OpengramContentFilter.writeFilter(file, OpengramSnapshotFile.MODE_PLAINTEXT, 7L, keywords, next, null));
        OpengramContentFilter.Snapshot read = OpengramContentFilter.readKeywords(file, OpengramSnapshotFile.MODE_PLAINTEXT, 7L, null);
        assertEquals(keywords, read.keywords);
        assertEquals(next, read.channels);
    }

    @Test
    public void failedWriteKeepsThePreviousFile() throws Exception {
        File file = temp();
        ArrayList<Long> channels = new ArrayList<>();
        channels.add(-8L);
        assertTrue(OpengramContentFilter.writeFilter(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, Arrays.asList("kept"), channels, sealer));
        byte[] before = Files.readAllBytes(file.toPath());
        assertFalse(OpengramContentFilter.writeFilter(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, Arrays.asList("lost"), channels, null));
        assertTrue(Arrays.equals(before, Files.readAllBytes(file.toPath())));
        OpengramContentFilter.Snapshot read = OpengramContentFilter.readKeywords(file, OpengramSnapshotFile.MODE_STRONGBOX, 7L, sealer);
        assertEquals(Arrays.asList("kept"), read.keywords);
        assertEquals(channels, read.channels);
    }

    @Test
    public void corruptChannelLineStopsFiltering() throws Exception {
        File file = temp();
        java.util.LinkedHashSet<String> peers = new java.util.LinkedHashSet<>();
        peers.add("ok");
        peers.add(OpengramContentFilter.CHANNEL_PREFIX + "nope");
        assertTrue(OpengramSnapshotFile.write(file, new OpengramSnapshotFile.Record(OpengramSnapshotFile.MODE_PLAINTEXT, 0L, new long[] {7L}, peers), null));
        long length = file.length();
        OpengramContentFilter.Snapshot read = OpengramContentFilter.readKeywords(file, OpengramSnapshotFile.MODE_PLAINTEXT, 7L, null);
        assertEquals(OpengramSnapshotFile.Status.CORRUPT, read.status);
        assertTrue(read.keywords.isEmpty());
        assertTrue(read.channels.isEmpty());
        assertEquals(length, file.length());
    }

    @Test
    public void searchCursorUsesTheLastServerMessage() {
        OpengramContentFilter.MessagePageCursor full = OpengramContentFilter.messagePageCursor(90, -15L, 20, 20);
        assertTrue(full.hasCursor);
        assertEquals(90, full.offsetId);
        assertEquals(-15L, full.dialogId);
        assertFalse(full.endReached);

        OpengramContentFilter.MessagePageCursor shortPage = OpengramContentFilter.messagePageCursor(4, -3L, 5, 20);
        assertTrue(shortPage.hasCursor);
        assertTrue(shortPage.endReached);

        OpengramContentFilter.MessagePageCursor empty = OpengramContentFilter.messagePageCursor(0, 0L, 0, 20);
        assertFalse(empty.hasCursor);
        assertTrue(empty.endReached);
    }

    @Test
    public void hiddenChannelDoesNotAdvancePts() {
        assertFalse(OpengramContentFilter.applyChannelPts(true));
        assertTrue(OpengramContentFilter.applyChannelPts(false));
    }

    @Test
    public void repostUsesTheForwardedChannel() {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.fwd_from = new TLRPC.TL_messageFwdHeader();
        message.fwd_from.from_id = new TLRPC.TL_peerChannel();
        message.fwd_from.from_id.channel_id = 15L;
        assertEquals(-15L, OpengramContentFilter.repostChannelDialogId(message));

        TLRPC.TL_message saved = new TLRPC.TL_message();
        saved.fwd_from = new TLRPC.TL_messageFwdHeader();
        saved.fwd_from.saved_from_peer = new TLRPC.TL_peerChannel();
        saved.fwd_from.saved_from_peer.channel_id = 9L;
        assertEquals(-9L, OpengramContentFilter.repostChannelDialogId(saved));

        assertEquals(0L, OpengramContentFilter.repostChannelDialogId(new TLRPC.TL_message()));
    }

    private static File temp() throws Exception {
        File file = File.createTempFile("opengram-filter", ".bin");
        file.delete();
        file.deleteOnExit();
        return file;
    }
}
