package org.telegram.messenger;

import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpengramReloadTest {

    private static final String ABANDON = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    @Test
    public void pbkdf2MatchesTheSha512Vector() throws Exception {
        byte[] key = OpengramReloadCrypto.pbkdf2("password".getBytes("UTF-8"), "salt".getBytes("UTF-8"), 1, 32);
        assertEquals("867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252", hex(key));
        Arrays.fill(key, (byte) 0);
        key = OpengramReloadCrypto.pbkdf2("password".getBytes("UTF-8"), "salt".getBytes("UTF-8"), 2, 32);
        assertEquals("e1d9c16aa681708a45f5c7c4e215ceb66e011a2e9f0040713f18aefdb866d53c", hex(key));
        Arrays.fill(key, (byte) 0);
    }

    @Test
    public void zeroEntropyIsTheAbandonVector() {
        OpengramReloadWords.Phrase phrase = OpengramReloadWords.fromEntropy(new byte[16]);
        assertNotNull(phrase);
        assertEquals(ABANDON, new String(phrase.toPassphrase()));
        assertNotNull(OpengramReloadWords.parse(ABANDON));
        assertNotNull(OpengramReloadWords.parse("  " + ABANDON.toUpperCase() + "  "));
    }

    @Test
    public void checksumAndUnknownWordsAreRejected() {
        assertNull(OpengramReloadWords.parse("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"));
        assertNull(OpengramReloadWords.parse("notaword abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"));
        assertNull(OpengramReloadWords.parse("abandon abandon abandon about"));
        assertNull(OpengramReloadWords.parse((String) null));
        assertNull(OpengramReloadWords.parse((char[]) null));
    }

    @Test
    public void quizPositionsAreFourDistinctIndexes() {
        int[] positions = OpengramReloadWords.quizPositions(new SecureRandom());
        assertEquals(OpengramReloadWords.QUIZ_COUNT, positions.length);
        HashSet<Integer> seen = new HashSet<>();
        for (int position : positions) {
            assertTrue(position >= 0 && position < OpengramReloadWords.WORD_COUNT);
            assertTrue(seen.add(position));
        }
    }

    @Test
    public void sealedFileHasAFixedLengthAndRoundTrips() {
        byte[] key = key();
        byte[] userBlob = userBlob(42);
        char[] passphrase = passphrase();
        byte[] file = OpengramReloadCrypto.seal(2, key, 42, userBlob, passphrase, new SecureRandom());
        assertNotNull(file);
        assertEquals(OpengramReloadCrypto.FILE_LENGTH, file.length);
        OpengramReloadCrypto.Opened opened = OpengramReloadCrypto.open(file, passphrase);
        assertNotNull(opened);
        assertEquals(2, opened.dcId);
        assertEquals(42L, opened.userId);
        assertEquals(42L, opened.user.id);
        assertTrue(Arrays.equals(key, opened.authKey));
        assertEquals(OpengramReloadCrypto.authKeyId(key), opened.authKeyId);
        opened.wipe();
    }

    @Test
    public void deliveredKeySurvivesAsyncUiDispatchAndRoundTrips() {
        byte[] delivered = key();
        byte[] owned = OpengramReloadCrypto.takeDeliveredKey(delivered);
        byte[][] seen = new byte[1][];
        Runnable posted = () -> seen[0] = owned;
        Arrays.fill(delivered, (byte) 0);
        posted.run();
        assertNotNull(seen[0]);
        assertFalse(allZero(seen[0]));
        assertTrue(allZero(delivered));
        assertEquals(1, seen[0][0]);
        char[] passphrase = passphrase();
        byte[] user = userBlob(42);
        byte[] file = OpengramReloadCrypto.seal(2, seen[0], 42, user, passphrase, new SecureRandom());
        assertNotNull(file);
        OpengramReloadCrypto.Opened opened = OpengramReloadCrypto.open(file, passphrase);
        assertNotNull(opened);
        assertTrue(Arrays.equals(seen[0], opened.authKey));
        assertFalse(allZero(opened.authKey));
        opened.wipe();
        Arrays.fill(seen[0], (byte) 0);
        Arrays.fill(passphrase, '\0');
        Arrays.fill(user, (byte) 0);
    }

    @Test
    public void tamperedBodyAndTruncatedFileFailClosed() {
        byte[] file = sealed();
        file[file.length - 1] ^= 0x11;
        assertNull(OpengramReloadCrypto.open(file, passphrase()));
        assertNull(OpengramReloadCrypto.open(Arrays.copyOf(sealed(), OpengramReloadCrypto.FILE_LENGTH - 1), passphrase()));
        assertNull(OpengramReloadCrypto.open(null, passphrase()));
    }

    @Test
    public void lowIterationCountIsRejected() {
        byte[] plain = OpengramReloadCrypto.buildPlaintext(2, key(), OpengramReloadCrypto.authKeyId(key()), 42, userBlob(42), new SecureRandom());
        byte[] file = OpengramReloadCrypto.sealPlaintext(plain, passphrase(), 1000, new SecureRandom());
        Arrays.fill(plain, (byte) 0);
        assertNotNull(file);
        assertNull(OpengramReloadCrypto.open(file, passphrase()));
    }

    @Test
    public void swappedHeaderFailsClosed() {
        byte[] file = sealed();
        file[10] ^= 0x5a;
        assertNull(OpengramReloadCrypto.open(file, passphrase()));
        file = sealed();
        file[26] ^= 0x5a;
        assertNull(OpengramReloadCrypto.open(file, passphrase()));
    }

    @Test
    public void wrongDatacenterAndMismatchedKeyIdFailClosed() {
        byte[] key = key();
        byte[] plain = OpengramReloadCrypto.buildPlaintext(9, key, OpengramReloadCrypto.authKeyId(key), 42, userBlob(42), new SecureRandom());
        assertNull(OpengramReloadCrypto.parsePlaintext(plain));
        Arrays.fill(plain, (byte) 0);

        plain = OpengramReloadCrypto.buildPlaintext(2, key, OpengramReloadCrypto.authKeyId(key) ^ 1L, 42, userBlob(42), new SecureRandom());
        assertNull(OpengramReloadCrypto.parsePlaintext(plain));
        Arrays.fill(plain, (byte) 0);

        plain = OpengramReloadCrypto.buildPlaintext(2, key, OpengramReloadCrypto.authKeyId(key), 99, userBlob(42), new SecureRandom());
        assertNull(OpengramReloadCrypto.parsePlaintext(plain));
        Arrays.fill(plain, (byte) 0);

        byte[] garbage = new byte[32];
        Arrays.fill(garbage, (byte) 7);
        plain = OpengramReloadCrypto.buildPlaintext(2, key, OpengramReloadCrypto.authKeyId(key), 42, garbage, new SecureRandom());
        assertNull(OpengramReloadCrypto.parsePlaintext(plain));
        Arrays.fill(plain, (byte) 0);
        assertNull(OpengramReloadCrypto.seal(0, key, 42, userBlob(42), passphrase(), new SecureRandom()));
        assertNull(OpengramReloadCrypto.seal(2, new byte[OpengramReloadCrypto.AUTH_KEY_LENGTH], 42, userBlob(42), passphrase(), new SecureRandom()));
    }

    @Test
    public void wrongPassphraseFailsClosed() {
        byte[] entropy = new byte[16];
        entropy[0] = 1;
        char[] wrong = OpengramReloadWords.fromEntropy(entropy).toPassphrase();
        assertNull(OpengramReloadCrypto.open(sealed(), wrong));
        Arrays.fill(wrong, '\0');
        Arrays.fill(entropy, (byte) 0);
    }

    private static byte[] sealed;

    private static byte[] sealed() {
        if (sealed == null) {
            sealed = OpengramReloadCrypto.seal(2, key(), 42, userBlob(42), passphrase(), new SecureRandom());
            assertNotNull(sealed);
        }
        return sealed.clone();
    }

    private static boolean allZero(byte[] key) {
        int acc = 0;
        for (int i = 0; i < key.length; i++) {
            acc |= key[i];
        }
        return acc == 0;
    }

    private static byte[] key() {
        byte[] key = new byte[OpengramReloadCrypto.AUTH_KEY_LENGTH];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    private static char[] passphrase() {
        return OpengramReloadWords.fromEntropy(new byte[16]).toPassphrase();
    }

    private static String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (int i = 0; i < value.length; i++) {
            builder.append(Character.forDigit((value[i] >> 4) & 0xf, 16));
            builder.append(Character.forDigit(value[i] & 0xf, 16));
        }
        return builder.toString();
    }

    private static byte[] userBlob(long userId) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = userId;
        byte[] blob = OpengramReloadCrypto.serializeUser(user);
        assertNotNull(blob);
        return blob;
    }
}
