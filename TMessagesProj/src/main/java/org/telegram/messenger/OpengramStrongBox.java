package org.telegram.messenger;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import androidx.annotation.RequiresApi;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * StrongBox AES-GCM sealer. A failed key operation throws. It never reports
 * "unsupported" for a keystore error, so callers cannot treat a transient
 * failure as permission to write plaintext.
 */
public final class OpengramStrongBox implements OpengramSnapshotFile.Sealer {

    public enum Probe {
        UNSUPPORTED,
        AVAILABLE,
        ERROR
    }

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "opengram_force_e2e_chats";
    private static final int GCM_TAG_BITS = 128;

    public static final OpengramStrongBox SEALER = new OpengramStrongBox();

    private OpengramStrongBox() {
    }

    public static Probe probe() {
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
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreate(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(sealed, 1 + ivLength, sealed.length - 1 - ivLength);
    }

    @RequiresApi(31)
    private static SecretKey loadOrCreate() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) {
            SecretKey existing = (SecretKey) keyStore.getKey(ALIAS, null);
            if (isStrongBox(existing)) {
                return existing;
            }
            keyStore.deleteEntry(ALIAS);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(true)
            .build());
        return generator.generateKey();
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
