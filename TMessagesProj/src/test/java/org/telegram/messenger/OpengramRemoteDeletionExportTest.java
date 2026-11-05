package org.telegram.messenger;

import org.json.JSONObject;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpengramRemoteDeletionExportTest {

    @Test
    public void entryNameUsesDialogAndMessageId() {
        assertEquals("-5/9.json", OpengramRemoteDeletionExport.entryName(-5, 9, null));
        assertEquals("-5/9.json", OpengramRemoteDeletionExport.entryName(-5, 9, ""));
        assertEquals("-5/9.jpg", OpengramRemoteDeletionExport.entryName(-5, 9, "jpg"));
        assertEquals("-5/9.ogg", OpengramRemoteDeletionExport.entryName(-5, 9, ".ogg"));
        assertEquals("-5/r77.json", OpengramRemoteDeletionExport.randomEntryName(-5, 77));
    }

    @Test
    public void extensionKeepsTheOriginalSuffix() {
        TLRPC.TL_message photo = new TLRPC.TL_message();
        photo.media = new TLRPC.TL_messageMediaPhoto();
        photo.media.photo = new TLRPC.TL_photo();
        assertEquals(".jpg", OpengramRemoteDeletionExport.extension(photo, new File("ignored.bin")));

        TLRPC.TL_message document = new TLRPC.TL_message();
        document.media = new TLRPC.TL_messageMediaDocument();
        document.media.document = new TLRPC.TL_document();
        document.media.document.file_name = "clip.MP4";
        assertEquals(".mp4", OpengramRemoteDeletionExport.extension(document, null));

        TLRPC.TL_message plain = new TLRPC.TL_message();
        assertEquals(".txt", OpengramRemoteDeletionExport.extension(plain, new File("note.TXT")));
    }

    @Test
    public void textJsonKeepsCaptionAndStructuredPayload() throws Exception {
        TLRPC.TL_message photo = new TLRPC.TL_message();
        photo.date = 50;
        photo.out = true;
        photo.message = "sunset \"caption\"";
        photo.media = new TLRPC.TL_messageMediaPhoto();
        photo.media.photo = new TLRPC.TL_photo();
        JSONObject photoJson = new JSONObject(OpengramRemoteDeletionExport.textJson(photo, -1005, 9));
        assertEquals(-1005L, photoJson.getLong("dialog_id"));
        assertEquals(9, photoJson.getInt("message_id"));
        assertEquals(50, photoJson.getInt("date"));
        assertTrue(photoJson.getBoolean("out"));
        assertEquals("sunset \"caption\"", photoJson.getString("text"));
        assertEquals("photo", photoJson.getString("media"));

        TLRPC.TL_message pollMessage = new TLRPC.TL_message();
        TLRPC.TL_messageMediaPoll pollMedia = new TLRPC.TL_messageMediaPoll();
        TLRPC.TL_poll poll = new TLRPC.TL_poll();
        poll.question = new TLRPC.TL_textWithEntities();
        poll.question.text = "Pick one";
        TLRPC.TL_pollAnswer answer = new TLRPC.TL_pollAnswer();
        answer.text = new TLRPC.TL_textWithEntities();
        answer.text.text = "Yes";
        poll.answers.add(answer);
        pollMedia.poll = poll;
        pollMessage.media = pollMedia;
        JSONObject pollJson = new JSONObject(OpengramRemoteDeletionExport.textJson(pollMessage, 3, 4));
        assertEquals("poll", pollJson.getString("media"));
        assertEquals("Pick one", pollJson.getJSONObject("poll").getString("question"));
        assertEquals("Yes", pollJson.getJSONObject("poll").getJSONArray("answers").getString(0));

        TLRPC.TL_message contactMessage = new TLRPC.TL_message();
        TLRPC.TL_messageMediaContact contact = new TLRPC.TL_messageMediaContact();
        contact.phone_number = "+100";
        contact.first_name = "Ada";
        contact.last_name = "Lovelace";
        contact.user_id = 42;
        contactMessage.media = contact;
        JSONObject contactJson = new JSONObject(OpengramRemoteDeletionExport.textJson(contactMessage, 3, 5));
        assertEquals("contact", contactJson.getString("media"));
        assertEquals("+100", contactJson.getJSONObject("contact").getString("phone_number"));
        assertEquals("Ada", contactJson.getJSONObject("contact").getString("first_name"));
        assertEquals(42L, contactJson.getJSONObject("contact").getLong("user_id"));

        TLRPC.TL_message geoMessage = new TLRPC.TL_message();
        TLRPC.TL_messageMediaVenue venue = new TLRPC.TL_messageMediaVenue();
        venue.geo = new TLRPC.TL_geoPoint();
        venue.geo.lat = 1.25;
        venue.geo._long = 2.5;
        venue.title = "Cafe";
        venue.address = "Main";
        geoMessage.media = venue;
        JSONObject geoJson = new JSONObject(OpengramRemoteDeletionExport.textJson(geoMessage, 3, 6));
        assertEquals("venue", geoJson.getString("media"));
        assertEquals(1.25, geoJson.getJSONObject("geo").getDouble("lat"), 0.001);
        assertEquals(2.5, geoJson.getJSONObject("geo").getDouble("long"), 0.001);
        assertEquals("Cafe", geoJson.getJSONObject("geo").getString("title"));
        assertEquals("Main", geoJson.getJSONObject("geo").getString("address"));
    }

    @Test
    public void exportKeepsCaptionBesideTheFileAndExportsRandomIds() throws Exception {
        TLRPC.TL_message photo = new TLRPC.TL_message();
        photo.id = 9;
        photo.date = 50;
        photo.out = true;
        photo.message = "sunset caption";
        photo.random_id = 77;
        photo.media = new TLRPC.TL_messageMediaPhoto();
        photo.media.photo = new TLRPC.TL_photo();
        File media = File.createTempFile("kept", ".jpg");
        media.deleteOnExit();
        Files.write(media.toPath(), new byte[]{1, 2, 3, 4});

        ArrayList<OpengramRemoteDeletionExport.Row> rows = new ArrayList<>();
        OpengramRemoteDeletionExport.Row photoRow = new OpengramRemoteDeletionExport.Row();
        photoRow.dialogId = -1005;
        photoRow.messageId = 9;
        photoRow.message = photo;
        photoRow.file = media;
        rows.add(photoRow);

        OpengramRemoteDeletionExport.Row random = new OpengramRemoteDeletionExport.Row();
        random.dialogId = -1005;
        random.randomId = 88;
        rows.add(random);

        OpengramRemoteDeletionExport.Row missing = new OpengramRemoteDeletionExport.Row();
        missing.dialogId = 3;
        missing.messageId = 4;
        rows.add(missing);

        Exported exported = export(rows, null);
        assertEquals(2, exported.result.written);
        assertEquals(1, exported.result.missing);
        assertEquals(0, exported.result.failed);
        assertFalse(exported.result.cancelled);
        JSONObject json = new JSONObject(new String(exported.entries.get("-1005/9.json"), StandardCharsets.UTF_8));
        assertEquals("sunset caption", json.getString("text"));
        assertEquals("photo", json.getString("media"));
        assertEquals(77L, json.getLong("random_id"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, exported.entries.get("-1005/9.jpg"));
        JSONObject randomJson = new JSONObject(new String(exported.entries.get("-1005/r88.json"), StandardCharsets.UTF_8));
        assertEquals(-1005L, randomJson.getLong("dialog_id"));
        assertEquals(88L, randomJson.getLong("random_id"));
        assertTrue(randomJson.getBoolean("unresolved"));
    }

    @Test
    public void oneBadMessageDoesNotAbortTheArchive() throws Exception {
        TLRPC.TL_message broken = new TLRPC.TL_message();
        broken.id = 1;
        broken.media = new TLRPC.TL_messageMediaPhoto();
        broken.media.photo = new TLRPC.TL_photo();
        broken.message = "broken";

        TLRPC.TL_message kept = new TLRPC.TL_message();
        kept.id = 2;
        kept.message = "still here";

        ArrayList<OpengramRemoteDeletionExport.Row> rows = new ArrayList<>();
        OpengramRemoteDeletionExport.Row bad = new OpengramRemoteDeletionExport.Row();
        bad.dialogId = 8;
        bad.messageId = 1;
        bad.message = broken;
        bad.file = new File(System.getProperty("java.io.tmpdir"), "opengram-export-missing-" + System.nanoTime() + ".jpg");
        rows.add(bad);
        OpengramRemoteDeletionExport.Row good = new OpengramRemoteDeletionExport.Row();
        good.dialogId = 8;
        good.messageId = 2;
        good.message = kept;
        rows.add(good);

        Exported exported = export(rows, null);
        assertEquals(1, exported.result.failed);
        assertEquals(1, exported.result.written);
        JSONObject json = new JSONObject(new String(exported.entries.get("8/2.json"), StandardCharsets.UTF_8));
        assertEquals("still here", json.getString("text"));
    }

    @Test
    public void cancelStopsBeforeTheNextMessage() throws Exception {
        TLRPC.TL_message kept = new TLRPC.TL_message();
        kept.id = 2;
        kept.message = "skip";
        ArrayList<OpengramRemoteDeletionExport.Row> rows = new ArrayList<>();
        OpengramRemoteDeletionExport.Row row = new OpengramRemoteDeletionExport.Row();
        row.dialogId = 1;
        row.messageId = 2;
        row.message = kept;
        rows.add(row);
        Exported exported = export(rows, new AtomicBoolean(true));
        assertTrue(exported.result.cancelled);
        assertEquals(0, exported.result.written);
        assertTrue(exported.entries.isEmpty());
    }

    private static Exported export(ArrayList<OpengramRemoteDeletionExport.Row> rows, AtomicBoolean cancelled) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(buffer);
        OpengramRemoteDeletionExport.Result result = OpengramRemoteDeletionExport.writeRows(zip, rows, cancelled);
        zip.close();
        Exported exported = new Exported();
        exported.result = result;
        exported.entries = new HashMap<>();
        ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(buffer.toByteArray()));
        ZipEntry entry;
        byte[] chunk = new byte[256];
        while ((entry = input.getNextEntry()) != null) {
            ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();
            int count;
            while ((count = input.read(chunk)) >= 0) {
                if (count > 0) {
                    entryBuffer.write(chunk, 0, count);
                }
            }
            exported.entries.put(entry.getName(), entryBuffer.toByteArray());
            input.closeEntry();
        }
        input.close();
        return exported;
    }

    private static final class Exported {
        private OpengramRemoteDeletionExport.Result result;
        private Map<String, byte[]> entries;
    }
}
