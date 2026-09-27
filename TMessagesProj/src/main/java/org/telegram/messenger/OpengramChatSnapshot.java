package org.telegram.messenger;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class OpengramChatSnapshot {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String AES_ALIAS = "opengram_force_e2e_chats";
    private static final String FILE_NAME = "opengram-e2e-chats.bin";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final byte VERSION_KEYSTORE_AES = 1;
    private static final Object lock = new Object();

    private static boolean loaded;
    private static final HashSet<String> ids = new HashSet<>();

    private OpengramChatSnapshot() {
    }

    public static boolean usesStrongBox() {
        return strongBoxKey() != null;
    }

    public static boolean contains(int account, long userId) {
        synchronized (lock) {
            ensureLoaded();
            return ids.contains(account + ":" + userId);
        }
    }

    public static boolean store(Set<String> chats) {
        synchronized (lock) {
            try {
                writeFile(encode(chats));
                ids.clear();
                if (chats != null) {
                    ids.addAll(chats);
                }
                loaded = true;
                return true;
            } catch (Exception e) {
                FileLog.e(e);
                return false;
            }
        }
    }

    public static void clear() {
        synchronized (lock) {
            File file = snapshotFile();
            if (file != null) {
                file.delete();
                new File(file.getParentFile(), FILE_NAME + ".tmp").delete();
            }
            ids.clear();
            loaded = true;
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        try {
            ids.clear();
            ids.addAll(decode(readFile()));
            loaded = true;
        } catch (Exception e) {
            FileLog.e(e);
            ids.clear();
            loaded = true;
            SharedConfig.forceEndToEndChatsSnapshotted = false;
            Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                context.getSharedPreferences("userconfing", Context.MODE_PRIVATE).edit().putBoolean("forceEndToEndChatsSnapshotted", false).apply();
            }
        }
    }

    private static byte[] encode(Set<String> chats) {
        if (chats == null || chats.isEmpty()) {
            return new byte[0];
        }
        StringBuilder builder = new StringBuilder();
        for (String chat : chats) {
            if (chat == null || chat.indexOf('\n') >= 0 || chat.indexOf('\r') >= 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(chat);
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static HashSet<String> decode(byte[] plain) {
        HashSet<String> result = new HashSet<>();
        if (plain == null || plain.length == 0) {
            return result;
        }
        String text = new String(plain, StandardCharsets.UTF_8);
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i != text.length() && text.charAt(i) != '\n') {
                continue;
            }
            String line = text.substring(start, i).trim();
            if (!line.isEmpty()) {
                result.add(line);
            }
            start = i + 1;
        }
        return result;
    }

    private static void writeFile(byte[] plain) throws Exception {
        SecretKey key = strongBoxKey();
        byte[] payload = key == null ? plain : encrypt(plain, key);
        File dest = snapshotFile();
        if (dest == null) {
            throw new IllegalStateException("no snapshot directory");
        }
        File tmp = new File(dest.getParentFile(), FILE_NAME + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(payload);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (dest.exists() && !dest.delete()) {
            tmp.delete();
            throw new IllegalStateException("snapshot replace failed");
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new IllegalStateException("snapshot rename failed");
        }
    }

    private static byte[] readFile() throws Exception {
        File dest = snapshotFile();
        if (dest == null || !dest.exists()) {
            return new byte[0];
        }
        byte[] payload = readFully(dest);
        if (payload.length == 0 || payload[0] != VERSION_KEYSTORE_AES) {
            return payload;
        }
        if (Build.VERSION.SDK_INT < 31) {
            throw new IllegalStateException("api 31 required");
        }
        return decrypt(payload);
    }

    @RequiresApi(31)
    private static byte[] encrypt(byte[] plain, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length == 0 || iv.length > 255) {
            throw new IllegalStateException("missing snapshot iv");
        }
        byte[] encrypted = cipher.doFinal(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream(2 + iv.length + encrypted.length);
        out.write(VERSION_KEYSTORE_AES);
        out.write(iv.length);
        out.write(iv);
        out.write(encrypted);
        return out.toByteArray();
    }

    @RequiresApi(31)
    private static byte[] decrypt(byte[] payload) throws Exception {
        if (payload.length < 2 + GCM_IV_LENGTH + 16) {
            throw new IllegalStateException("short snapshot");
        }
        int ivLength = payload[1] & 0xff;
        if (ivLength == 0 || payload.length < 2 + ivLength + 16) {
            throw new IllegalStateException("short snapshot");
        }
        byte[] iv = new byte[ivLength];
        System.arraycopy(payload, 2, iv, 0, ivLength);
        SecretKey key = strongBoxKey();
        if (key == null) {
            throw new IllegalStateException("strongbox key missing");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(payload, 2 + ivLength, payload.length - 2 - ivLength);
    }

    private static SecretKey strongBoxKey() {
        if (Build.VERSION.SDK_INT < 31 || !hasStrongBoxFeature()) {
            return null;
        }
        try {
            return loadOrCreateStrongBoxKey();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    @RequiresApi(31)
    private static SecretKey loadOrCreateStrongBoxKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(AES_ALIAS)) {
            SecretKey existing = (SecretKey) keyStore.getKey(AES_ALIAS, null);
            if (isStrongBoxKey(existing)) {
                return existing;
            }
            keyStore.deleteEntry(AES_ALIAS);
        }
        try {
            return generateStrongBoxKey();
        } catch (Exception e) {
            if (keyStore.containsAlias(AES_ALIAS)) {
                keyStore.deleteEntry(AES_ALIAS);
            }
            throw e;
        }
    }

    @RequiresApi(31)
    private static SecretKey generateStrongBoxKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(true);
        generator.init(builder.build());
        return generator.generateKey();
    }

    @RequiresApi(31)
    private static boolean isStrongBoxKey(SecretKey key) throws Exception {
        KeyInfo info = (KeyInfo) javax.crypto.SecretKeyFactory.getInstance(key.getAlgorithm(), ANDROID_KEYSTORE).getKeySpec(key, KeyInfo.class);
        return info.getSecurityLevel() == KeyProperties.SECURITY_LEVEL_STRONGBOX;
    }

    private static boolean hasStrongBoxFeature() {
        if (Build.VERSION.SDK_INT < 31) {
            return false;
        }
        Context context = ApplicationLoader.applicationContext;
        return context != null && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
    }

    private static File snapshotFile() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        return new File(context.getNoBackupFilesDir(), FILE_NAME);
    }

    private static byte[] readFully(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
