package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class OpengramRemoteDeletionExport {

    public static final class Result {
        public int written;
        public int missing;
        public int failed;
        public boolean cancelled;
    }

    static final class Row {
        long dialogId;
        int messageId;
        long randomId;
        TLRPC.Message message;
        File file;
    }

    private OpengramRemoteDeletionExport() {
    }

    public static Result write(int account, OutputStream destination, AtomicBoolean cancelled) throws Exception {
        Result result = new Result();
        ZipOutputStream zip = new ZipOutputStream(destination);
        try {
            if (cancelled != null && cancelled.get()) {
                result.cancelled = true;
                return result;
            }
            MessagesStorage.KeptExport loaded = MessagesStorage.getInstance(account).loadKeptForExport(OpengramRemoteDeletion.kept(account), cancelled);
            if (cancelled != null && cancelled.get()) {
                result.cancelled = true;
                return result;
            }
            ArrayList<Row> rows = new ArrayList<>();
            if (loaded != null) {
                result.missing += loaded.missing;
                if (loaded.messages != null) {
                    for (int i = 0; i < loaded.messages.size(); i++) {
                        TLRPC.Message message = loaded.messages.get(i);
                        if (message == null) {
                            result.missing++;
                            continue;
                        }
                        Row row = new Row();
                        row.dialogId = message.dialog_id;
                        row.messageId = message.id;
                        row.randomId = message.random_id;
                        row.message = message;
                        try {
                            row.file = mediaFile(account, message);
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                        rows.add(row);
                    }
                }
                if (loaded.unresolvedRandoms != null) {
                    for (int i = 0; i < loaded.unresolvedRandoms.size(); i++) {
                        long[] random = loaded.unresolvedRandoms.get(i);
                        if (random == null || random.length < 2 || random[1] == 0) {
                            continue;
                        }
                        Row row = new Row();
                        row.dialogId = random[0];
                        row.randomId = random[1];
                        rows.add(row);
                    }
                }
            }
            Result written = writeRows(zip, rows, cancelled);
            result.written += written.written;
            result.missing += written.missing;
            result.failed += written.failed;
            result.cancelled = written.cancelled;
            return result;
        } finally {
            zip.finish();
        }
    }

    static Result writeRows(ZipOutputStream zip, ArrayList<Row> rows, AtomicBoolean cancelled) {
        Result result = new Result();
        if (rows == null) {
            return result;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (cancelled != null && cancelled.get()) {
                result.cancelled = true;
                return result;
            }
            Row row = rows.get(i);
            if (row == null) {
                continue;
            }
            try {
                if (row.message == null) {
                    if (row.randomId != 0) {
                        appendUnresolvedRandom(zip, row.dialogId, row.randomId);
                        result.written++;
                    } else {
                        result.missing++;
                    }
                    continue;
                }
                int messageId = row.messageId != 0 ? row.messageId : row.message.id;
                appendMessage(zip, row.dialogId, messageId, row.message, row.file);
                result.written++;
            } catch (Exception e) {
                FileLog.e(e);
                result.failed++;
            }
        }
        return result;
    }

    static void appendMessage(ZipOutputStream zip, long dialogId, int messageId, TLRPC.Message message, File media) throws Exception {
        byte[] json = textJson(message, dialogId, messageId).getBytes(StandardCharsets.UTF_8);
        writeBytes(zip, entryName(dialogId, messageId, ".json"), json);
        if (media != null) {
            writeFile(zip, entryName(dialogId, messageId, extension(message, media)), media);
        }
    }

    static void appendUnresolvedRandom(ZipOutputStream zip, long dialogId, long randomId) throws Exception {
        JSONObject object = new JSONObject();
        object.put("dialog_id", dialogId);
        object.put("random_id", randomId);
        object.put("unresolved", true);
        writeBytes(zip, randomEntryName(dialogId, randomId), object.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String entryName(long dialogId, int messageId, String extension) {
        String ext = extension == null || extension.isEmpty() ? ".json" : extension;
        if (ext.charAt(0) != '.') {
            ext = "." + ext;
        }
        return dialogId + "/" + messageId + ext;
    }

    public static String randomEntryName(long dialogId, long randomId) {
        return dialogId + "/r" + randomId + ".json";
    }

    public static String textJson(TLRPC.Message message, long dialogId, int messageId) throws Exception {
        JSONObject object = new JSONObject();
        object.put("dialog_id", dialogId);
        object.put("message_id", messageId);
        object.put("date", message.date);
        object.put("out", message.out);
        long fromId = 0;
        if (message.from_id != null) {
            fromId = MessageObject.getPeerId(message.from_id);
        }
        object.put("from_id", fromId);
        object.put("text", message.message == null ? "" : message.message);
        if (message.random_id != 0) {
            object.put("random_id", message.random_id);
        }
        String media = mediaKind(message);
        if (media != null) {
            object.put("media", media);
        }
        putPayload(object, message);
        return object.toString();
    }

    private static void putPayload(JSONObject object, TLRPC.Message message) throws Exception {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media instanceof TLRPC.TL_messageMediaPoll) {
            TLRPC.TL_messageMediaPoll pollMedia = (TLRPC.TL_messageMediaPoll) media;
            JSONObject poll = new JSONObject();
            JSONArray answers = new JSONArray();
            if (pollMedia.poll != null) {
                if (pollMedia.poll.question != null && pollMedia.poll.question.text != null) {
                    poll.put("question", pollMedia.poll.question.text);
                }
                if (pollMedia.poll.answers != null) {
                    for (int i = 0; i < pollMedia.poll.answers.size(); i++) {
                        TLRPC.PollAnswer answer = pollMedia.poll.answers.get(i);
                        if (answer != null && answer.text != null && answer.text.text != null) {
                            answers.put(answer.text.text);
                        }
                    }
                }
            }
            poll.put("answers", answers);
            object.put("poll", poll);
        } else if (media instanceof TLRPC.TL_messageMediaContact) {
            JSONObject contact = new JSONObject();
            contact.put("phone_number", media.phone_number == null ? "" : media.phone_number);
            contact.put("first_name", media.first_name == null ? "" : media.first_name);
            contact.put("last_name", media.last_name == null ? "" : media.last_name);
            contact.put("vcard", media.vcard == null ? "" : media.vcard);
            contact.put("user_id", media.user_id);
            object.put("contact", contact);
        } else if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaGeoLive || media instanceof TLRPC.TL_messageMediaVenue) {
            JSONObject geo = new JSONObject();
            if (media.geo != null) {
                geo.put("lat", media.geo.lat);
                geo.put("long", media.geo._long);
            }
            if (media.title != null) {
                geo.put("title", media.title);
            }
            if (media.address != null) {
                geo.put("address", media.address);
            }
            if (media.provider != null) {
                geo.put("provider", media.provider);
            }
            if (media.venue_id != null) {
                geo.put("venue_id", media.venue_id);
            }
            if (media instanceof TLRPC.TL_messageMediaGeoLive) {
                geo.put("period", media.period);
            }
            object.put("geo", geo);
        } else if (media instanceof TLRPC.TL_messageMediaWebPage && media.webpage != null) {
            JSONObject webpage = new JSONObject();
            webpage.put("url", media.webpage.url == null ? "" : media.webpage.url);
            if (media.webpage.title != null) {
                webpage.put("title", media.webpage.title);
            }
            object.put("webpage", webpage);
        }
    }

    private static File mediaFile(int account, TLRPC.Message message) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (!(media instanceof TLRPC.TL_messageMediaPhoto) && !(media instanceof TLRPC.TL_messageMediaDocument)) {
            return null;
        }
        try {
            File file = FileLoader.getInstance(account).getPathToMessage(message);
            if (usable(file)) {
                return file;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (message.attachPath != null && message.attachPath.length() > 0) {
            File attached = new File(message.attachPath);
            if (usable(attached)) {
                return attached;
            }
        }
        return null;
    }

    private static boolean usable(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }

    private static String mediaKind(TLRPC.Message message) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return "photo";
        }
        if (media instanceof TLRPC.TL_messageMediaPoll) {
            return "poll";
        }
        if (media instanceof TLRPC.TL_messageMediaContact) {
            return "contact";
        }
        if (media instanceof TLRPC.TL_messageMediaGeo) {
            return "geo";
        }
        if (media instanceof TLRPC.TL_messageMediaGeoLive) {
            return "geo_live";
        }
        if (media instanceof TLRPC.TL_messageMediaVenue) {
            return "venue";
        }
        if (media instanceof TLRPC.TL_messageMediaWebPage) {
            return "webpage";
        }
        if (media instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.Document document = MessageObject.getDocument(message);
            if (MessageObject.isVoiceDocument(document)) {
                return "voice";
            }
            if (MessageObject.isRoundVideoDocument(document)) {
                return "round_video";
            }
            if (MessageObject.isVideoDocument(document)) {
                return "video";
            }
            if (MessageObject.isStickerDocument(document) || MessageObject.isAnimatedStickerDocument(document, true)) {
                return "sticker";
            }
            return "document";
        }
        return null;
    }

    static String extension(TLRPC.Message message, File file) {
        TLRPC.Document document = MessageObject.getDocument(message);
        if (document != null) {
            String name = FileLoader.getDocumentFileName(document);
            int dot = name == null ? -1 : name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = sanitizeExtension(name.substring(dot));
                if (ext.length() > 1) {
                    return ext;
                }
            }
            if (MessageObject.isVoiceDocument(document)) {
                return ".ogg";
            }
            if (MessageObject.isRoundVideoDocument(document) || MessageObject.isVideoDocument(document)) {
                return ".mp4";
            }
            if (MessageObject.isAnimatedStickerDocument(document, true)) {
                return ".tgs";
            }
            if (MessageObject.isStickerDocument(document)) {
                return ".webp";
            }
        }
        if (MessageObject.getPhoto(message) != null) {
            return ".jpg";
        }
        if (file != null) {
            String ext = FileLoader.getFileExtension(file);
            if (ext != null && !ext.isEmpty()) {
                String sanitized = sanitizeExtension("." + ext);
                if (sanitized.length() > 1 && sanitized.length() <= 9) {
                    return sanitized;
                }
            }
        }
        return ".bin";
    }

    private static String sanitizeExtension(String extension) {
        StringBuilder builder = new StringBuilder();
        builder.append('.');
        for (int i = 0; i < extension.length(); i++) {
            char c = extension.charAt(i);
            if (c == '.' && builder.length() == 1) {
                continue;
            }
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                builder.append(Character.toLowerCase(c));
            }
        }
        return builder.toString();
    }

    private static void writeBytes(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void writeFile(ZipOutputStream zip, String name, File file) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    zip.write(buffer, 0, count);
                }
            }
        } finally {
            if (input != null) {
                input.close();
            }
            zip.closeEntry();
        }
    }
}
