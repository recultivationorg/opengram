package org.telegram.messenger;

import android.app.Activity;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

public final class OpengramPreload {

    private OpengramPreload() {
    }

    public static boolean interceptCloudChat(ChatActivity fragment, long userId) {
        final int account = fragment.getCurrentAccount();
        if (OpengramSecurePolicy.allowsCloudTransport(OpengramSecureChannel.evaluate(account, userId))) {
            return false;
        }
        TLRPC.EncryptedChat existing = findSecretChat(account, userId);
        if (existing == null) {
            return false;
        }
        android.os.Bundle args = new android.os.Bundle();
        args.putInt("enc_id", existing.id);
        present(new ChatActivity(args));
        return true;
    }

    public static boolean shouldPromptSecretChat(int account, long userId) {
        if (userId <= 0) {
            return false;
        }
        if (OpengramSecurePolicy.allowsCloudTransport(OpengramSecureChannel.evaluate(account, userId))) {
            return false;
        }
        return findSecretChat(account, userId) == null;
    }

    public static void startSecretChat(ChatActivity fragment, TLRPC.User user) {
        if (fragment == null || user == null) {
            return;
        }
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            activity = LaunchActivity.instance;
        }
        beginSecretChat(activity, fragment.getCurrentAccount(), user);
    }

    private static void beginSecretChat(Activity activity, int account, TLRPC.User user) {
        if (activity == null || user == null) {
            return;
        }
        final NotificationCenter center = NotificationCenter.getInstance(account);
        final long expectedUserId = user.id;
        final NotificationCenter.NotificationCenterDelegate[] holder = new NotificationCenter.NotificationCenterDelegate[1];
        holder[0] = (id, accountNum, args) -> {
            if (id != NotificationCenter.encryptedChatCreated || args.length == 0 || !(args[0] instanceof TLRPC.EncryptedChat)) {
                return;
            }
            TLRPC.EncryptedChat chat = (TLRPC.EncryptedChat) args[0];
            if (chat.user_id != expectedUserId) {
                return;
            }
            center.removeObserver(holder[0], NotificationCenter.encryptedChatCreated);
            android.os.Bundle bundle = new android.os.Bundle();
            bundle.putInt("enc_id", chat.id);
            AndroidUtilities.runOnUIThread(() -> present(new ChatActivity(bundle)));
        };
        center.addObserver(holder[0], NotificationCenter.encryptedChatCreated);
        SecretChatHelper.getInstance(account).startSecretChat(activity, user, () -> center.removeObserver(holder[0], NotificationCenter.encryptedChatCreated));
    }

    private static TLRPC.EncryptedChat findSecretChat(int account, long userId) {
        TLRPC.EncryptedChat chat = MessagesController.getInstance(account).findEncryptedChatByUser(userId);
        if (chat != null) {
            return chat;
        }
        chat = MessagesStorage.getInstance(account).loadEncryptedChatForUserSync(userId);
        if (chat != null) {
            MessagesController.getInstance(account).putEncryptedChat(chat, false);
        }
        return chat;
    }

    private static void present(ChatActivity activity) {
        if (LaunchActivity.instance != null) {
            LaunchActivity.instance.presentFragment(activity);
        }
    }
}
