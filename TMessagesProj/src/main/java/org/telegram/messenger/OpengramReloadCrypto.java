package org.telegram.messenger;

import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sealed session file for Opengram Reload.
 *
 * <p>The plaintext is always 256 KiB. A short prefix holds the permanent
 * auth key, its id, the datacenter, and the user object. The rest is random,
 * so every file is the same length. The file header is AES-GCM additional
 * data. The iteration count is accepted only when it is the value this
 * writer uses: a lower count would weaken the passphrase, and a higher count
 * would let a file stall the import.
 */
public final class OpengramReloadCrypto {

    public static final int AUTH_KEY_LENGTH = 256;
    public static final int MAX_USER_BLOB = 8192;
    public static final int PLAINTEXT_LENGTH = 256 * 1024;
    public static final int PBKDF2_ITERATIONS = 100000;
    public static final int SALT_LENGTH = 16;
    public static final int NONCE_LENGTH = 12;
    public static final int HEADER_LENGTH = 38;
    public static final int TAG_LENGTH = 16;
    public static final int FILE_LENGTH = HEADER_LENGTH + PLAINTEXT_LENGTH + TAG_LENGTH;

    static final byte VERSION = 1;
    private static final byte KDF_PBKDF2_SHA512 = 1;
    private static final int GCM_TAG_BITS = 128;

    public static final class Opened {
        public final int dcId;
        public final byte[] authKey;
        public final long authKeyId;
        public final long userId;
        public final TLRPC.User user;

        Opened(int dcId, byte[] authKey, long authKeyId, long userId, TLRPC.User user) {
            this.dcId = dcId;
            this.authKey = authKey;
            this.authKeyId = authKeyId;
            this.userId = userId;
            this.user = user;
        }

        public void wipe() {
            if (authKey != null) {
                Arrays.fill(authKey, (byte) 0);
            }
        }
    }

    private OpengramReloadCrypto() {
    }

    public static long authKeyId(byte[] key) {
        if (key == null || key.length != AUTH_KEY_LENGTH) {
            return 0;
        }
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1").digest(key);
            long id = 0;
            for (int i = 0; i < 8; i++) {
                id |= (sha[12 + i] & 0xffL) << (8 * i);
            }
            Arrays.fill(sha, (byte) 0);
            return id;
        } catch (Exception e) {
            return 0;
        }
    }

    public static byte[] serializeUser(TLRPC.User user) {
        if (user == null || user.id == 0) {
            return null;
        }
        SerializedData data = new SerializedData();
        try {
            user.serializeToStream(data);
            byte[] bytes = data.toByteArray();
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_USER_BLOB) {
                if (bytes != null) {
                    Arrays.fill(bytes, (byte) 0);
                }
                return null;
            }
            return bytes;
        } catch (Exception e) {
            return null;
        } finally {
            data.cleanup();
        }
    }

    public static byte[] takeDeliveredKey(byte[] delivered) {
        if (delivered == null) {
            return null;
        }
        byte[] owned = delivered.length == AUTH_KEY_LENGTH ? Arrays.copyOf(delivered, delivered.length) : null;
        Arrays.fill(delivered, (byte) 0);
        return owned;
    }

    public static byte[] seal(int dcId, byte[] authKey, long userId, byte[] userBlob, char[] passphrase, SecureRandom random) {
        if (dcId < 1 || dcId > 5 || userId <= 0 || passphrase == null || passphrase.length == 0 || random == null) {
            return null;
        }
        if (authKey == null || authKey.length != AUTH_KEY_LENGTH || allZero(authKey)) {
            return null;
        }
        TLRPC.User user = deserializeUser(userBlob);
        if (user == null || user.id != userId) {
            return null;
        }
        long keyId = authKeyId(authKey);
        byte[] plain = buildPlaintext(dcId, authKey, keyId, userId, userBlob, random);
        if (plain == null) {
            return null;
        }
        try {
            return sealPlaintext(plain, passphrase, PBKDF2_ITERATIONS, random);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    public static Opened open(byte[] file, char[] passphrase) {
        if (file == null || file.length != FILE_LENGTH || passphrase == null || passphrase.length == 0) {
            return null;
        }
        if (file[0] != 'O' || file[1] != 'G' || file[2] != 'R' || file[3] != '1') {
            return null;
        }
        int version = file[4] & 0xff;
        int kdf = file[5] & 0xff;
        int iterations = readInt(file, 6);
        if (version != VERSION || kdf != KDF_PBKDF2_SHA512 || iterations != PBKDF2_ITERATIONS) {
            return null;
        }
        byte[] salt = Arrays.copyOfRange(file, 10, 10 + SALT_LENGTH);
        byte[] nonce = Arrays.copyOfRange(file, 26, 26 + NONCE_LENGTH);
        byte[] header = Arrays.copyOf(file, HEADER_LENGTH);
        byte[] body = Arrays.copyOfRange(file, HEADER_LENGTH, file.length);
        byte[] key = null;
        byte[] plain = null;
        try {
            key = deriveKey(passphrase, salt, iterations);
            if (key == null) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(header);
            plain = cipher.doFinal(body);
            return parsePlaintext(plain);
        } catch (Exception e) {
            return null;
        } finally {
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(header, (byte) 0);
            Arrays.fill(body, (byte) 0);
        }
    }

    static byte[] buildPlaintext(int dcId, byte[] authKey, long authKeyId, long userId, byte[] userBlob, SecureRandom random) {
        if (authKey == null || authKey.length != AUTH_KEY_LENGTH || userBlob == null || userBlob.length == 0 || userBlob.length > MAX_USER_BLOB || random == null) {
            return null;
        }
        byte[] plain = new byte[PLAINTEXT_LENGTH];
        random.nextBytes(plain);
        plain[0] = 'O';
        plain[1] = 'G';
        plain[2] = 'R';
        plain[3] = 'L';
        plain[4] = VERSION;
        plain[5] = (byte) dcId;
        System.arraycopy(authKey, 0, plain, 6, AUTH_KEY_LENGTH);
        int offset = 6 + AUTH_KEY_LENGTH;
        writeLong(plain, offset, authKeyId);
        offset += 8;
        writeLong(plain, offset, userId);
        offset += 8;
        writeInt(plain, offset, userBlob.length);
        offset += 4;
        System.arraycopy(userBlob, 0, plain, offset, userBlob.length);
        return plain;
    }

    static byte[] sealPlaintext(byte[] plaintext, char[] passphrase, int iterations, SecureRandom random) {
        if (plaintext == null || plaintext.length != PLAINTEXT_LENGTH || passphrase == null || passphrase.length == 0 || iterations <= 0 || random == null) {
            return null;
        }
        byte[] salt = new byte[SALT_LENGTH];
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(salt);
        random.nextBytes(nonce);
        byte[] header = header(iterations, salt, nonce);
        byte[] key = null;
        byte[] body = null;
        try {
            key = deriveKey(passphrase, salt, iterations);
            if (key == null) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(header);
            body = cipher.doFinal(plaintext);
            if (body.length != PLAINTEXT_LENGTH + TAG_LENGTH) {
                return null;
            }
            byte[] file = new byte[HEADER_LENGTH + body.length];
            System.arraycopy(header, 0, file, 0, HEADER_LENGTH);
            System.arraycopy(body, 0, file, HEADER_LENGTH, body.length);
            return file;
        } catch (Exception e) {
            return null;
        } finally {
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
            if (body != null) {
                Arrays.fill(body, (byte) 0);
            }
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(header, (byte) 0);
        }
    }

    static Opened parsePlaintext(byte[] plain) {
        if (plain == null || plain.length != PLAINTEXT_LENGTH) {
            return null;
        }
        if (plain[0] != 'O' || plain[1] != 'G' || plain[2] != 'R' || plain[3] != 'L') {
            return null;
        }
        int version = plain[4] & 0xff;
        int dcId = plain[5] & 0xff;
        if (version != VERSION || dcId < 1 || dcId > 5) {
            return null;
        }
        byte[] authKey = Arrays.copyOfRange(plain, 6, 6 + AUTH_KEY_LENGTH);
        int offset = 6 + AUTH_KEY_LENGTH;
        long storedId = readLong(plain, offset);
        offset += 8;
        long userId = readLong(plain, offset);
        offset += 8;
        int blobLength = readInt(plain, offset);
        offset += 4;
        if (allZero(authKey) || storedId != authKeyId(authKey) || userId <= 0 || blobLength <= 0 || blobLength > MAX_USER_BLOB || offset + blobLength > plain.length) {
            Arrays.fill(authKey, (byte) 0);
            return null;
        }
        byte[] blob = Arrays.copyOfRange(plain, offset, offset + blobLength);
        TLRPC.User user = deserializeUser(blob);
        Arrays.fill(blob, (byte) 0);
        if (user == null || user.id != userId) {
            Arrays.fill(authKey, (byte) 0);
            return null;
        }
        return new Opened(dcId, authKey, storedId, userId, user);
    }

    private static TLRPC.User deserializeUser(byte[] blob) {
        if (blob == null || blob.length < 4) {
            return null;
        }
        SerializedData data = new SerializedData(blob);
        try {
            int constructor = data.readInt32(false);
            if (constructor == 0) {
                return null;
            }
            TLRPC.User user = TLRPC.User.TLdeserialize(data, constructor, false);
            if (user == null || user.id == 0) {
                return null;
            }
            return user;
        } catch (Exception e) {
            return null;
        } finally {
            data.cleanup();
        }
    }

    private static byte[] deriveKey(char[] passphrase, byte[] salt, int iterations) {
        byte[] password = null;
        try {
            password = passphraseBytes(passphrase);
            if (password == null) {
                return null;
            }
            return pbkdf2(password, salt, iterations, 32);
        } catch (Exception e) {
            return null;
        } finally {
            if (password != null) {
                Arrays.fill(password, (byte) 0);
            }
        }
    }

    private static byte[] passphraseBytes(char[] passphrase) {
        boolean ascii = true;
        for (int i = 0; i < passphrase.length; i++) {
            if (passphrase[i] > 0x7f) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            byte[] bytes = new byte[passphrase.length];
            for (int i = 0; i < passphrase.length; i++) {
                bytes[i] = (byte) passphrase[i];
            }
            return bytes;
        }
        String normalized = java.text.Normalizer.normalize(new String(passphrase), java.text.Normalizer.Form.NFKD);
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        return bytes;
    }

    static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(password, "HmacSHA512"));
        int hashLength = mac.getMacLength();
        int blocks = (length + hashLength - 1) / hashLength;
        byte[] result = new byte[blocks * hashLength];
        byte[] block = new byte[salt.length + 4];
        System.arraycopy(salt, 0, block, 0, salt.length);
        for (int i = 1; i <= blocks; i++) {
            block[salt.length] = (byte) (i >>> 24);
            block[salt.length + 1] = (byte) (i >>> 16);
            block[salt.length + 2] = (byte) (i >>> 8);
            block[salt.length + 3] = (byte) i;
            byte[] u = mac.doFinal(block);
            byte[] t = Arrays.copyOf(u, u.length);
            for (int iteration = 1; iteration < iterations; iteration++) {
                byte[] next = mac.doFinal(u);
                Arrays.fill(u, (byte) 0);
                u = next;
                for (int k = 0; k < t.length; k++) {
                    t[k] ^= u[k];
                }
            }
            System.arraycopy(t, 0, result, (i - 1) * hashLength, hashLength);
            Arrays.fill(u, (byte) 0);
            Arrays.fill(t, (byte) 0);
        }
        byte[] key = Arrays.copyOf(result, length);
        Arrays.fill(result, (byte) 0);
        Arrays.fill(block, (byte) 0);
        return key;
    }

    private static byte[] header(int iterations, byte[] salt, byte[] nonce) {
        byte[] header = new byte[HEADER_LENGTH];
        header[0] = 'O';
        header[1] = 'G';
        header[2] = 'R';
        header[3] = '1';
        header[4] = VERSION;
        header[5] = KDF_PBKDF2_SHA512;
        writeInt(header, 6, iterations);
        System.arraycopy(salt, 0, header, 10, SALT_LENGTH);
        System.arraycopy(nonce, 0, header, 26, NONCE_LENGTH);
        return header;
    }

    private static boolean allZero(byte[] key) {
        int acc = 0;
        for (int i = 0; i < key.length; i++) {
            acc |= key[i];
        }
        return acc == 0;
    }

    private static void writeInt(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
    }

    private static void writeLong(byte[] out, int offset, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out[offset++] = (byte) (value >>> shift);
        }
    }

    private static int readInt(byte[] in, int offset) {
        return ((in[offset] & 0xff) << 24) | ((in[offset + 1] & 0xff) << 16) | ((in[offset + 2] & 0xff) << 8) | (in[offset + 3] & 0xff);
    }

    private static long readLong(byte[] in, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (in[offset + i] & 0xffL);
        }
        return value;
    }
}
