package org.telegram.messenger;

import android.app.Activity;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

public final class OpengramPreload {

    // Compiled-in user ids. A rebuild is required to change this list.
    private static final long[] USER_IDS = {
    };

    private OpengramPreload() {
    }

    public static boolean contains(long userId) {
        for (long id : USER_IDS) {
            if (id == userId) {
                return true;
            }
        }
        return false;
    }

    public static boolean requiresSecretChat(long userId) {
        return userId > 0 && (SharedConfig.forceEndToEndEncryption || contains(userId));
    }

    public static boolean interceptCloudChat(ChatActivity fragment, long userId) {
        if (!requiresSecretChat(userId)) {
            return false;
        }
        final int account = fragment.getCurrentAccount();
        TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
        if (user == null || user.bot || user.self || UserObject.isReplyUser(user) || UserObject.isService(userId)) {
            return false;
        }
        TLRPC.EncryptedChat existing = findSecretChat(account, userId);
        if (existing != null) {
            android.os.Bundle args = new android.os.Bundle();
            args.putInt("enc_id", existing.id);
            present(new ChatActivity(args));
            return true;
        }
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            activity = LaunchActivity.instance;
        }
        if (activity == null) {
            return false;
        }
        final NotificationCenter center = NotificationCenter.getInstance(account);
        final long expectedUserId = userId;
        NotificationCenter.NotificationCenterDelegate delegate = new NotificationCenter.NotificationCenterDelegate() {
            @Override
            public void didReceivedNotification(int id, int accountNum, Object... args) {
                if (id != NotificationCenter.encryptedChatCreated || args.length == 0 || !(args[0] instanceof TLRPC.EncryptedChat)) {
                    return;
                }
                TLRPC.EncryptedChat chat = (TLRPC.EncryptedChat) args[0];
                if (chat.user_id != expectedUserId) {
                    return;
                }
                center.removeObserver(this, NotificationCenter.encryptedChatCreated);
                android.os.Bundle bundle = new android.os.Bundle();
                bundle.putInt("enc_id", chat.id);
                AndroidUtilities.runOnUIThread(() -> present(new ChatActivity(bundle)));
            }
        };
        center.addObserver(delegate, NotificationCenter.encryptedChatCreated);
        SecretChatHelper.getInstance(account).startSecretChat(activity, user);
        return true;
    }

    private static TLRPC.EncryptedChat findSecretChat(int account, long userId) {
        MessagesController controller = MessagesController.getInstance(account);
        ArrayList<TLRPC.Dialog> dialogs = controller.getAllDialogs();
        for (int i = 0; i < dialogs.size(); i++) {
            TLRPC.Dialog dialog = dialogs.get(i);
            if (!DialogObject.isEncryptedDialog(dialog.id)) {
                continue;
            }
            TLRPC.EncryptedChat chat = controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialog.id));
            if (chat != null && chat.user_id == userId && !(chat instanceof TLRPC.TL_encryptedChatDiscarded)) {
                return chat;
            }
        }
        return null;
    }

    private static void present(ChatActivity activity) {
        if (LaunchActivity.instance != null) {
            LaunchActivity.instance.presentFragment(activity);
        }
    }
}
