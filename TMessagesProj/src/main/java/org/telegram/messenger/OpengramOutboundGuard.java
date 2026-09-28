package org.telegram.messenger;

import android.app.NotificationManager;
import android.content.Context;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

/**
 * Final backstop for user-authored cloud sends. Interactive screens should
 * also preflight, but a caller that never does still cannot dispatch.
 */
public final class OpengramOutboundGuard {

    private OpengramOutboundGuard() {
    }

    public static boolean reject(int account, long dialogId) {
        OpengramSecurePolicy.Decision decision = OpengramSecureChannel.evaluate(account, dialogId);
        if (OpengramSecurePolicy.allowsCloudTransport(decision)) {
            return false;
        }
        AndroidUtilities.runOnUIThread(() -> showBlocked(account, dialogId));
        return true;
    }

    private static void showBlocked(int account, long dialogId) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        String text = LocaleController.getString(R.string.SecretChatRequiredReply);
        if (LaunchActivity.instance != null) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
            return;
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL == null ? "Other3" : NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
            .setSmallIcon(R.drawable.notification)
            .setContentTitle(LocaleController.getString(R.string.ForceEndToEndEncryption))
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(account * 100000 + (int) (dialogId ^ (dialogId >>> 32)), builder.build());
        }
    }
}
