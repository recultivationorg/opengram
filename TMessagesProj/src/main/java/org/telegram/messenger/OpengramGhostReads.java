package org.telegram.messenger;

import android.content.Context;
import android.util.LongSparseArray;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public final class OpengramGhostReads {

    private static final String LEGACY_FILE = "opengram-ghost-reads.bin";
    private static final String FILE_PREFIX = "opengram-ghost-";
    private static final String FILE_SUFFIX = ".bin";
    private static final Object lock = new Object();
    private static LongSparseArray<Integer> epochs;
    private static boolean saveQueued;
    private static LongSparseArray<LongSparseArray<HashSet<Integer>>> readReactions;
    private static LongSparseArray<LongSparseArray<Integer>> storyReads;
    private static LongSparseArray<Boolean> loadedUsers;
    private static LongSparseArray<Boolean> loadingUsers;
    private static LongSparseArray<Boolean> dirtyUsers;

    private OpengramGhostReads() {
    }

    public static int reactionCountFromServer(int localCount, int serverCount) {
        if (localCount < 0) {
            localCount = 0;
        }
        if (serverCount < 0) {
            serverCount = 0;
        }
        return Math.min(localCount, serverCount);
    }

    public static int storyReadId(int localReadId, int serverReadId) {
        return Math.max(localReadId, serverReadId);
    }

    static int modeForProbe(OpengramStrongBox.Probe probe) {
        if (probe == OpengramStrongBox.Probe.AVAILABLE) {
            return OpengramSnapshotFile.MODE_STRONGBOX;
        }
        if (probe == OpengramStrongBox.Probe.UNSUPPORTED) {
            return OpengramSnapshotFile.MODE_PLAINTEXT;
        }
        return 0;
    }

    static String reactionLine(long dialogId, int messageId) {
        return "r," + dialogId + "," + messageId;
    }

    static String storyLine(long dialogId, int maxReadId) {
        return "s," + dialogId + "," + maxReadId;
    }

    static Entry parse(String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.split(",", -1);
        if (parts.length != 3 || (!"r".equals(parts[0]) && !"s".equals(parts[0]))) {
            return null;
        }
        try {
            long dialogId = Long.parseLong(parts[1]);
            long last = Long.parseLong(parts[2]);
            if (dialogId == 0 || last < 0 || last > Integer.MAX_VALUE) {
                return null;
            }
            if ("r".equals(parts[0])) {
                if (last <= 0) {
                    return null;
                }
                return new Entry(true, dialogId, (int) last);
            }
            return new Entry(false, dialogId, (int) last);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void markReaction(int account, long dialogId, int messageId) {
        long clientUserId = clientUserId(account);
        if (!UserConfig.isGhostMode(account) || clientUserId == 0 || dialogId == 0 || messageId <= 0) {
            return;
        }
        boolean added;
        synchronized (lock) {
            added = messages(clientUserId, dialogId).add(messageId);
        }
        if (added) {
            scheduleSave(clientUserId);
        }
    }

    public static void unmarkReaction(int account, long dialogId, int messageId) {
        long clientUserId = clientUserId(account);
        if (clientUserId == 0) {
            return;
        }
        boolean removed = false;
        synchronized (lock) {
            HashSet<Integer> ids = existingMessages(clientUserId, dialogId);
            if (ids != null) {
                removed = ids.remove(messageId);
            }
        }
        if (removed) {
            scheduleSave(clientUserId);
        }
    }

    public static boolean isReactionRead(int account, long dialogId, int messageId) {
        long clientUserId = clientUserId(account);
        if (clientUserId == 0) {
            return false;
        }
        synchronized (lock) {
            HashSet<Integer> ids = existingMessages(clientUserId, dialogId);
            return ids != null && ids.contains(messageId);
        }
    }

    public static void rememberStory(int account, long clientUserId, long dialogId, int maxReadId) {
        long accountUserId = clientUserId(account);
        if (clientUserId == 0) {
            clientUserId = accountUserId;
        }
        if (!UserConfig.isGhostMode(account) || clientUserId == 0 || clientUserId != accountUserId || dialogId == 0 || maxReadId <= 0) {
            return;
        }
        boolean raised;
        synchronized (lock) {
            LongSparseArray<Integer> dialogs = stories(clientUserId);
            Integer previous = dialogs.get(dialogId);
            int next = storyReadId(previous == null ? 0 : previous, maxReadId);
            raised = previous == null || previous != next;
            if (raised) {
                dialogs.put(dialogId, next);
            }
        }
        if (raised) {
            scheduleSave(clientUserId);
        }
    }

    public static void overlayStories(int account, long clientUserId, StoryFloor floor) {
        long accountUserId = clientUserId(account);
        if (clientUserId == 0) {
            clientUserId = accountUserId;
        }
        if (floor == null || !UserConfig.isGhostMode(account) || clientUserId == 0 || clientUserId != accountUserId) {
            return;
        }
        long[] dialogIds;
        int[] reads;
        synchronized (lock) {
            LongSparseArray<Integer> dialogs = existingStories(clientUserId);
            if (dialogs == null || dialogs.size() == 0) {
                return;
            }
            dialogIds = new long[dialogs.size()];
            reads = new int[dialogs.size()];
            for (int i = 0; i < dialogs.size(); i++) {
                dialogIds[i] = dialogs.keyAt(i);
                Integer value = dialogs.valueAt(i);
                reads[i] = value == null ? 0 : value;
            }
        }
        for (int i = 0; i < dialogIds.length; i++) {
            if (reads[i] > 0) {
                floor.raise(dialogIds[i], reads[i]);
            }
        }
    }

    public static void onEnabled(int account) {
        if (!UserConfig.isGhostMode(account)) {
            return;
        }
        ensure(account, true);
    }

    public static void load(int account) {
        long uid = clientUserId(account);
        File ledger = uid == 0 ? null : file(uid);
        ensure(account, uid != 0 && (ledger == null || !ledger.exists()));
    }

    private static void ensure(int account, boolean create) {
        long clientUserId = clientUserId(account);
        if (!UserConfig.isGhostMode(account) || clientUserId == 0) {
            return;
        }
        int generation;
        synchronized (lock) {
            if (isLoaded(clientUserId) || isLoading(clientUserId)) {
                return;
            }
            generation = currentEpoch(clientUserId);
            markLoading(clientUserId);
        }
        final long uid = clientUserId;
        final int generationAtStart = generation;
        Utilities.globalQueue.postRunnable(() -> {
            if (create) {
                generate(uid, generationAtStart);
            } else {
                readNow(uid, generationAtStart);
            }
        });
    }

    private static void generate(long clientUserId, int generation) {
        if (!ghostEnabled(clientUserId) || clientUserId == 0 || generation != currentEpoch(clientUserId)) {
            abandon(clientUserId, generation);
            return;
        }
        File ledger = file(clientUserId);
        if (ledger == null) {
            abandon(clientUserId, generation);
            return;
        }
        if (!ledger.exists()) {
            OpengramStrongBox sealer = OpengramStrongBox.forGhost(clientUserId);
            int mode = modeForProbe(sealer.inspect());
            if (mode == 0 || generation != currentEpoch(clientUserId) || !ghostEnabled(clientUserId)) {
                abandon(clientUserId, generation);
                return;
            }
            OpengramSnapshotFile.Sealer writer = mode == OpengramSnapshotFile.MODE_STRONGBOX ? sealer : null;
            boolean wrote = OpengramSnapshotFile.write(ledger, new OpengramSnapshotFile.Record(mode, 0L, new long[] {clientUserId}, new LinkedHashSet<String>()), writer);
            if (!wrote || generation != currentEpoch(clientUserId) || !ghostEnabled(clientUserId)) {
                if (ledger.exists()) {
                    ledger.delete();
                }
                if (mode == OpengramSnapshotFile.MODE_STRONGBOX) {
                    sealer.deleteKey();
                }
                abandon(clientUserId, generation);
                return;
            }
        }
        readNow(clientUserId, generation);
        boolean dirty;
        synchronized (lock) {
            dirty = generation == currentEpoch(clientUserId) && dirtyUsers != null && dirtyUsers.get(clientUserId) != null;
        }
        if (dirty) {
            scheduleSave(clientUserId);
        }
    }

    private static void abandon(long clientUserId, int generation) {
        synchronized (lock) {
            if (generation == currentEpoch(clientUserId) && loadingUsers != null) {
                loadingUsers.remove(clientUserId);
            }
        }
    }

    public static void clear(int account) {
        long clientUserId = clientUserId(account);
        if (clientUserId == 0) {
            return;
        }
        synchronized (lock) {
            nextEpoch(clientUserId);
            if (readReactions != null) {
                readReactions.remove(clientUserId);
            }
            if (storyReads != null) {
                storyReads.remove(clientUserId);
            }
            if (loadedUsers != null) {
                loadedUsers.remove(clientUserId);
            }
            if (loadingUsers != null) {
                loadingUsers.remove(clientUserId);
            }
            if (dirtyUsers != null) {
                dirtyUsers.remove(clientUserId);
            }
        }
        final long uid = clientUserId;
        Utilities.globalQueue.postRunnable(() -> {
            deleteLegacy();
            deleteUser(uid);
        });
    }

    private static void scheduleSave(long clientUserId) {
        if (!ghostEnabled(clientUserId) || clientUserId == 0) {
            return;
        }
        boolean post = false;
        synchronized (lock) {
            if (dirtyUsers == null) {
                dirtyUsers = new LongSparseArray<>();
            }
            dirtyUsers.put(clientUserId, Boolean.TRUE);
            if (!saveQueued) {
                saveQueued = true;
                post = true;
            }
        }
        if (post) {
            Utilities.globalQueue.postRunnable(OpengramGhostReads::drainSaves);
        }
    }

    private static void drainSaves() {
        saveDirty();
        synchronized (lock) {
            saveQueued = false;
        }
    }

    private static void readNow(long clientUserId, int generation) {
        boolean success = false;
        boolean upgrade = false;
        try {
            if (!ghostEnabled(clientUserId) || clientUserId == 0 || generation != currentEpoch(clientUserId)) {
                return;
            }
            File ledger = file(clientUserId);
            if (ledger == null) {
                return;
            }
            deleteLegacy();
            OpengramStrongBox sealer = OpengramStrongBox.forGhost(clientUserId);
            OpengramStrongBox.Probe probe = sealer.inspect();
            OpengramSnapshotFile.ReadResult result = readForProbe(ledger, probe, sealer);
            if (result == null) {
                return;
            }
            if (result.status != OpengramSnapshotFile.Status.OK || result.record == null || !owns(result.record, clientUserId)) {
                return;
            }
            synchronized (lock) {
                if (generation != currentEpoch(clientUserId) || isLoaded(clientUserId)) {
                    return;
                }
                applyLines(clientUserId, result.record.peers);
                upgrade = probe == OpengramStrongBox.Probe.AVAILABLE && result.record.mode == OpengramSnapshotFile.MODE_PLAINTEXT;
                markLoaded(clientUserId);
                success = true;
            }
        } finally {
            synchronized (lock) {
                if (generation == currentEpoch(clientUserId) && loadingUsers != null) {
                    loadingUsers.remove(clientUserId);
                }
            }
        }
        if (success) {
            if (upgrade) {
                scheduleSave(clientUserId);
            }
            AndroidUtilities.runOnUIThread(OpengramGhostReads::publishStories);
        }
    }

    private static void publishStories() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!UserConfig.getInstance(account).isClientActivated()) {
                continue;
            }
            MessagesController.getInstance(account).getStoriesController().mergeGhostStoryReads();
        }
    }

    private static void saveDirty() {
        long[] uids;
        synchronized (lock) {
            if (dirtyUsers == null || dirtyUsers.size() == 0) {
                return;
            }
            uids = new long[dirtyUsers.size()];
            for (int i = 0; i < dirtyUsers.size(); i++) {
                uids[i] = dirtyUsers.keyAt(i);
            }
            dirtyUsers.clear();
        }
        for (int i = 0; i < uids.length; i++) {
            saveNow(uids[i]);
        }
    }

    private static void saveNow(long clientUserId) {
        if (clientUserId == 0) {
            return;
        }
        if (!ghostEnabled(clientUserId)) {
            return;
        }
        boolean loaded;
        int generation;
        synchronized (lock) {
            loaded = isLoaded(clientUserId);
            generation = currentEpoch(clientUserId);
            if (!loaded && !isLoading(clientUserId)) {
                markLoading(clientUserId);
            }
        }
        if (!loaded) {
            readNow(clientUserId, generation);
        }
        synchronized (lock) {
            loaded = isLoaded(clientUserId);
        }
        if (!loaded) {
            rememberDirty(clientUserId);
            return;
        }
        File ledger = file(clientUserId);
        if (ledger == null || !ledger.exists()) {
            rememberDirty(clientUserId);
            return;
        }
        OpengramStrongBox sealer = OpengramStrongBox.forGhost(clientUserId);
        int mode = modeForProbe(sealer.inspect());
        if (mode == 0) {
            failSave(clientUserId);
            return;
        }
        Set<String> lines = new LinkedHashSet<>();
        int generation;
        synchronized (lock) {
            generation = currentEpoch(clientUserId);
            capture(clientUserId, lines);
        }
        OpengramSnapshotFile.Sealer writer = mode == OpengramSnapshotFile.MODE_STRONGBOX ? sealer : null;
        boolean wrote = OpengramSnapshotFile.write(ledger, new OpengramSnapshotFile.Record(mode, 0L, new long[] {clientUserId}, lines), writer);
        if (generation != currentEpoch(clientUserId)) {
            deleteUser(clientUserId);
            return;
        }
        if (!wrote) {
            failSave(clientUserId);
            return;
        }
        failedSaves = 0;
    }

    private static int failedSaves;

    private static void failSave(long clientUserId) {
        rememberDirty(clientUserId);
        if (failedSaves >= 3) {
            return;
        }
        failedSaves++;
        AndroidUtilities.runOnUIThread(() -> {
            boolean post = false;
            synchronized (lock) {
                if (ghostEnabled(clientUserId) && !saveQueued && dirtyUsers != null && dirtyUsers.size() > 0) {
                    saveQueued = true;
                    post = true;
                }
            }
            if (post) {
                Utilities.globalQueue.postRunnable(OpengramGhostReads::drainSaves);
            }
        }, 2000);
    }

    private static void rememberDirty(long clientUserId) {
        synchronized (lock) {
            if (!ghostEnabled(clientUserId)) {
                return;
            }
            if (dirtyUsers == null) {
                dirtyUsers = new LongSparseArray<>();
            }
            dirtyUsers.put(clientUserId, Boolean.TRUE);
        }
    }

    private static boolean isLoaded(long clientUserId) {
        return loadedUsers != null && loadedUsers.get(clientUserId) != null;
    }

    private static boolean isLoading(long clientUserId) {
        return loadingUsers != null && loadingUsers.get(clientUserId) != null;
    }

    private static void markLoading(long clientUserId) {
        if (loadingUsers == null) {
            loadingUsers = new LongSparseArray<>();
        }
        loadingUsers.put(clientUserId, Boolean.TRUE);
    }

    private static void markLoaded(long clientUserId) {
        if (loadedUsers == null) {
            loadedUsers = new LongSparseArray<>();
        }
        loadedUsers.put(clientUserId, Boolean.TRUE);
    }

    private static OpengramSnapshotFile.ReadResult readForProbe(File file, OpengramStrongBox.Probe probe, OpengramStrongBox sealer) {
        if (file == null) {
            return null;
        }
        if (probe == OpengramStrongBox.Probe.UNSUPPORTED) {
            return OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_PLAINTEXT, null);
        }
        OpengramSnapshotFile.ReadResult sealed = OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_STRONGBOX, sealer);
        if (probe == OpengramStrongBox.Probe.ERROR || sealed.status != OpengramSnapshotFile.Status.MODE_MISMATCH) {
            return sealed;
        }
        return OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_PLAINTEXT, null);
    }

    private static boolean owns(OpengramSnapshotFile.Record record, long clientUserId) {
        return record.accountIds != null && record.accountIds.length == 1 && record.accountIds[0] == clientUserId;
    }

    private static void applyLines(long clientUserId, Set<String> lines) {
        if (lines == null) {
            return;
        }
        for (String line : lines) {
            Entry entry = parse(line);
            if (entry == null) {
                continue;
            }
            if (entry.reaction) {
                messages(clientUserId, entry.dialogId).add(entry.value);
            } else {
                LongSparseArray<Integer> dialogs = stories(clientUserId);
                Integer previous = dialogs.get(entry.dialogId);
                dialogs.put(entry.dialogId, storyReadId(previous == null ? 0 : previous, entry.value));
            }
        }
    }

    private static void capture(long clientUserId, Set<String> lines) {
        LongSparseArray<HashSet<Integer>> reactionDialogs = readReactions == null ? null : readReactions.get(clientUserId);
        if (reactionDialogs != null) {
            for (int d = 0; d < reactionDialogs.size(); d++) {
                long dialogId = reactionDialogs.keyAt(d);
                for (Integer messageId : reactionDialogs.valueAt(d)) {
                    if (messageId != null && messageId > 0) {
                        lines.add(reactionLine(dialogId, messageId));
                    }
                }
            }
        }
        LongSparseArray<Integer> storyDialogs = storyReads == null ? null : storyReads.get(clientUserId);
        if (storyDialogs != null) {
            for (int d = 0; d < storyDialogs.size(); d++) {
                Integer maxReadId = storyDialogs.valueAt(d);
                if (maxReadId != null && maxReadId > 0) {
                    lines.add(storyLine(storyDialogs.keyAt(d), maxReadId));
                }
            }
        }
    }

    private static void deleteUser(long clientUserId) {
        OpengramSnapshotFile.delete(file(clientUserId));
        OpengramStrongBox.forGhost(clientUserId).deleteKey();
    }

    private static void deleteLegacy() {
        File directory = directory();
        if (directory == null) {
            return;
        }
        OpengramSnapshotFile.delete(new File(directory, LEGACY_FILE));
    }

    private static boolean ghostEnabled(long clientUserId) {
        if (clientUserId == 0) {
            return false;
        }
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).getClientUserId() == clientUserId) {
                return UserConfig.isGhostMode(account);
            }
        }
        return false;
    }

    private static int currentEpoch(long clientUserId) {
        if (epochs == null) {
            return 0;
        }
        Integer value = epochs.get(clientUserId);
        return value == null ? 0 : value;
    }

    private static void nextEpoch(long clientUserId) {
        if (epochs == null) {
            epochs = new LongSparseArray<>();
        }
        epochs.put(clientUserId, currentEpoch(clientUserId) + 1);
    }

    private static long clientUserId(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return 0;
        }
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static File file(long clientUserId) {
        File directory = directory();
        if (directory == null) {
            return null;
        }
        return new File(directory, FILE_PREFIX + clientUserId + FILE_SUFFIX);
    }

    private static File directory() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        return context.getNoBackupFilesDir();
    }

    private static HashSet<Integer> messages(long clientUserId, long dialogId) {
        if (readReactions == null) {
            readReactions = new LongSparseArray<>();
        }
        LongSparseArray<HashSet<Integer>> dialogs = readReactions.get(clientUserId);
        if (dialogs == null) {
            dialogs = new LongSparseArray<>();
            readReactions.put(clientUserId, dialogs);
        }
        HashSet<Integer> ids = dialogs.get(dialogId);
        if (ids == null) {
            ids = new HashSet<>();
            dialogs.put(dialogId, ids);
        }
        return ids;
    }

    private static HashSet<Integer> existingMessages(long clientUserId, long dialogId) {
        if (readReactions == null) {
            return null;
        }
        LongSparseArray<HashSet<Integer>> dialogs = readReactions.get(clientUserId);
        if (dialogs == null) {
            return null;
        }
        return dialogs.get(dialogId);
    }

    private static LongSparseArray<Integer> stories(long clientUserId) {
        if (storyReads == null) {
            storyReads = new LongSparseArray<>();
        }
        LongSparseArray<Integer> dialogs = storyReads.get(clientUserId);
        if (dialogs == null) {
            dialogs = new LongSparseArray<>();
            storyReads.put(clientUserId, dialogs);
        }
        return dialogs;
    }

    private static LongSparseArray<Integer> existingStories(long clientUserId) {
        if (storyReads == null) {
            return null;
        }
        return storyReads.get(clientUserId);
    }

    static final class Entry {
        final boolean reaction;
        final long dialogId;
        final int value;

        Entry(boolean reaction, long dialogId, int value) {
            this.reaction = reaction;
            this.dialogId = dialogId;
            this.value = value;
        }
    }

    public interface StoryFloor {
        void raise(long dialogId, int maxReadId);
    }
}
