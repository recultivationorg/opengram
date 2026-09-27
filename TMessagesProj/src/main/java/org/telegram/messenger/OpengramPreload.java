package org.telegram.messenger;

import android.app.Activity;
import android.util.LongSparseArray;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

import java.util.HashSet;

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

    public static void ensureSnapshot() {
        if (!SharedConfig.forceEndToEndEncryption || SharedConfig.forceEndToEndChatsSnapshotted) {
            return;
        }
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!UserConfig.getInstance(account).isClientActivated()) {
                continue;
            }
            if (!MessagesController.getInstance(account).dialogsLoaded) {
                return;
            }
        }
        captureExistingChats();
    }

    public static void captureExistingChats() {
        HashSet<String> ids = new HashSet<>();
        boolean ready = true;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!UserConfig.getInstance(account).isClientActivated()) {
                continue;
            }
            MessagesController controller = MessagesController.getInstance(account);
            if (!controller.dialogsLoaded) {
                ready = false;
            }
            LongSparseArray<TLRPC.Dialog> dialogs = controller.dialogs_dict;
            for (int i = 0; i < dialogs.size(); i++) {
                long dialogId = dialogs.keyAt(i);
                if (DialogObject.isUserDialog(dialogId)) {
                    ids.add(account + ":" + dialogId);
                }
            }
        }
        if (ready) {
            SharedConfig.forceEndToEndChatsSnapshotted = OpengramChatSnapshot.store(ids);
        } else {
            SharedConfig.forceEndToEndChatsSnapshotted = false;
        }
        SharedConfig.saveConfig();
    }

    public static boolean chatPredatesForce(int account, long userId) {
        ensureSnapshot();
        if (!SharedConfig.forceEndToEndChatsSnapshotted) {
            return true;
        }
        return OpengramChatSnapshot.contains(account, userId);
    }

    public static boolean forceApplies(int account, long userId) {
        if (!SharedConfig.forceEndToEndEncryption || userId <= 0 || !canSecretChat(account, userId)) {
            return false;
        }
        if (SharedConfig.forceEndToEndForAllChats) {
            return true;
        }
        return !chatPredatesForce(account, userId);
    }

    public static boolean interceptCloudChat(ChatActivity fragment, long userId) {
        final int account = fragment.getCurrentAccount();
        if (!canSecretChat(account, userId)) {
            return false;
        }
        boolean forced = forceApplies(account, userId);
        boolean listed = contains(userId);
        if (!forced && !listed) {
            return false;
        }
        TLRPC.EncryptedChat existing = findSecretChat(account, userId);
        if (existing != null) {
            android.os.Bundle args = new android.os.Bundle();
            args.putInt("enc_id", existing.id);
            present(new ChatActivity(args));
            return true;
        }
        if (listed && !forced) {
            TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
            Activity activity = fragment.getParentActivity();
            if (activity == null) {
                activity = LaunchActivity.instance;
            }
            beginSecretChat(activity, account, user);
            return true;
        }
        return false;
    }

    public static boolean shouldPromptSecretChat(int account, long userId) {
        if (!forceApplies(account, userId)) {
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

    private static boolean canSecretChat(int account, long userId) {
        if (userId <= 0 || UserObject.isReplyUser(userId) || UserObject.isService(userId)) {
            return false;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
        return user != null && !user.bot && !user.self && !UserObject.isDeleted(user);
    }

    private static void beginSecretChat(Activity activity, int account, TLRPC.User user) {
        if (activity == null || user == null) {
            return;
        }
        final NotificationCenter center = NotificationCenter.getInstance(account);
        final long expectedUserId = user.id;
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
    }

    private static TLRPC.EncryptedChat findSecretChat(int account, long userId) {
        MessagesController controller = MessagesController.getInstance(account);
        LongSparseArray<TLRPC.Dialog> dialogs = controller.dialogs_dict;
        for (int i = 0; i < dialogs.size(); i++) {
            long dialogId = dialogs.keyAt(i);
            if (!DialogObject.isEncryptedDialog(dialogId)) {
                continue;
            }
            TLRPC.EncryptedChat chat = controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
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
