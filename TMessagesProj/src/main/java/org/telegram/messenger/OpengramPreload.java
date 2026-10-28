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
        return presentSecretChat(fragment, account, existing.id);
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
        TLRPC.EncryptedChat existing = findSecretChat(fragment.getCurrentAccount(), user.id);
        if (existing != null) {
            presentSecretChat(fragment, fragment.getCurrentAccount(), existing.id);
            return;
        }
        beginSecretChat(fragment, activity, fragment.getCurrentAccount(), user);
    }

    private static void beginSecretChat(ChatActivity source, Activity activity, int account, TLRPC.User user) {
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
            AndroidUtilities.runOnUIThread(() -> presentSecretChat(source, account, chat.id));
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

    private static boolean presentSecretChat(ChatActivity source, int account, int encId) {
        android.os.Bundle args = new android.os.Bundle();
        args.putInt("enc_id", encId);
        ChatActivity activity = new ChatActivity(args);
        activity.setCurrentAccount(account);
        if (source != null && source.getParentLayout() != null) {
            return source.presentFragment(activity);
        }
        return LaunchActivity.instance != null && LaunchActivity.instance.presentFragment(activity);
    }
}
