package org.telegram.messenger;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import androidx.annotation.RequiresApi;

import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Enumeration;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class OpengramStrongBox implements OpengramSnapshotFile.Sealer {

    public enum Probe {
        UNSUPPORTED,
        AVAILABLE,
        ERROR
    }

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String E2E_ALIAS = "opengram_force_e2e_chats";
    private static final String GHOST_ALIAS_PREFIX = "opengram_ghost_";
    private static final String FILTER_ALIAS_PREFIX = "opengram_filter_";
    private static final int GCM_TAG_BITS = 128;
    private static final Object keystoreLock = new Object();

    public static final OpengramStrongBox SEALER = new OpengramStrongBox(E2E_ALIAS);

    private final String alias;

    private OpengramStrongBox(String alias) {
        this.alias = alias;
    }

    public static OpengramStrongBox forGhost(long clientUserId) {
        if (clientUserId <= 0) {
            throw new IllegalArgumentException("client user id required");
        }
        return new OpengramStrongBox(GHOST_ALIAS_PREFIX + clientUserId);
    }

    public static OpengramStrongBox forFilter(long clientUserId) {
        if (clientUserId <= 0) {
            throw new IllegalArgumentException("client user id required");
        }
        return new OpengramStrongBox(FILTER_ALIAS_PREFIX + clientUserId);
    }

    public static Probe probe() {
        return SEALER.probeKey();
    }

    String alias() {
        return alias;
    }

    public Probe probeKey() {
        if (Build.VERSION.SDK_INT < 31 || !hasFeature()) {
            return Probe.UNSUPPORTED;
        }
        try {
            SecretKey key = loadOrCreate();
            return isStrongBox(key) ? Probe.AVAILABLE : Probe.ERROR;
        } catch (Exception e) {
            FileLog.e(e);
            return Probe.ERROR;
        }
    }

    public Probe inspect() {
        if (Build.VERSION.SDK_INT < 31 || !hasFeature()) {
            return Probe.UNSUPPORTED;
        }
        try {
            SecretKey key = loadExisting();
            if (key == null) {
                return Probe.AVAILABLE;
            }
            return isStrongBox(key) ? Probe.AVAILABLE : Probe.ERROR;
        } catch (Exception e) {
            FileLog.e(e);
            return Probe.ERROR;
        }
    }

    public void deleteKey() {
        if (Build.VERSION.SDK_INT < 31 || !(alias.startsWith(GHOST_ALIAS_PREFIX) || alias.startsWith(FILTER_ALIAS_PREFIX))) {
            return;
        }
        synchronized (keystoreLock) {
            deleteAlias(alias);
        }
    }

    public static void deleteAllGhostKeys() {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        synchronized (keystoreLock) {
            try {
                KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
                keyStore.load(null);
                ArrayList<String> aliases = new ArrayList<>();
                Enumeration<String> existing = keyStore.aliases();
                while (existing.hasMoreElements()) {
                    String alias = existing.nextElement();
                    if (alias != null && alias.startsWith(GHOST_ALIAS_PREFIX)) {
                        aliases.add(alias);
                    }
                }
                for (int i = 0; i < aliases.size(); i++) {
                    keyStore.deleteEntry(aliases.get(i));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private static void deleteAlias(String alias) {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public byte[] seal(byte[] aad, byte[] body) throws Exception {
        if (Build.VERSION.SDK_INT < 31) {
            throw new IllegalStateException("strongbox requires api 31");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreate());
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length == 0 || iv.length > 255) {
            throw new IllegalStateException("missing snapshot iv");
        }
        byte[] encrypted = cipher.doFinal(body);
        byte[] stored = new byte[1 + iv.length + encrypted.length];
        stored[0] = (byte) iv.length;
        System.arraycopy(iv, 0, stored, 1, iv.length);
        System.arraycopy(encrypted, 0, stored, 1 + iv.length, encrypted.length);
        return stored;
    }

    @Override
    public byte[] open(byte[] aad, byte[] sealed) throws Exception {
        if (Build.VERSION.SDK_INT < 31) {
            throw new IllegalStateException("strongbox requires api 31");
        }
        if (sealed == null || sealed.length < 1 + 12 + 16) {
            throw new IllegalStateException("short snapshot");
        }
        int ivLength = sealed[0] & 0xff;
        if (ivLength == 0 || sealed.length < 1 + ivLength + 16) {
            throw new IllegalStateException("short snapshot");
        }
        byte[] iv = new byte[ivLength];
        System.arraycopy(sealed, 1, iv, 0, ivLength);
        SecretKey key = loadExisting();
        if (key == null) {
            throw new IllegalStateException("missing strongbox key");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(sealed, 1 + ivLength, sealed.length - 1 - ivLength);
    }

    @RequiresApi(31)
    private SecretKey loadExisting() throws Exception {
        synchronized (keystoreLock) {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(alias)) {
                return null;
            }
            return (SecretKey) keyStore.getKey(alias, null);
        }
    }

    @RequiresApi(31)
    private SecretKey loadOrCreate() throws Exception {
        synchronized (keystoreLock) {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(alias)) {
                SecretKey existing = (SecretKey) keyStore.getKey(alias, null);
                if (isStrongBox(existing)) {
                    return existing;
                }
                keyStore.deleteEntry(alias);
            }
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(true)
                .build());
            return generator.generateKey();
        }
    }

    @RequiresApi(31)
    private static boolean isStrongBox(SecretKey key) throws Exception {
        KeyInfo info = (KeyInfo) javax.crypto.SecretKeyFactory.getInstance(key.getAlgorithm(), ANDROID_KEYSTORE).getKeySpec(key, KeyInfo.class);
        return info.getSecurityLevel() == KeyProperties.SECURITY_LEVEL_STRONGBOX;
    }

    private static boolean hasFeature() {
        if (Build.VERSION.SDK_INT < 31) {
            return false;
        }
        Context context = ApplicationLoader.applicationContext;
        return context != null && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
    }
}
