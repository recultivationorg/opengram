package org.telegram.messenger;

import org.junit.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpengramClientFingerprintTest {

    @Test
    public void existingInstallKeepsPixel8AtUtc() {
        MapStore store = new MapStore();
        OpengramClientFingerprint.Identity identity = OpengramClientFingerprint.resolve(store, true, new Random(1), january());
        assertEquals(OpengramClientFingerprint.LEGACY_MODEL, identity.deviceModel);
        assertEquals(OpengramClientFingerprint.LEGACY_OFFSET, identity.timezoneOffset);
        assertEquals(OpengramClientFingerprint.LEGACY_MODEL, store.get(OpengramClientFingerprint.KEY_MODEL));
        assertEquals("0", store.get(OpengramClientFingerprint.KEY_TIMEZONE));

        OpengramClientFingerprint.Identity again = OpengramClientFingerprint.resolve(store, false, new Random(2), july());
        assertEquals(OpengramClientFingerprint.LEGACY_MODEL, again.deviceModel);
        assertEquals(0, again.timezoneOffset);
    }

    @Test
    public void newInstallDrawsOnceAndKeepsThePair() {
        MapStore store = new MapStore();
        OpengramClientFingerprint.Identity identity = OpengramClientFingerprint.resolve(store, false, new Sequence(0, 0), january());
        assertTrue(Arrays.asList(OpengramClientFingerprint.MODELS).contains(identity.deviceModel));
        assertTrue(Arrays.asList(OpengramClientFingerprint.ZONES).contains(store.get(OpengramClientFingerprint.KEY_TIMEZONE)));
        assertEquals(OpengramClientFingerprint.MODELS[0], identity.deviceModel);
        assertEquals(OpengramClientFingerprint.ZONES[0], store.get(OpengramClientFingerprint.KEY_TIMEZONE));
        assertEquals(OpengramClientFingerprint.offsetOf(OpengramClientFingerprint.ZONES[0], january()), identity.timezoneOffset);

        OpengramClientFingerprint.Identity again = OpengramClientFingerprint.resolve(store, false, new Sequence(4, 4), july());
        assertEquals(identity.deviceModel, again.deviceModel);
        assertEquals(OpengramClientFingerprint.ZONES[0], store.get(OpengramClientFingerprint.KEY_TIMEZONE));
        assertEquals(OpengramClientFingerprint.offsetOf(OpengramClientFingerprint.ZONES[0], july()), again.timezoneOffset);
    }

    @Test
    public void rerollReplacesTheStoredPair() {
        MapStore store = new MapStore();
        OpengramClientFingerprint.resolve(store, false, new Sequence(0, 0), january());
        OpengramClientFingerprint.Identity rolled = OpengramClientFingerprint.reroll(store, new Sequence(1, 1), january());
        assertEquals(OpengramClientFingerprint.MODELS[1], rolled.deviceModel);
        assertEquals(OpengramClientFingerprint.ZONES[1], store.get(OpengramClientFingerprint.KEY_TIMEZONE));
        assertNotEquals(OpengramClientFingerprint.MODELS[0], rolled.deviceModel);
        assertNotEquals(OpengramClientFingerprint.ZONES[0], store.get(OpengramClientFingerprint.KEY_TIMEZONE));
    }

    @Test
    public void importedAccountsKeepSeparateIdentities() {
        MapStore install = new MapStore();
        OpengramClientFingerprint.resolve(install, true, new Random(1), january());
        MapStore first = new MapStore();
        MapStore second = new MapStore();
        first.putAccount("samsungSM-S928B", "Europe/Berlin");
        second.putAccount("GooglePixel 11", "Asia/Tokyo");

        OpengramClientFingerprint.Identity one = OpengramClientFingerprint.resolveAccount(first, install, true, new Random(3), january());
        OpengramClientFingerprint.Identity two = OpengramClientFingerprint.resolveAccount(second, install, true, new Random(4), january());
        assertEquals("samsungSM-S928B", one.deviceModel);
        assertEquals("Europe/Berlin", one.timezone);
        assertEquals(3600, one.timezoneOffset);
        assertEquals("GooglePixel 11", two.deviceModel);
        assertEquals("Asia/Tokyo", two.timezone);

        install.putAccount("GooglePixel 9", "Europe/London");
        assertEquals("GooglePixel 9", OpengramClientFingerprint.resolveAccount(install, install, true, new Random(5), january()).deviceModel);
        assertEquals("GooglePixel 8", install.get(OpengramClientFingerprint.KEY_MODEL));

        MapStore fresh = new MapStore();
        OpengramClientFingerprint.Identity drawn = OpengramClientFingerprint.resolveAccount(fresh, install, false, new Sequence(3, 2), january());
        assertEquals(OpengramClientFingerprint.MODELS[3], drawn.deviceModel);
        assertEquals(OpengramClientFingerprint.ZONES[2], fresh.get(OpengramClientFingerprint.KEY_ACCOUNT_TIMEZONE));
        assertNull(fresh.get(OpengramClientFingerprint.KEY_MODEL));
        assertEquals("samsungSM-S928B", first.get(OpengramClientFingerprint.KEY_ACCOUNT_MODEL));
        assertEquals("GooglePixel 11", second.get(OpengramClientFingerprint.KEY_ACCOUNT_MODEL));
        assertEquals("GooglePixel 8", install.get(OpengramClientFingerprint.KEY_MODEL));

        MapStore migrated = new MapStore();
        OpengramClientFingerprint.Identity copied = OpengramClientFingerprint.resolveAccount(migrated, install, true, new Sequence(1, 1), january());
        assertEquals("GooglePixel 8", copied.deviceModel);
        assertEquals("0", migrated.get(OpengramClientFingerprint.KEY_ACCOUNT_TIMEZONE));
        install.putPair("samsungSM-S938B", "Asia/Tokyo");
        OpengramClientFingerprint.Identity kept = OpengramClientFingerprint.resolveAccount(migrated, install, true, new Sequence(4, 4), july());
        assertEquals("GooglePixel 8", kept.deviceModel);
        assertEquals("0", kept.timezone);
        assertEquals("GooglePixel 11", OpengramClientFingerprint.resolveAccount(second, install, true, new Random(8), january()).deviceModel);
    }

    @Test
    public void sharedInstallIdentityIsNotKeptByAnotherAccount() {
        MapStore install = new MapStore();
        install.putPair(OpengramClientFingerprint.LEGACY_MODEL, "0");
        MapStore leaked = new MapStore();
        leaked.putAccount(OpengramClientFingerprint.LEGACY_MODEL, "0");
        java.util.Set<String> reserved = new java.util.HashSet<>();
        reserved.add(OpengramClientFingerprint.LEGACY_MODEL + "\n0");
        OpengramClientFingerprint.Identity identity = OpengramClientFingerprint.resolveAccount(leaked, install, false, new Sequence(4, 2), january(), reserved);
        assertEquals(OpengramClientFingerprint.MODELS[4], identity.deviceModel);
        assertEquals(OpengramClientFingerprint.ZONES[2], identity.timezone);
        assertEquals(OpengramClientFingerprint.LEGACY_MODEL, install.get(OpengramClientFingerprint.KEY_MODEL));
        assertEquals("0", install.get(OpengramClientFingerprint.KEY_TIMEZONE));

        MapStore original = new MapStore();
        original.putPair(OpengramClientFingerprint.LEGACY_MODEL, "0");
        original.putAccount(OpengramClientFingerprint.LEGACY_MODEL, "0");
        OpengramClientFingerprint.Identity kept = OpengramClientFingerprint.resolveAccount(original, original, true, new Sequence(1, 1), january());
        assertEquals(OpengramClientFingerprint.LEGACY_MODEL, kept.deviceModel);
        assertEquals("0", kept.timezone);
    }

    @Test
    public void unactivatedAccountDrawsItsOwnFingerprint() {
        MapStore install = new MapStore();
        install.putPair(OpengramClientFingerprint.LEGACY_MODEL, "0");
        MapStore slot = new MapStore();
        OpengramClientFingerprint.Identity identity = OpengramClientFingerprint.resolveAccount(slot, install, false, new Sequence(5, 1), january());
        assertEquals(OpengramClientFingerprint.MODELS[5], identity.deviceModel);
        assertEquals(OpengramClientFingerprint.ZONES[1], identity.timezone);
        assertEquals(OpengramClientFingerprint.LEGACY_MODEL, install.get(OpengramClientFingerprint.KEY_MODEL));
        assertNull(install.get(OpengramClientFingerprint.KEY_ACCOUNT_MODEL));
        assertEquals(OpengramClientFingerprint.MODELS[5], slot.get(OpengramClientFingerprint.KEY_ACCOUNT_MODEL));
    }

    @Test
    public void berlinOffsetFollowsDaylightSaving() {
        assertEquals(3600, OpengramClientFingerprint.offsetOf("Europe/Berlin", january()));
        assertEquals(7200, OpengramClientFingerprint.offsetOf("Europe/Berlin", july()));
        assertEquals(0, OpengramClientFingerprint.offsetOf("0", july()));
    }

    private static long january() {
        return Instant.parse("2026-01-15T12:00:00Z").toEpochMilli();
    }

    private static long july() {
        return Instant.parse("2026-07-15T12:00:00Z").toEpochMilli();
    }

    private static final class MapStore implements OpengramClientFingerprint.Store {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void putPair(String model, String timezone) {
            values.put(OpengramClientFingerprint.KEY_MODEL, model);
            values.put(OpengramClientFingerprint.KEY_TIMEZONE, timezone);
        }

        @Override
        public void putAccount(String model, String timezone) {
            values.put(OpengramClientFingerprint.KEY_ACCOUNT_MODEL, model);
            values.put(OpengramClientFingerprint.KEY_ACCOUNT_TIMEZONE, timezone);
        }

        @Override
        public void removeAccount() {
            values.remove(OpengramClientFingerprint.KEY_ACCOUNT_MODEL);
            values.remove(OpengramClientFingerprint.KEY_ACCOUNT_TIMEZONE);
        }
    }

    private static final class Sequence extends Random {
        private final int[] values;
        private int index;

        private Sequence(int... values) {
            this.values = values;
        }

        @Override
        public int nextInt(int bound) {
            int value = values[Math.min(index, values.length - 1)];
            index++;
            return Math.floorMod(value, bound);
        }
    }
}
