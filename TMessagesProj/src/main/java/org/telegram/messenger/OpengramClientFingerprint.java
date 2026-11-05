package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.io.File;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;

public final class OpengramClientFingerprint {

    public static final String LEGACY_MODEL = "GooglePixel 8";
    public static final int LEGACY_OFFSET = 0;

    static final String KEY_MODEL = "opengramReportedDeviceModel";
    static final String KEY_TIMEZONE = "opengramReportedTimezone";
    static final String KEY_ACCOUNT_MODEL = "opengramAccountDeviceModel";
    static final String KEY_ACCOUNT_TIMEZONE = "opengramAccountTimezone";

    private static final Object lock = new Object();

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
        public final String timezone;
        public final int timezoneOffset;

        Identity(String deviceModel, String timezone, int timezoneOffset) {
            this.deviceModel = deviceModel;
            this.timezone = timezone;
            this.timezoneOffset = timezoneOffset;
        }
    }

    interface Store {
        String get(String key);

        void putPair(String model, String timezone);

        void putAccount(String model, String timezone);

        void removeAccount();
    }

    private OpengramClientFingerprint() {
    }

    public static Identity resolve(Context context) {
        synchronized (lock) {
            return resolve(installStore(context), tgnetExists(), new Random(), System.currentTimeMillis());
        }
    }

    public static Identity resolve(Context context, int account) {
        synchronized (lock) {
            UserConfig config = UserConfig.getInstance(account);
            config.loadConfig();
            boolean keepInstallIdentity = account == 0 && config.isClientActivated();
            return resolveAccount(accountStore(context, account), installStore(context), keepInstallIdentity, new Random(), System.currentTimeMillis(), reservedIdentities(context, account));
        }
    }

    public static Identity accountOverride(Context context, int account) {
        synchronized (lock) {
            Store store = accountStore(context, account);
            String model = store.get(KEY_ACCOUNT_MODEL);
            String timezone = store.get(KEY_ACCOUNT_TIMEZONE);
            if (!present(model) || !present(timezone)) {
                return null;
            }
            return new Identity(model, timezone, offsetOf(timezone, System.currentTimeMillis()));
        }
    }

    public static void reroll(Context context, int account) {
        synchronized (lock) {
            drawAccount(accountStore(context, account), new Random(), System.currentTimeMillis(), reservedIdentities(context, account));
        }
    }

    public static void apply(Context context, int account, String deviceModel, String timezone) {
        if (!acceptable(deviceModel, timezone)) {
            return;
        }
        synchronized (lock) {
            accountStore(context, account).putAccount(deviceModel, timezone);
        }
        org.telegram.tgnet.ConnectionsManager.applyReportedIdentity(account, deviceModel, offsetOf(timezone, System.currentTimeMillis()));
    }

    public static void clear(Context context, int account) {
        Identity fresh;
        synchronized (lock) {
            fresh = drawAccount(accountStore(context, account), new Random(), System.currentTimeMillis(), reservedIdentities(context, account));
        }
        org.telegram.tgnet.ConnectionsManager.applyReportedIdentity(account, fresh.deviceModel, fresh.timezoneOffset);
    }

    public static boolean acceptable(String deviceModel, String timezone) {
        return text(deviceModel, 128) && text(timezone, 64);
    }

    public static void rerollAndRestart(Activity activity, int account) {
        reroll(activity, account);
        PackageManager manager = activity.getPackageManager();
        Intent intent = manager.getLaunchIntentForPackage(activity.getPackageName());
        activity.finishAffinity();
        if (intent != null) {
            activity.startActivity(intent);
        }
        System.exit(0);
    }

    static Identity resolveAccount(Store account, Store install, boolean keepInstallIdentity, Random random, long nowMillis) {
        return resolveAccount(account, install, keepInstallIdentity, random, nowMillis, null);
    }

    static Identity resolveAccount(Store account, Store install, boolean keepInstallIdentity, Random random, long nowMillis, Set<String> reserved) {
        String model = account.get(KEY_ACCOUNT_MODEL);
        String timezone = account.get(KEY_ACCOUNT_TIMEZONE);
        if (present(model) && present(timezone) && (keepInstallIdentity || !copiedFromInstall(install, model, timezone))) {
            return new Identity(model, timezone, offsetOf(timezone, nowMillis));
        }
        if (keepInstallIdentity && !present(model)) {
            String installModel = install.get(KEY_MODEL);
            String installTimezone = install.get(KEY_TIMEZONE);
            if (!present(installModel) || !present(installTimezone)) {
                installModel = LEGACY_MODEL;
                installTimezone = Integer.toString(LEGACY_OFFSET);
            }
            account.putAccount(installModel, installTimezone);
            return new Identity(installModel, installTimezone, offsetOf(installTimezone, nowMillis));
        }
        Set<String> blocked = reserved == null ? new HashSet<String>() : reserved;
        addReserved(blocked, install.get(KEY_MODEL), install.get(KEY_TIMEZONE));
        if (!present(install.get(KEY_MODEL)) || !present(install.get(KEY_TIMEZONE))) {
            blocked.add(LEGACY_MODEL + "\n" + LEGACY_OFFSET);
        }
        return drawAccount(account, random, nowMillis, blocked);
    }

    static Identity resolve(Store store, boolean tgnetExists, Random random, long nowMillis) {
        String model = store.get(KEY_MODEL);
        String timezone = store.get(KEY_TIMEZONE);
        if (present(model) && present(timezone)) {
            return new Identity(model, timezone, offsetOf(timezone, nowMillis));
        }
        if (tgnetExists) {
            String legacyTimezone = Integer.toString(LEGACY_OFFSET);
            store.putPair(LEGACY_MODEL, legacyTimezone);
            return new Identity(LEGACY_MODEL, legacyTimezone, LEGACY_OFFSET);
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
        return new Identity(model, zone, offsetOf(zone, nowMillis));
    }

    private static Identity drawAccount(Store account, Random random, long nowMillis, Set<String> reserved) {
        String model = MODELS[0];
        String zone = ZONES[0];
        for (int attempt = 0; attempt < MODELS.length * ZONES.length; attempt++) {
            model = MODELS[random.nextInt(MODELS.length)];
            zone = ZONES[random.nextInt(ZONES.length)];
            if (reserved == null || !reserved.contains(model + "\n" + zone)) {
                break;
            }
        }
        account.putAccount(model, zone);
        return new Identity(model, zone, offsetOf(zone, nowMillis));
    }

    private static boolean copiedFromInstall(Store install, String model, String timezone) {
        String installModel = install.get(KEY_MODEL);
        String installTimezone = install.get(KEY_TIMEZONE);
        if (present(installModel) && present(installTimezone)) {
            return model.equals(installModel) && timezone.equals(installTimezone);
        }
        return LEGACY_MODEL.equals(model) && Integer.toString(LEGACY_OFFSET).equals(timezone);
    }

    private static Set<String> reservedIdentities(Context context, int account) {
        Set<String> reserved = new HashSet<>();
        Store install = installStore(context);
        addReserved(reserved, install.get(KEY_MODEL), install.get(KEY_TIMEZONE));
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (i == account) {
                continue;
            }
            Store other = accountStore(context, i);
            addReserved(reserved, other.get(KEY_ACCOUNT_MODEL), other.get(KEY_ACCOUNT_TIMEZONE));
        }
        return reserved;
    }

    private static void addReserved(Set<String> reserved, String model, String timezone) {
        if (present(model) && present(timezone)) {
            reserved.add(model + "\n" + timezone);
        }
    }

    private static boolean text(String value, int max) {
        if (value == null || value.length() == 0 || value.length() > max) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
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

        @Override
        public void putAccount(String model, String timezone) {
            preferences.edit().putString(KEY_ACCOUNT_MODEL, model).putString(KEY_ACCOUNT_TIMEZONE, timezone).commit();
        }

        @Override
        public void removeAccount() {
            preferences.edit().remove(KEY_ACCOUNT_MODEL).remove(KEY_ACCOUNT_TIMEZONE).commit();
        }
    }

    private static Store installStore(Context context) {
        return new PreferenceStore(context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE));
    }

    private static Store accountStore(Context context, int account) {
        String name = account == 0 ? "mainconfig" : "mainconfig" + account;
        return new PreferenceStore(context.getSharedPreferences(name, Context.MODE_PRIVATE));
    }

    private static boolean tgnetExists() {
        return new File(ApplicationLoader.getFilesDirFixed(), "tgnet.dat").exists();
    }
}
