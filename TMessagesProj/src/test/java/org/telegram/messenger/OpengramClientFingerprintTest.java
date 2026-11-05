package org.telegram.messenger;

import org.junit.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
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
