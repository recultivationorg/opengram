/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2020.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.android.billingclient.api.ProductDetails;

import java.util.Objects;

public class BuildVars {

    public static boolean DEBUG_VERSION = BuildConfig.DEBUG_VERSION;
    public static boolean LOGS_ENABLED = BuildConfig.DEBUG_VERSION;
    public static boolean DEBUG_PRIVATE_VERSION = BuildConfig.DEBUG_PRIVATE_VERSION;
    public static boolean USE_CLOUD_STRINGS = true;
    public static boolean CHECK_UPDATES = true;
    public static boolean NO_SCOPED_STORAGE = Build.VERSION.SDK_INT <= 29;
    public static String BUILD_VERSION_STRING = BuildConfig.BUILD_VERSION_STRING;

    public static int APP_ID = 4;
    public static String APP_HASH = "014b35b6184100b085b0d0572f9b5103";

    // Identity reported in initConnection. Local Build.* stays real for audio and video workarounds.
    public static final String REPORTED_DEVICE_MODEL = "GooglePixel 8";
    public static final String REPORTED_SYSTEM_VERSION = "SDK 37";
    public static final String REPORTED_INSTALLER = "com.google.android.packageinstaller";
    public static final String REPORTED_PACKAGE_ID = "org.recultivation.opengram";

    // Official website APK (telegram.org/android), build 12.10.3, version code 7089.
    // Set MASK_AS_OFFICIAL_CLIENT to true to send that package, version, and certificate again.
    public static boolean MASK_AS_OFFICIAL_CLIENT = false;
    public static final String OFFICIAL_WEB_PACKAGE_ID = "org.telegram.messenger.web";
    public static final String OFFICIAL_WEB_APP_VERSION = "12.10.3 (7089)";
    public static final String OFFICIAL_WEB_CERT_SHA256 = "49C1522548EBACD46CE322B6FD47F6092BB745D0F88082145CAF35E14DCC38E1";

    // Forkgram GitHub build 12.10.5.1, versionCode 710019, certificate CN=23rd.
    // Reported to Telegram only. The installed application id stays REPORTED_PACKAGE_ID.
    public static boolean REPORT_FORKGRAM_IDENTITY = true;
    public static final String FORKGRAM_PACKAGE_ID = "org.forkclient.messenger.beta";
    public static final String FORKGRAM_APP_VERSION = "12.10.5.1 (710019)";
    public static final int FORKGRAM_VERSION_CODE = 710019;
    public static final String FORKGRAM_CERT_SHA256 = "0880F186D777F3CB6D82A66D02BD58850E3A270A26B678175459A95511235AE5";

    public static boolean useOfficialWebIdentity() {
        return MASK_AS_OFFICIAL_CLIENT && OFFICIAL_WEB_APP_VERSION != null && !OFFICIAL_WEB_APP_VERSION.isEmpty();
    }

    public static boolean useForkgramIdentity() {
        return !useOfficialWebIdentity() && REPORT_FORKGRAM_IDENTITY;
    }
    public static final String REPORTED_LANG_CODE = "en-us";
    public static final int REPORTED_TIMEZONE_OFFSET = 0;
    public static String PLAYSTORE_APP_URL = "https://play.google.com/store/apps/details?id=org.telegram.messenger";
    public static String HUAWEI_STORE_URL = "https://appgallery.huawei.com/app/C101184875";
    public static String GOOGLE_AUTH_CLIENT_ID = "760348033671-81kmi3pi84p11ub8hp9a1funsv0rn2p9.apps.googleusercontent.com";

    public static String HUAWEI_APP_ID = "101184875";

    // You can use this flag to disable Google Play Billing (If you're making fork and want it to be in Google Play)
    public static boolean IS_BILLING_UNAVAILABLE = false;

    // Do not request or render Telegram sponsored messages, search ads, or in-video ads.
    public static boolean DISABLE_SPONSORED_MESSAGES = true;

    // Do not open Premium purchase pages, gift-premium checkout, or promotional sheets.
    // Account entitlements stay whatever the server reports.
    public static boolean DISABLE_PREMIUM_PROMO = true;

    // works only on official app ids, disable on your forks
    public static boolean SUPPORTS_PASSKEYS = true;

    static {
        if (ApplicationLoader.applicationContext != null) {
            SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", Context.MODE_PRIVATE);
            LOGS_ENABLED = DEBUG_VERSION || sharedPreferences.getBoolean("logsEnabled", DEBUG_VERSION);
            if (LOGS_ENABLED) {
                final Thread.UncaughtExceptionHandler pastHandler = Thread.getDefaultUncaughtExceptionHandler();
                Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
                    FileLog.fatal(exception, false);
                    if (pastHandler != null) {
                        pastHandler.uncaughtException(thread, exception);
                    }
                });
            }
        }
    }

    public static boolean useInvoiceBilling() {
        return BillingController.billingClientEmpty || DEBUG_VERSION && false || ApplicationLoader.isStandaloneBuild() || isBetaApp() && false || isHuaweiStoreApp() || hasDirectCurrency();
    }

    private static boolean hasDirectCurrency() {
        if (!BillingController.getInstance().isReady() || BillingController.PREMIUM_PRODUCT_DETAILS == null) {
            return false;
        }
        for (ProductDetails.SubscriptionOfferDetails offerDetails : BillingController.PREMIUM_PRODUCT_DETAILS.getSubscriptionOfferDetails()) {
            for (ProductDetails.PricingPhase phase : offerDetails.getPricingPhases().getPricingPhaseList()) {
                for (String cur : MessagesController.getInstance(UserConfig.selectedAccount).directPaymentsCurrency) {
                    if (Objects.equals(phase.getPriceCurrencyCode(), cur)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Boolean betaApp;
    public static boolean isBetaApp() {
        if (betaApp == null) {
            betaApp = ApplicationLoader.applicationContext != null && "org.telegram.messenger.beta".equals(ApplicationLoader.applicationContext.getPackageName());
        }
        return betaApp;
    }


    public static boolean isHuaweiStoreApp() {
        return ApplicationLoader.isHuaweiStoreBuild();
    }

    public static String getSmsHash() {
        return ApplicationLoader.isStandaloneBuild() ? "w0lkcmTZkKh" : (DEBUG_VERSION ? "O2P2z+/jBpJ" : "oLeq9AcOZkT");
    }
}
