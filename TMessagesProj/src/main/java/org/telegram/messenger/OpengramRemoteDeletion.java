package org.telegram.messenger;

import android.content.Context;

import androidx.collection.LongSparseArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public final class OpengramRemoteDeletion {

    private static final String FILE_PREFIX = "opengram-deletions-";
    private static final String FILE_SUFFIX = ".bin";
    private static final Object lock = new Object();
    private static final State[] states = new State[UserConfig.MAX_ACCOUNT_COUNT];
    static File directoryOverride;
    static Hook hook;

    static final class Hook {
        boolean remoteDeletion;
        int remoteDeletionMode;
        long clientUserId;
        boolean strongBoxAvailable;
        OpengramSnapshotFile.Sealer sealer;
        File directory;
    }

    private OpengramRemoteDeletion() {
    }

    public static final class Snapshot {
        public final OpengramSnapshotFile.Status status;
        public final int mode;
        public final List<String> records;

        Snapshot(OpengramSnapshotFile.Status status, int mode, List<String> records) {
            this.status = status;
            this.mode = mode;
            this.records = records == null ? Collections.<String>emptyList() : records;
        }
    }

    public static boolean keeps(int account) {
        return validAccount(account) && preferenceOn(account);
    }

    public static long deletionDialogId(long dialogId, long channelId) {
        if (channelId != 0) {
            return -channelId;
        }
        return dialogId;
    }

    public static boolean clearedByAvailableMin(int messageId, int availableMinId) {
        return messageId > 0 && messageId < availableMinId;
    }

    public static ArrayList<Integer> idsNotFound(ArrayList<Integer> requested, LongSparseArray<ArrayList<Integer>> found) {
        ArrayList<Integer> missing = new ArrayList<>();
        if (requested == null) {
            return missing;
        }
        for (int i = 0; i < requested.size(); i++) {
            Integer messageId = requested.get(i);
            if (messageId == null || messageId == 0 || containsId(found, messageId)) {
                continue;
            }
            missing.add(messageId);
        }
        return missing;
    }

    public static boolean enable(int account, int mode) {
        if (!validAccount(account)) {
            return false;
        }
        if (mode != OpengramSnapshotFile.MODE_STRONGBOX && mode != OpengramSnapshotFile.MODE_PLAINTEXT) {
            return false;
        }
        long clientUserId = clientUserId(account);
        if (clientUserId <= 0) {
            return false;
        }
        synchronized (lock) {
            State state = stateLocked(account);
            File target = file(clientUserId);
            Snapshot existing = readRecords(target, clientUserId, sealerFor(clientUserId, OpengramSnapshotFile.MODE_STRONGBOX));
            if (existing.status == OpengramSnapshotFile.Status.OK) {
                if (existing.mode != mode) {
                    if (!strongBoxReady(clientUserId, mode)) {
                        return false;
                    }
                    if (!writeRecords(target, mode, clientUserId, existing.records, sealerFor(clientUserId, mode))) {
                        return false;
                    }
                }
                adopt(state, clientUserId, mode, existing.records, false);
                setPreference(account, true, mode);
            } else if (existing.status != OpengramSnapshotFile.Status.MISSING) {
                OpengramSnapshotFile.delete(target);
                state.loaded = false;
                state.error = false;
                state.records.clear();
                state.clientUserId = clientUserId;
                return false;
            } else {
                if (!strongBoxReady(clientUserId, mode)) {
                    return false;
                }
                if (!writeRecords(target, mode, clientUserId, Collections.<String>emptyList(), sealerFor(clientUserId, mode))) {
                    return false;
                }
                adopt(state, clientUserId, mode, Collections.<String>emptyList(), false);
                setPreference(account, true, mode);
            }
        }
        notifyMarked(account);
        return true;
    }

    public static void disable(int account) {
        if (!validAccount(account)) {
            return;
        }
        long clientUserId = clientUserId(account);
        setPreference(account, false, 0);
        synchronized (lock) {
            State state = stateLocked(account);
            state.loaded = true;
            state.error = false;
            state.clientUserId = clientUserId;
            state.mode = 0;
            state.records.clear();
        }
        if (clientUserId > 0) {
            OpengramSnapshotFile.delete(file(clientUserId));
            if (hook == null) {
                OpengramStrongBox.forDeletions(clientUserId).deleteKey();
            }
        }
        notifyMarked(account);
    }

    public static void wipe(int account) {
        if (!validAccount(account)) {
            return;
        }
        synchronized (lock) {
            State state = stateLocked(account);
            UserConfig config = UserConfig.getInstance(account);
            long clientUserId = config.getClientUserId();
            File target = file(clientUserId);
            state.loaded = true;
            state.error = false;
            state.clientUserId = clientUserId;
            state.records.clear();
            if (target != null && target.exists() && !OpengramSnapshotFile.delete(target)) {
                int mode = state.mode;
                if (mode != OpengramSnapshotFile.MODE_STRONGBOX && mode != OpengramSnapshotFile.MODE_PLAINTEXT) {
                    mode = config.remoteDeletionMode == OpengramSnapshotFile.MODE_PLAINTEXT ? OpengramSnapshotFile.MODE_PLAINTEXT : OpengramSnapshotFile.MODE_STRONGBOX;
                }
                OpengramSnapshotFile.Sealer sealer = mode == OpengramSnapshotFile.MODE_STRONGBOX ? OpengramStrongBox.forDeletions(clientUserId) : null;
                writeRecords(target, mode, clientUserId, Collections.<String>emptyList(), sealer);
            }
        }
        notifyMarked(account);
    }

    public static boolean markMessages(int account, long dialogId, ArrayList<Integer> messageIds) {
        if (dialogId == 0 || messageIds == null || messageIds.isEmpty()) {
            return false;
        }
        ArrayList<String> add = new ArrayList<>();
        for (int i = 0; i < messageIds.size(); i++) {
            Integer messageId = messageIds.get(i);
            if (messageId == null || messageId == 0) {
                continue;
            }
            add.add(messageRecord(dialogId, messageId));
        }
        if (add.isEmpty()) {
            return false;
        }
        return update(account, add, null);
    }

    public static boolean markMessages(int account, LongSparseArray<ArrayList<Integer>> byDialog) {
        if (byDialog == null || byDialog.size() == 0) {
            return false;
        }
        ArrayList<String> add = new ArrayList<>();
        for (int i = 0; i < byDialog.size(); i++) {
            long dialogId = byDialog.keyAt(i);
            ArrayList<Integer> messageIds = byDialog.valueAt(i);
            if (dialogId == 0 || messageIds == null) {
                continue;
            }
            for (int j = 0; j < messageIds.size(); j++) {
                Integer messageId = messageIds.get(j);
                if (messageId == null || messageId == 0) {
                    continue;
                }
                add.add(messageRecord(dialogId, messageId));
            }
        }
        if (add.isEmpty()) {
            return false;
        }
        return update(account, add, null);
    }

    public static boolean markRandoms(int account, long dialogId, ArrayList<Long> randomIds) {
        if (dialogId == 0 || randomIds == null || randomIds.isEmpty()) {
            return false;
        }
        ArrayList<String> add = new ArrayList<>();
        for (int i = 0; i < randomIds.size(); i++) {
            Long randomId = randomIds.get(i);
            if (randomId == null || randomId == 0) {
                continue;
            }
            add.add(randomRecord(dialogId, randomId));
        }
        if (add.isEmpty()) {
            return false;
        }
        return update(account, add, null);
    }

    public static boolean promote(int account, ArrayList<String> randomRecords, ArrayList<String> messageRecords) {
        return update(account, messageRecords, randomRecords);
    }

    public static void forgetMessages(int account, long dialogId, ArrayList<Integer> messageIds) {
        if (dialogId == 0 || messageIds == null || messageIds.isEmpty()) {
            return;
        }
        ArrayList<String> remove = new ArrayList<>();
        for (int i = 0; i < messageIds.size(); i++) {
            Integer messageId = messageIds.get(i);
            if (messageId == null || messageId == 0) {
                continue;
            }
            remove.add(messageRecord(dialogId, messageId));
        }
        update(account, null, remove);
    }

    public static void forgetRandoms(int account, long dialogId, ArrayList<Long> randomIds) {
        if (dialogId == 0 || randomIds == null || randomIds.isEmpty()) {
            return;
        }
        ArrayList<String> remove = new ArrayList<>();
        for (int i = 0; i < randomIds.size(); i++) {
            Long randomId = randomIds.get(i);
            if (randomId == null || randomId == 0) {
                continue;
            }
            remove.add(randomRecord(dialogId, randomId));
        }
        update(account, null, remove);
    }

    public static boolean isMarked(int account, long dialogId, int messageId) {
        if (!keeps(account) || dialogId == 0 || messageId == 0) {
            return false;
        }
        synchronized (lock) {
            ensureLoadedLocked(account);
            State state = stateLocked(account);
            return !state.error && state.records.contains(messageRecord(dialogId, messageId));
        }
    }

    public static boolean isMarked(int account, MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        if (isMarked(account, message.getDialogId(), message.getId())) {
            return true;
        }
        long randomId = message.messageOwner.random_id;
        return randomId != 0 && isMarkedRandom(account, message.getDialogId(), randomId);
    }

    public static boolean randomRecorded(int account, long randomId) {
        if (!keeps(account) || randomId == 0) {
            return false;
        }
        synchronized (lock) {
            ensureLoadedLocked(account);
            State state = stateLocked(account);
            if (state.error) {
                return false;
            }
            for (String record : state.records) {
                if (isRandomRecord(record, randomId)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static ArrayList<Long> unrecordedRandoms(int account, ArrayList<Long> randomIds, ArrayList<Long> found) {
        ArrayList<Long> missing = new ArrayList<>();
        if (randomIds == null) {
            return missing;
        }
        for (int i = 0; i < randomIds.size(); i++) {
            Long randomId = randomIds.get(i);
            if (randomId == null || randomId == 0 || (found != null && found.contains(randomId))) {
                continue;
            }
            if (!randomRecorded(account, randomId)) {
                missing.add(randomId);
            }
        }
        return missing;
    }

    public static void forgetRandomIds(int account, ArrayList<Long> randomIds) {
        if (!validAccount(account) || randomIds == null || randomIds.isEmpty()) {
            return;
        }
        synchronized (lock) {
            ensureLoadedLocked(account);
            State state = stateLocked(account);
            if (state.error) {
                return;
            }
            ArrayList<String> remove = new ArrayList<>();
            for (String record : state.records) {
                for (int i = 0; i < randomIds.size(); i++) {
                    Long randomId = randomIds.get(i);
                    if (randomId != null && isRandomRecord(record, randomId)) {
                        remove.add(record);
                        break;
                    }
                }
            }
            if (remove.isEmpty()) {
                return;
            }
            state.records.removeAll(remove);
            long clientUserId = clientUserId(account);
            int mode = storedMode(state, account);
            if (clientUserId <= 0 || mode == 0 || !writeRecords(file(clientUserId), mode, clientUserId, state.records, sealerFor(clientUserId, mode))) {
                state.records.addAll(remove);
            }
        }
    }

    public static String messageRecord(long dialogId, int messageId) {
        return "d:" + dialogId + ":" + messageId;
    }

    public static String randomRecord(long dialogId, long randomId) {
        return "r:" + dialogId + ":" + randomId;
    }

    public static boolean writeRecords(File file, int mode, long clientUserId, Collection<String> records, OpengramSnapshotFile.Sealer sealer) {
        if (clientUserId <= 0) {
            return false;
        }
        LinkedHashSet<String> peers = new LinkedHashSet<>();
        if (records != null) {
            for (String record : records) {
                if (!validRecord(record)) {
                    return false;
                }
                peers.add(record);
            }
        }
        return OpengramSnapshotFile.write(file, new OpengramSnapshotFile.Record(mode, 0, new long[]{clientUserId}, peers), sealer);
    }

    public static Snapshot readRecords(File file, long clientUserId, OpengramSnapshotFile.Sealer sealer) {
        if (file == null || !file.exists()) {
            return new Snapshot(OpengramSnapshotFile.Status.MISSING, 0, Collections.<String>emptyList());
        }
        OpengramSnapshotFile.ReadResult strong = OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_STRONGBOX, sealer);
        OpengramSnapshotFile.ReadResult plain = OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_PLAINTEXT, null);
        OpengramSnapshotFile.ReadResult chosen;
        if (strong.status == OpengramSnapshotFile.Status.OK) {
            chosen = strong;
        } else if (plain.status == OpengramSnapshotFile.Status.OK) {
            chosen = plain;
        } else if (strong.status == OpengramSnapshotFile.Status.MISSING || plain.status == OpengramSnapshotFile.Status.MISSING) {
            return new Snapshot(OpengramSnapshotFile.Status.MISSING, 0, Collections.<String>emptyList());
        } else {
            return new Snapshot(OpengramSnapshotFile.Status.CORRUPT, 0, Collections.<String>emptyList());
        }
        if (chosen.record == null || chosen.record.accountIds.length != 1 || chosen.record.accountIds[0] != clientUserId) {
            return new Snapshot(OpengramSnapshotFile.Status.CORRUPT, 0, Collections.<String>emptyList());
        }
        ArrayList<String> records = new ArrayList<>();
        for (String record : chosen.record.peers) {
            if (!validRecord(record)) {
                return new Snapshot(OpengramSnapshotFile.Status.CORRUPT, 0, Collections.<String>emptyList());
            }
            records.add(record);
        }
        return new Snapshot(OpengramSnapshotFile.Status.OK, chosen.record.mode, records);
    }

    private static boolean isMarkedRandom(int account, long dialogId, long randomId) {
        if (!validAccount(account) || dialogId == 0 || randomId == 0) {
            return false;
        }
        synchronized (lock) {
            ensureLoadedLocked(account);
            State state = stateLocked(account);
            return keeps(account) && !state.error && state.records.contains(randomRecord(dialogId, randomId));
        }
    }

    private static boolean update(int account, ArrayList<String> add, ArrayList<String> remove) {
        if (!validAccount(account)) {
            return false;
        }
        boolean changed = false;
        synchronized (lock) {
            State state = stateLocked(account);
            if (state.error) {
                state.loaded = false;
            }
            ensureLoadedLocked(account);
            if (state.error) {
                return false;
            }
            LinkedHashSet<String> next = new LinkedHashSet<>(state.records);
            if (remove != null) {
                for (int i = 0; i < remove.size(); i++) {
                    if (next.remove(remove.get(i))) {
                        changed = true;
                    }
                }
            }
            if (add != null) {
                for (int i = 0; i < add.size(); i++) {
                    String record = add.get(i);
                    if (!validRecord(record)) {
                        continue;
                    }
                    if (next.add(record)) {
                        changed = true;
                    }
                }
            }
            if (!changed) {
                return true;
            }
            long clientUserId = clientUserId(account);
            if (clientUserId <= 0) {
                return false;
            }
            int mode = storedMode(state, account);
            if (mode == 0 || !strongBoxReady(clientUserId, mode)) {
                return false;
            }
            OpengramSnapshotFile.Sealer sealer = sealerFor(clientUserId, mode);
            if (!writeRecords(file(clientUserId), mode, clientUserId, next, sealer)) {
                return false;
            }
            state.records.clear();
            state.records.addAll(next);
            state.mode = mode;
            state.error = false;
            state.clientUserId = clientUserId;
        }
        notifyMarked(account);
        return true;
    }

    private static void ensureLoadedLocked(int account) {
        State state = stateLocked(account);
        long clientUserId = clientUserId(account);
        if (state.loaded && state.clientUserId == clientUserId && !state.error) {
            return;
        }
        state.loaded = true;
        state.clientUserId = clientUserId;
        state.records.clear();
        state.error = false;
        state.mode = preferenceMode(account);
        if (clientUserId <= 0) {
            state.error = preferenceOn(account);
            return;
        }
        Snapshot snapshot = readRecords(file(clientUserId), clientUserId, sealerFor(clientUserId, OpengramSnapshotFile.MODE_STRONGBOX));
        if (snapshot.status == OpengramSnapshotFile.Status.OK) {
            state.mode = snapshot.mode;
            state.records.addAll(snapshot.records);
            state.error = false;
        } else if (snapshot.status != OpengramSnapshotFile.Status.MISSING) {
            state.error = true;
        }
    }

    private static void adopt(State state, long clientUserId, int mode, List<String> records, boolean error) {
        state.loaded = true;
        state.error = error;
        state.clientUserId = clientUserId;
        state.mode = mode;
        state.records.clear();
        if (!error && records != null) {
            state.records.addAll(records);
        }
    }

    private static void notifyMarked(int account) {
        if (hook != null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.remoteDeletionMarked));
    }

    private static boolean preferenceOn(int account) {
        if (hook != null) {
            return hook.remoteDeletion;
        }
        return UserConfig.getInstance(account).remoteDeletion;
    }

    private static int preferenceMode(int account) {
        if (hook != null) {
            return hook.remoteDeletionMode;
        }
        return UserConfig.getInstance(account).remoteDeletionMode;
    }

    private static long clientUserId(int account) {
        if (hook != null) {
            return hook.clientUserId;
        }
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static void setPreference(int account, boolean enabled, int mode) {
        if (hook != null) {
            hook.remoteDeletion = enabled;
            hook.remoteDeletionMode = mode;
            return;
        }
        UserConfig config = UserConfig.getInstance(account);
        config.remoteDeletion = enabled;
        config.remoteDeletionMode = mode;
        config.saveConfig(false);
    }

    private static boolean strongBoxReady(long clientUserId, int mode) {
        if (mode != OpengramSnapshotFile.MODE_STRONGBOX) {
            return mode == OpengramSnapshotFile.MODE_PLAINTEXT;
        }
        if (hook != null) {
            return hook.strongBoxAvailable;
        }
        return OpengramStrongBox.forDeletions(clientUserId).probeKey() == OpengramStrongBox.Probe.AVAILABLE;
    }

    private static OpengramSnapshotFile.Sealer sealerFor(long clientUserId, int mode) {
        if (mode != OpengramSnapshotFile.MODE_STRONGBOX) {
            return null;
        }
        if (hook != null) {
            return hook.sealer;
        }
        return OpengramStrongBox.forDeletions(clientUserId);
    }

    private static int storedMode(State state, int account) {
        int mode = state.mode;
        if (mode != OpengramSnapshotFile.MODE_STRONGBOX && mode != OpengramSnapshotFile.MODE_PLAINTEXT) {
            mode = preferenceMode(account);
        }
        if (mode != OpengramSnapshotFile.MODE_STRONGBOX && mode != OpengramSnapshotFile.MODE_PLAINTEXT) {
            return 0;
        }
        return mode;
    }

    private static boolean containsId(LongSparseArray<ArrayList<Integer>> found, int messageId) {
        if (found == null) {
            return false;
        }
        for (int i = 0; i < found.size(); i++) {
            ArrayList<Integer> ids = found.valueAt(i);
            if (ids != null && ids.contains(messageId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRandomRecord(String record, long randomId) {
        if (record == null || record.length() < 5 || record.charAt(0) != 'r' || record.charAt(1) != ':') {
            return false;
        }
        int split = record.lastIndexOf(':');
        if (split <= 1 || split >= record.length() - 1) {
            return false;
        }
        try {
            return Long.parseLong(record.substring(split + 1)) == randomId;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static void resetForTest() {
        hook = null;
        directoryOverride = null;
        for (int i = 0; i < states.length; i++) {
            states[i] = null;
        }
    }

    private static boolean validRecord(String line) {
        if (line == null || line.length() < 5 || line.charAt(1) != ':') {
            return false;
        }
        char kind = line.charAt(0);
        if (kind != 'd' && kind != 'r') {
            return false;
        }
        int split = line.indexOf(':', 2);
        if (split <= 2 || split >= line.length() - 1 || line.indexOf(':', split + 1) >= 0) {
            return false;
        }
        try {
            Long.parseLong(line.substring(2, split));
            long id = Long.parseLong(line.substring(split + 1));
            if (kind == 'd') {
                return id != 0 && id <= Integer.MAX_VALUE && id >= Integer.MIN_VALUE;
            }
            return id != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean validAccount(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT;
    }

    private static State stateLocked(int account) {
        if (states[account] == null) {
            states[account] = new State();
        }
        return states[account];
    }

    private static File file(long clientUserId) {
        if (clientUserId <= 0) {
            return null;
        }
        File directory = directoryOverride != null ? directoryOverride : (hook != null ? hook.directory : null);
        if (directory == null) {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return null;
            }
            directory = context.getNoBackupFilesDir();
        }
        return new File(directory, FILE_PREFIX + clientUserId + FILE_SUFFIX);
    }

    private static final class State {
        boolean loaded;
        boolean error;
        long clientUserId;
        int mode;
        final LinkedHashSet<String> records = new LinkedHashSet<>();
    }
}
