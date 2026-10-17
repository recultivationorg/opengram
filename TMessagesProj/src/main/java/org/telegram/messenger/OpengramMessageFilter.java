package org.telegram.messenger;

import android.content.Context;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Hides channel posts and bot-chat messages whose text contains a keyword.
 * The keyword list is a per-account snapshot. StrongBox mode authenticates the
 * header as additional data. A bad seal, a mode mismatch, or a truncated file
 * clears the in-memory list and stops filtering.
 */
public final class OpengramMessageFilter {

    public static final int MAX_KEYWORDS = 32;
    public static final int MAX_KEYWORD_LENGTH = 48;

    private static final int ACCOUNT_SLOTS = 4;
    private static final String FILE_PREFIX = "opengram-filter-";
    private static final String FILE_SUFFIX = ".bin";
    private static final Object lock = new Object();
    private static final State[] states = new State[ACCOUNT_SLOTS];

    public enum Phase {
        DISABLED,
        READY,
        ERROR
    }

    public static final class Snapshot {
        public final OpengramSnapshotFile.Status status;
        public final List<String> keywords;

        Snapshot(OpengramSnapshotFile.Status status, List<String> keywords) {
            this.status = status;
            this.keywords = keywords == null ? Collections.<String>emptyList() : keywords;
        }
    }

    private static final class State {
        boolean loaded;
        boolean error;
        long clientUserId;
        int mode;
        final ArrayList<String> keywords = new ArrayList<>();

        void clear() {
            loaded = false;
            error = false;
            clientUserId = 0;
            mode = 0;
            keywords.clear();
        }

        void fail(long clientUserId, int mode) {
            loaded = true;
            error = true;
            this.clientUserId = clientUserId;
            this.mode = mode;
            keywords.clear();
        }
    }

    private OpengramMessageFilter() {
    }

    public static List<String> parseKeywords(String raw) {
        ArrayList<String> keywords = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return keywords;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int start = 0;
        for (int i = 0; i <= raw.length(); i++) {
            if (i != raw.length() && raw.charAt(i) != ',') {
                continue;
            }
            String token = raw.substring(start, i).replace('\n', ' ').replace('\r', ' ').trim();
            start = i + 1;
            if (token.isEmpty() || token.length() > MAX_KEYWORD_LENGTH) {
                continue;
            }
            if (!seen.add(token.toLowerCase(Locale.ROOT))) {
                continue;
            }
            keywords.add(token);
            if (keywords.size() == MAX_KEYWORDS) {
                break;
            }
        }
        return keywords;
    }

    public static boolean matchesText(CharSequence text, Collection<String> keywords) {
        if (text == null || text.length() == 0 || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String haystack = text.toString().toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword == null || keyword.isEmpty()) {
                continue;
            }
            if (haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static boolean messageMatches(MessageObject message, Collection<String> keywords) {
        if (message == null) {
            return false;
        }
        if (matchesText(message.messageText, keywords)) {
            return true;
        }
        return message.messageOwner != null && matchesText(message.messageOwner.message, keywords);
    }

    public static boolean appliesToDialog(boolean encrypted, boolean broadcastChannel, boolean bot) {
        if (encrypted) {
            return false;
        }
        return broadcastChannel || bot;
    }

    public static boolean writeKeywords(File file, int mode, long clientUserId, Collection<String> keywords, OpengramSnapshotFile.Sealer sealer) {
        LinkedHashSet<String> peers = new LinkedHashSet<>();
        if (keywords != null) {
            for (String keyword : keywords) {
                if (keyword == null) {
                    continue;
                }
                String token = keyword.replace('\n', ' ').replace('\r', ' ').trim();
                if (token.isEmpty() || token.length() > MAX_KEYWORD_LENGTH || token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0) {
                    continue;
                }
                if (!peers.add(token)) {
                    continue;
                }
                if (peers.size() == MAX_KEYWORDS) {
                    break;
                }
            }
        }
        return OpengramSnapshotFile.write(file, new OpengramSnapshotFile.Record(mode, 0L, new long[]{clientUserId}, peers), sealer);
    }

    public static Snapshot readKeywords(File file, int mode, long clientUserId, OpengramSnapshotFile.Sealer sealer) {
        OpengramSnapshotFile.ReadResult read = OpengramSnapshotFile.read(file, mode, sealer);
        if (read.status != OpengramSnapshotFile.Status.OK || read.record == null) {
            return new Snapshot(read.status, Collections.<String>emptyList());
        }
        if (read.record.accountIds.length != 1 || read.record.accountIds[0] != clientUserId) {
            return new Snapshot(OpengramSnapshotFile.Status.CORRUPT, Collections.<String>emptyList());
        }
        ArrayList<String> keywords = new ArrayList<>();
        for (String peer : read.record.peers) {
            if (peer == null || peer.indexOf('\n') >= 0 || peer.indexOf('\r') >= 0) {
                return new Snapshot(OpengramSnapshotFile.Status.CORRUPT, Collections.<String>emptyList());
            }
            String token = peer.trim();
            if (token.isEmpty() || token.length() > MAX_KEYWORD_LENGTH) {
                return new Snapshot(OpengramSnapshotFile.Status.CORRUPT, Collections.<String>emptyList());
            }
            keywords.add(token);
            if (keywords.size() > MAX_KEYWORDS) {
                return new Snapshot(OpengramSnapshotFile.Status.CORRUPT, Collections.<String>emptyList());
            }
        }
        return new Snapshot(OpengramSnapshotFile.Status.OK, keywords);
    }

    public static Phase phase(int account) {
        if (!validAccount(account) || !UserConfig.getInstance(account).messageFilter) {
            return Phase.DISABLED;
        }
        synchronized (lock) {
            ensureLoadedLocked(account);
            return stateLocked(account).error ? Phase.ERROR : Phase.READY;
        }
    }

    public static int keywordCount(int account) {
        if (phase(account) != Phase.READY) {
            return 0;
        }
        synchronized (lock) {
            return stateLocked(account).keywords.size();
        }
    }

    public static String keywordsText(int account) {
        if (phase(account) != Phase.READY) {
            return "";
        }
        synchronized (lock) {
            ArrayList<String> keywords = stateLocked(account).keywords;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(keywords.get(i));
            }
            return builder.toString();
        }
    }

    public static boolean enable(int account, int mode) {
        if (!validAccount(account)) {
            return false;
        }
        if (mode != OpengramSnapshotFile.MODE_STRONGBOX && mode != OpengramSnapshotFile.MODE_PLAINTEXT) {
            return false;
        }
        UserConfig config = UserConfig.getInstance(account);
        long clientUserId = config.getClientUserId();
        if (clientUserId <= 0) {
            return false;
        }
        if (mode == OpengramSnapshotFile.MODE_STRONGBOX && OpengramStrongBox.forFilter(clientUserId).probeKey() != OpengramStrongBox.Probe.AVAILABLE) {
            return false;
        }
        OpengramSnapshotFile.Sealer sealer = mode == OpengramSnapshotFile.MODE_STRONGBOX ? OpengramStrongBox.forFilter(clientUserId) : null;
        if (!writeKeywords(file(clientUserId), mode, clientUserId, Collections.<String>emptyList(), sealer)) {
            return false;
        }
        config.messageFilter = true;
        config.messageFilterMode = mode;
        config.saveConfig(false);
        synchronized (lock) {
            State state = stateLocked(account);
            state.loaded = true;
            state.error = false;
            state.clientUserId = clientUserId;
            state.mode = mode;
            state.keywords.clear();
        }
        notifyDialogs(account);
        return true;
    }

    public static void disable(int account) {
        if (!validAccount(account)) {
            return;
        }
        UserConfig config = UserConfig.getInstance(account);
        long clientUserId = config.getClientUserId();
        config.messageFilter = false;
        config.messageFilterMode = 0;
        config.saveConfig(false);
        synchronized (lock) {
            stateLocked(account).clear();
        }
        if (clientUserId > 0) {
            OpengramSnapshotFile.delete(file(clientUserId));
            OpengramStrongBox.forFilter(clientUserId).deleteKey();
        }
        notifyDialogs(account);
    }

    public static boolean saveKeywords(int account, String raw) {
        if (phase(account) != Phase.READY) {
            return false;
        }
        UserConfig config = UserConfig.getInstance(account);
        long clientUserId = config.getClientUserId();
        int mode = config.messageFilterMode;
        if (clientUserId <= 0) {
            return false;
        }
        List<String> keywords = parseKeywords(raw);
        OpengramSnapshotFile.Sealer sealer = mode == OpengramSnapshotFile.MODE_STRONGBOX ? OpengramStrongBox.forFilter(clientUserId) : null;
        if (!writeKeywords(file(clientUserId), mode, clientUserId, keywords, sealer)) {
            return false;
        }
        synchronized (lock) {
            State state = stateLocked(account);
            state.loaded = true;
            state.error = false;
            state.clientUserId = clientUserId;
            state.mode = mode;
            state.keywords.clear();
            state.keywords.addAll(keywords);
        }
        notifyDialogs(account);
        return true;
    }

    private static void notifyDialogs(int account) {
        MessagesController.getInstance(account).clearVisibleDialogPreviews();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageFilterChanged);
        NotificationsController.getInstance(account).removeFilteredPushMessages();
    }

    public static ArrayList<MessageObject> withoutHidden(int account, long dialogId, ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<String> keywords = keywordsForHide(account);
        if (keywords.isEmpty() || !appliesToDialog(DialogObject.isEncryptedDialog(dialogId), isBroadcastChannel(account, dialogId), isBotChat(account, dialogId))) {
            return messages;
        }
        HashSet<Long> hiddenGroups = hiddenGroups(messages, keywords);
        ArrayList<MessageObject> visible = null;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message != null && (messageMatches(message, keywords) || hiddenGroups.contains(message.getGroupId()))) {
                if (visible == null) {
                    visible = new ArrayList<>(Math.max(0, messages.size() - 1));
                    for (int j = 0; j < i; j++) {
                        visible.add(messages.get(j));
                    }
                }
                continue;
            }
            if (visible != null) {
                visible.add(message);
            }
        }
        return visible == null ? messages : visible;
    }

    public static boolean previewIsHidden(int account, long dialogId, List<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        HashSet<Long> groups = hiddenGroups(account, dialogId, messages);
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message == null) {
                continue;
            }
            long groupId = message.getGroupId();
            if (shouldHide(account, dialogId, message) || groupId != 0 && groups.contains(groupId)) {
                return true;
            }
        }
        return false;
    }

    public static HashSet<Long> hiddenGroups(int account, long dialogId, List<MessageObject> messages) {
        List<String> keywords = keywordsForHide(account);
        if (keywords.isEmpty() || messages == null || !appliesToDialog(DialogObject.isEncryptedDialog(dialogId), isBroadcastChannel(account, dialogId), isBotChat(account, dialogId))) {
            return new HashSet<>();
        }
        return hiddenGroups(messages, keywords);
    }

    public static boolean shouldHide(int account, long dialogId, MessageObject message) {
        if (message == null) {
            return false;
        }
        List<String> keywords = keywordsForHide(account);
        if (keywords.isEmpty()) {
            return false;
        }
        long id = dialogId;
        if (id == 0 && message.messageOwner != null) {
            id = message.getDialogId();
        }
        if (!appliesToDialog(DialogObject.isEncryptedDialog(id), isBroadcastChannel(account, id), isBotChat(account, id))) {
            return false;
        }
        return messageMatches(message, keywords);
    }

    private static HashSet<Long> hiddenGroups(List<MessageObject> messages, List<String> keywords) {
        HashSet<Long> groups = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message == null) {
                continue;
            }
            long groupId = message.getGroupId();
            if (groupId != 0 && messageMatches(message, keywords)) {
                groups.add(groupId);
            }
        }
        return groups;
    }

    private static List<String> keywordsForHide(int account) {
        if (phase(account) != Phase.READY) {
            return Collections.emptyList();
        }
        synchronized (lock) {
            return new ArrayList<>(stateLocked(account).keywords);
        }
    }

    private static boolean isBroadcastChannel(int account, long dialogId) {
        if (!DialogObject.isChatDialog(dialogId)) {
            return false;
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        return ChatObject.isChannelAndNotMegaGroup(chat);
    }

    private static boolean isBotChat(int account, long dialogId) {
        if (!DialogObject.isUserDialog(dialogId)) {
            return false;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        return user != null && user.bot;
    }

    private static void ensureLoadedLocked(int account) {
        UserConfig config = UserConfig.getInstance(account);
        State state = stateLocked(account);
        if (!config.messageFilter) {
            state.clear();
            return;
        }
        long clientUserId = config.getClientUserId();
        int mode = config.messageFilterMode;
        if (state.loaded && state.clientUserId == clientUserId && state.mode == mode) {
            return;
        }
        if (clientUserId <= 0 || (mode != OpengramSnapshotFile.MODE_STRONGBOX && mode != OpengramSnapshotFile.MODE_PLAINTEXT)) {
            state.fail(clientUserId, mode);
            return;
        }
        OpengramSnapshotFile.Sealer sealer = mode == OpengramSnapshotFile.MODE_STRONGBOX ? OpengramStrongBox.forFilter(clientUserId) : null;
        Snapshot snapshot = readKeywords(file(clientUserId), mode, clientUserId, sealer);
        if (snapshot.status != OpengramSnapshotFile.Status.OK) {
            state.fail(clientUserId, mode);
            return;
        }
        state.loaded = true;
        state.error = false;
        state.clientUserId = clientUserId;
        state.mode = mode;
        state.keywords.clear();
        state.keywords.addAll(snapshot.keywords);
    }

    private static boolean validAccount(int account) {
        return account >= 0 && account < ACCOUNT_SLOTS && account < UserConfig.MAX_ACCOUNT_COUNT;
    }

    private static State stateLocked(int account) {
        if (states[account] == null) {
            states[account] = new State();
        }
        return states[account];
    }

    private static File file(long clientUserId) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        return new File(context.getNoBackupFilesDir(), FILE_PREFIX + clientUserId + FILE_SUFFIX);
    }
}
