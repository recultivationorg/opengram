package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.io.File;
import java.util.Random;
import java.util.TimeZone;

public final class OpengramClientFingerprint {

    public static final String LEGACY_MODEL = "GooglePixel 8";
    public static final int LEGACY_OFFSET = 0;

    static final String KEY_MODEL = "opengramReportedDeviceModel";
    static final String KEY_TIMEZONE = "opengramReportedTimezone";

    static final String[] MODELS = {
            "GooglePixel 8",
            "GooglePixel 8 Pro",
            "GooglePixel 9",
            "GooglePixel 9 Pro",
            "GooglePixel 9 Pro XL",
            "GooglePixel 10",
            "GooglePixel 10 Pro",
            "GooglePixel 10 Pro XL",
            "GooglePixel 11",
            "GooglePixel 11 Pro",
            "GooglePixel 11 Pro XL",
            "samsungSM-S918B",
            "samsungSM-S921B",
            "samsungSM-S926B",
            "samsungSM-S928B",
            "samsungSM-S928U",
            "samsungSM-S931B",
            "samsungSM-S936B",
            "samsungSM-S938B",
            "samsungSM-S938U"
    };

    static final String[] ZONES = {
            "America/New_York",
            "America/Los_Angeles",
            "Europe/London",
            "Europe/Berlin",
            "Europe/Moscow",
            "Asia/Dubai",
            "Asia/Kolkata",
            "Asia/Singapore",
            "Asia/Tokyo",
            "Australia/Sydney"
    };

    public static final class Identity {
        public final String deviceModel;
        public final int timezoneOffset;

        Identity(String deviceModel, int timezoneOffset) {
            this.deviceModel = deviceModel;
            this.timezoneOffset = timezoneOffset;
        }
    }

    interface Store {
        String get(String key);

        void putPair(String model, String timezone);
    }

    private OpengramClientFingerprint() {
    }

    public static Identity resolve(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        File tgnet = new File(ApplicationLoader.getFilesDirFixed(), "tgnet.dat");
        return resolve(new PreferenceStore(preferences), tgnet.exists(), new Random(), System.currentTimeMillis());
    }

    public static void reroll(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        reroll(new PreferenceStore(preferences), new Random(), System.currentTimeMillis());
    }

    public static void rerollAndRestart(Activity activity) {
        reroll(activity);
        PackageManager manager = activity.getPackageManager();
        Intent intent = manager.getLaunchIntentForPackage(activity.getPackageName());
        activity.finishAffinity();
        if (intent != null) {
            activity.startActivity(intent);
        }
        System.exit(0);
    }

    static Identity resolve(Store store, boolean tgnetExists, Random random, long nowMillis) {
        String model = store.get(KEY_MODEL);
        String timezone = store.get(KEY_TIMEZONE);
        if (present(model) && present(timezone)) {
            return new Identity(model, offsetOf(timezone, nowMillis));
        }
        if (tgnetExists) {
            store.putPair(LEGACY_MODEL, Integer.toString(LEGACY_OFFSET));
            return new Identity(LEGACY_MODEL, LEGACY_OFFSET);
        }
        return draw(store, random, nowMillis);
    }

    static Identity reroll(Store store, Random random, long nowMillis) {
        return draw(store, random, nowMillis);
    }

    static int offsetOf(String stored, long nowMillis) {
        if (fixedOffset(stored)) {
            return Integer.parseInt(stored);
        }
        return TimeZone.getTimeZone(stored).getOffset(nowMillis) / 1000;
    }

    private static Identity draw(Store store, Random random, long nowMillis) {
        String model = MODELS[random.nextInt(MODELS.length)];
        String zone = ZONES[random.nextInt(ZONES.length)];
        store.putPair(model, zone);
        return new Identity(model, offsetOf(zone, nowMillis));
    }

    private static boolean present(String value) {
        return value != null && value.length() > 0;
    }

    private static boolean fixedOffset(String value) {
        int start = 0;
        if (value.charAt(0) == '-' || value.charAt(0) == '+') {
            start = 1;
        }
        if (start >= value.length()) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static final class PreferenceStore implements Store {
        private final SharedPreferences preferences;

        private PreferenceStore(SharedPreferences preferences) {
            this.preferences = preferences;
        }

        @Override
        public String get(String key) {
            return preferences.getString(key, null);
        }

        @Override
        public void putPair(String model, String timezone) {
            preferences.edit().putString(KEY_MODEL, model).putString(KEY_TIMEZONE, timezone).commit();
        }
    }
}
