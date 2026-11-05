package org.telegram.messenger;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class OpengramRemoteDeletionExport {

    private OpengramRemoteDeletionExport() {
    }

    public static int write(int account, OutputStream destination) throws Exception {
        OpengramRemoteDeletion.KeptMessages kept = OpengramRemoteDeletion.kept(account);
        ZipOutputStream zip = new ZipOutputStream(destination);
        int written = 0;
        try {
            for (int i = 0; i < kept.messages.size(); i++) {
                long dialogId = kept.messages.keyAt(i);
                java.util.ArrayList<Integer> ids = kept.messages.valueAt(i);
                if (ids == null) {
                    continue;
                }
                for (int j = 0; j < ids.size(); j++) {
                    Integer messageId = ids.get(j);
                    if (messageId == null || messageId == 0) {
                        continue;
                    }
                    TLRPC.Message message = MessagesStorage.getInstance(account).getMessage(dialogId, messageId);
                    if (message == null) {
                        continue;
                    }
                    File media = mediaFile(account, message);
                    if (media != null) {
                        writeStored(zip, entryName(dialogId, messageId, extension(message, media)), media);
                    } else {
                        byte[] json = textJson(message, dialogId, messageId).getBytes(StandardCharsets.UTF_8);
                        ZipEntry entry = new ZipEntry(entryName(dialogId, messageId, ".json"));
                        zip.putNextEntry(entry);
                        zip.write(json);
                        zip.closeEntry();
                    }
                    written++;
                }
            }
        } finally {
            zip.finish();
        }
        return written;
    }

    public static String entryName(long dialogId, int messageId, String extension) {
        String ext = extension == null || extension.isEmpty() ? ".json" : extension;
        if (ext.charAt(0) != '.') {
            ext = "." + ext;
        }
        return dialogId + "/" + messageId + ext;
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
        String media = mediaKind(message);
        if (media != null) {
            object.put("media", media);
        }
        return object.toString();
    }

    private static File mediaFile(int account, TLRPC.Message message) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (!(media instanceof TLRPC.TL_messageMediaPhoto) && !(media instanceof TLRPC.TL_messageMediaDocument)) {
            return null;
        }
        File file = FileLoader.getInstance(account).getPathToMessage(message, false);
        if (file == null || !file.isFile() || file.length() <= 0) {
            return null;
        }
        return file;
    }

    private static String mediaKind(TLRPC.Message message) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return "photo";
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

    private static void writeStored(ZipOutputStream zip, String name, File file) throws Exception {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[8192];
        FileInputStream input = new FileInputStream(file);
        try {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    crc.update(buffer, 0, count);
                }
            }
        } finally {
            input.close();
        }
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(file.length());
        entry.setCompressedSize(file.length());
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        input = new FileInputStream(file);
        try {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    zip.write(buffer, 0, count);
                }
            }
        } finally {
            input.close();
        }
        zip.closeEntry();
    }
}
