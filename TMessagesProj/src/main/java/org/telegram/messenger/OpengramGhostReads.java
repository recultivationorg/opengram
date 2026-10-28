package org.telegram.messenger;

import android.content.Context;
import android.os.Handler;
import android.util.LongSparseArray;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public final class OpengramGhostReads {

    private static final String LEGACY_FILE = "opengram-ghost-reads.bin";
    private static final String FILE_PREFIX = "opengram-ghost-";
    private static final String FILE_SUFFIX = ".bin";
    private static final Object lock = new Object();
    private static LongSparseArray<Integer> epochs;
    private static boolean saveQueued;
    private static LongSparseArray<LongSparseArray<HashSet<Integer>>> readReactions;
    private static LongSparseArray<LongSparseArray<Integer>> storyReads;
    private static LongSparseArray<LongSparseArray<Integer>> serverReactionCounts;
    private static LongSparseArray<LongSparseArray<LongSparseArray<Integer>>> topicReactionCounts;
    private static LongSparseArray<LongSparseArray<HashSet<Integer>>> reactionTombstones;
    private static LongSparseArray<Integer> failedSaveCounts;
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

    public static int reactionCountAfterServer(int localCount, int serverCount, int previousServerCount) {
        if (localCount < 0) {
            localCount = 0;
        }
        if (serverCount < 0) {
            serverCount = 0;
        }
        if (previousServerCount < 0) {
            return reactionCountFromServer(localCount, serverCount);
        }
        int next = localCount + (serverCount - previousServerCount);
        if (next < 0) {
            next = 0;
        }
        if (next > serverCount) {
            next = serverCount;
        }
        return next;
    }

    public static int applyServerReactionCount(int account, long dialogId, int localCount, int serverCount) {
        if (!UserConfig.isGhostMode(account) || dialogId == 0) {
            return serverCount;
        }
        long clientUserId = clientUserId(account);
        if (clientUserId == 0) {
            return reactionCountFromServer(localCount, serverCount);
        }
        int previous;
        boolean store;
        synchronized (lock) {
            store = isLoaded(clientUserId);
            if (!store) {
                previous = -1;
            } else {
                previous = storedServerReactionCount(clientUserId, dialogId);
                rememberServerCount(true, serverCounts(clientUserId), dialogId, serverCount);
            }
        }
        if (store && previous != serverCount) {
            scheduleSave(clientUserId);
        }
        return reactionCountAfterServer(localCount, serverCount, previous);
    }

    public static int applyServerTopicReactionCount(int account, long dialogId, long topicId, int localCount, int serverCount) {
        if (!UserConfig.isGhostMode(account) || dialogId == 0 || topicId == 0) {
            return serverCount;
        }
        long clientUserId = clientUserId(account);
        if (clientUserId == 0) {
            return reactionCountFromServer(localCount, serverCount);
        }
        int previous;
        boolean store;
        synchronized (lock) {
            store = isLoaded(clientUserId);
            if (!store) {
                previous = -1;
            } else {
                previous = storedTopicReactionCount(clientUserId, dialogId, topicId);
                rememberServerCount(true, topicCounts(clientUserId, dialogId), topicId, serverCount);
            }
        }
        if (store && previous != serverCount) {
            scheduleSave(clientUserId);
        }
        return reactionCountAfterServer(localCount, serverCount, previous);
    }

    public static void noteServerReactionCount(int account, long dialogId, int serverCount) {
        if (!UserConfig.isGhostMode(account) || dialogId == 0) {
            return;
        }
        long clientUserId = clientUserId(account);
        if (clientUserId == 0) {
            return;
        }
        boolean changed = false;
        synchronized (lock) {
            if (isLoaded(clientUserId)) {
                int previous = storedServerReactionCount(clientUserId, dialogId);
                changed = previous != serverCount;
                if (changed) {
                    rememberServerCount(true, serverCounts(clientUserId), dialogId, serverCount);
                }
            }
        }
        if (changed) {
            scheduleSave(clientUserId);
        }
    }

    public static void shiftServerReactionCount(int account, long dialogId, int delta) {
        if (!UserConfig.isGhostMode(account) || dialogId == 0 || delta == 0) {
            return;
        }
        long clientUserId = clientUserId(account);
        if (clientUserId == 0) {
            return;
        }
        boolean changed = false;
        synchronized (lock) {
            if (!isLoaded(clientUserId)) {
                return;
            }
            int previous = storedServerReactionCount(clientUserId, dialogId);
            if (previous < 0) {
                return;
            }
            int next = previous + delta;
            if (next < 0) {
                next = 0;
            }
            changed = next != previous;
            if (changed) {
                rememberServerCount(true, serverCounts(clientUserId), dialogId, next);
            }
        }
        if (changed) {
            scheduleSave(clientUserId);
        }
    }

    public static void shiftServerTopicReactionCount(int account, long dialogId, long topicId, int delta) {
        if (!UserConfig.isGhostMode(account) || dialogId == 0 || topicId == 0 || delta == 0) {
            return;
        }
        long clientUserId = clientUserId(account);
        if (clientUserId == 0) {
            return;
        }
        boolean changed = false;
        synchronized (lock) {
            if (!isLoaded(clientUserId)) {
                return;
            }
            int previous = storedTopicReactionCount(clientUserId, dialogId, topicId);
            if (previous < 0) {
                return;
            }
            int next = previous + delta;
            if (next < 0) {
                next = 0;
            }
            changed = next != previous;
            if (changed) {
                rememberServerCount(true, topicCounts(clientUserId, dialogId), topicId, next);
            }
        }
        if (changed) {
            scheduleSave(clientUserId);
        }
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

    static String serverCountLine(long dialogId, int count) {
        return "c," + dialogId + "," + count;
    }

    static String topicCountLine(long dialogId, long topicId, int count) {
        return "t," + dialogId + "," + topicId + "," + count;
    }

    static Entry parse(String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.split(",", -1);
        if (parts.length == 4) {
            if (!"t".equals(parts[0])) {
                return null;
            }
            try {
                long dialogId = Long.parseLong(parts[1]);
                long topicId = Long.parseLong(parts[2]);
                long count = Long.parseLong(parts[3]);
                if (dialogId == 0 || topicId == 0 || count < 0 || count > Integer.MAX_VALUE) {
                    return null;
                }
                return new Entry(dialogId, topicId, (int) count);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (parts.length != 3) {
            return null;
        }
        boolean reaction = "r".equals(parts[0]);
        boolean story = "s".equals(parts[0]);
        boolean serverCount = "c".equals(parts[0]);
        if (!reaction && !story && !serverCount) {
            return null;
        }
        try {
            long dialogId = Long.parseLong(parts[1]);
            long last = Long.parseLong(parts[2]);
            if (dialogId == 0 || last < 0 || last > Integer.MAX_VALUE) {
                return null;
            }
            if (reaction) {
                if (last <= 0) {
                    return null;
                }
                return new Entry(true, false, dialogId, (int) last);
            }
            return new Entry(false, serverCount, dialogId, (int) last);
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
            HashSet<Integer> tombstones = existingTombstones(clientUserId, dialogId);
            if (tombstones != null) {
                tombstones.remove(messageId);
            }
            added = messages(clientUserId, dialogId).add(messageId);
        }
        if (added) {
            scheduleSave(clientUserId);
        }
    }

    public static void unmarkReaction(int account, long dialogId, int messageId) {
        long clientUserId = clientUserId(account);
        if (clientUserId == 0 || dialogId == 0 || messageId <= 0) {
            return;
        }
        boolean removed = false;
        synchronized (lock) {
            if (!isLoaded(clientUserId)) {
                tombstones(clientUserId, dialogId).add(messageId);
                removed = true;
            }
            HashSet<Integer> ids = existingMessages(clientUserId, dialogId);
            if (ids != null && ids.remove(messageId)) {
                removed = true;
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
            if (serverReactionCounts != null) {
                serverReactionCounts.remove(clientUserId);
            }
            if (topicReactionCounts != null) {
                topicReactionCounts.remove(clientUserId);
            }
            if (reactionTombstones != null) {
                reactionTombstones.remove(clientUserId);
            }
            if (failedSaveCounts != null) {
                failedSaveCounts.remove(clientUserId);
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
        final int accountAtClear = account;
        MessagesController.getInstance(accountAtClear).dropPendingReadTasks();
        Utilities.globalQueue.postRunnable(() -> {
            deleteLegacy();
            deleteUser(uid);
        });
    }

    public static void flush(int account) {
        long clientUserId = clientUserId(account);
        if (clientUserId == 0 || !UserConfig.isGhostMode(account)) {
            return;
        }
        Handler handler = Utilities.globalQueue.getHandler();
        if (handler != null && Thread.currentThread() == handler.getLooper().getThread()) {
            saveNow(clientUserId);
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                saveNow(clientUserId);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        for (int pass = 0; pass < 4; pass++) {
            saveDirty();
            boolean more;
            synchronized (lock) {
                more = dirtyUsers != null && dirtyUsers.size() > 0;
                if (!more) {
                    saveQueued = false;
                    return;
                }
            }
        }
        AndroidUtilities.runOnUIThread(() -> {
            boolean post = false;
            synchronized (lock) {
                if (dirtyUsers != null && dirtyUsers.size() > 0) {
                    saveQueued = true;
                    post = true;
                } else {
                    saveQueued = false;
                }
            }
            if (post) {
                Utilities.globalQueue.postRunnable(OpengramGhostReads::drainSaves);
            }
        }, 2000);
    }

    private static void readNow(long clientUserId, int generation) {
        boolean success = false;
        boolean upgrade = false;
        try {
            int epoch;
            synchronized (lock) {
                epoch = currentEpoch(clientUserId);
            }
            if (!ghostEnabled(clientUserId) || clientUserId == 0 || generation != epoch) {
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
        File ledger = file(clientUserId);
        if (ledger == null) {
            rememberDirty(clientUserId);
            return;
        }
        if (!loaded && ledger.exists()) {
            readNow(clientUserId, generation);
            synchronized (lock) {
                loaded = isLoaded(clientUserId);
                generation = currentEpoch(clientUserId);
            }
        }
        if (!loaded && ledger.exists()) {
            failSave(clientUserId);
            return;
        }
        OpengramStrongBox sealer = OpengramStrongBox.forGhost(clientUserId);
        int mode = modeForProbe(sealer.inspect());
        if (mode == 0) {
            failSave(clientUserId);
            return;
        }
        Set<String> lines = new LinkedHashSet<>();
        synchronized (lock) {
            generation = currentEpoch(clientUserId);
            capture(clientUserId, lines);
        }
        OpengramSnapshotFile.Sealer writer = mode == OpengramSnapshotFile.MODE_STRONGBOX ? sealer : null;
        boolean wrote;
        if (!ledger.exists()) {
            wrote = ensureLedgerFile(ledger, mode, clientUserId, lines, writer);
        } else {
            wrote = OpengramSnapshotFile.write(ledger, new OpengramSnapshotFile.Record(mode, 0L, new long[] {clientUserId}, lines), writer);
        }
        boolean stale;
        synchronized (lock) {
            stale = generation != currentEpoch(clientUserId);
        }
        if (stale) {
            deleteUser(clientUserId);
            return;
        }
        if (!wrote) {
            failSave(clientUserId);
            return;
        }
        synchronized (lock) {
            if (generation == currentEpoch(clientUserId)) {
                markLoaded(clientUserId);
                if (failedSaveCounts != null) {
                    failedSaveCounts.remove(clientUserId);
                }
            }
        }
    }

    private static void failSave(long clientUserId) {
        int count;
        synchronized (lock) {
            if (failedSaveCounts == null) {
                failedSaveCounts = new LongSparseArray<>();
            }
            Integer previous = failedSaveCounts.get(clientUserId);
            count = (previous == null ? 0 : previous) + 1;
            failedSaveCounts.put(clientUserId, count);
        }
        rememberDirty(clientUserId);
        long delay = 2000L << Math.min(Math.max(count - 1, 0), 5);
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
        }, delay);
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
        if (probe == OpengramStrongBox.Probe.UNSUPPORTED || sealer == null || !sealer.keyPresent()) {
            return OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_PLAINTEXT, null);
        }
        return OpengramSnapshotFile.read(file, OpengramSnapshotFile.MODE_STRONGBOX, sealer);
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
                applyReactionLine(messages(clientUserId, entry.dialogId), existingTombstones(clientUserId, entry.dialogId), entry.value);
            } else if (entry.topic) {
                applyDiskCount(topicCounts(clientUserId, entry.dialogId), entry.topicId, entry.value);
            } else if (entry.serverCount) {
                applyDiskCount(serverCounts(clientUserId), entry.dialogId, entry.value);
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
                HashSet<Integer> tombstones = existingTombstones(clientUserId, dialogId);
                for (Integer messageId : reactionDialogs.valueAt(d)) {
                    if (messageId != null && messageId > 0 && (tombstones == null || !tombstones.contains(messageId))) {
                        lines.add(reactionLine(dialogId, messageId));
                    }
                }
            }
        }
        LongSparseArray<Integer> serverCounts = serverReactionCounts == null ? null : serverReactionCounts.get(clientUserId);
        if (serverCounts != null) {
            for (int d = 0; d < serverCounts.size(); d++) {
                Integer count = serverCounts.valueAt(d);
                if (count != null && count >= 0) {
                    lines.add(serverCountLine(serverCounts.keyAt(d), count));
                }
            }
        }
        LongSparseArray<LongSparseArray<Integer>> topicDialogs = topicReactionCounts == null ? null : topicReactionCounts.get(clientUserId);
        if (topicDialogs != null) {
            for (int d = 0; d < topicDialogs.size(); d++) {
                long dialogId = topicDialogs.keyAt(d);
                LongSparseArray<Integer> topics = topicDialogs.valueAt(d);
                if (topics == null) {
                    continue;
                }
                for (int t = 0; t < topics.size(); t++) {
                    Integer count = topics.valueAt(t);
                    if (count != null && count >= 0 && topics.keyAt(t) != 0) {
                        lines.add(topicCountLine(dialogId, topics.keyAt(t), count));
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
        final boolean serverCount;
        final boolean topic;
        final long dialogId;
        final long topicId;
        final int value;

        Entry(boolean reaction, long dialogId, int value) {
            this(reaction, false, dialogId, value);
        }

        Entry(boolean reaction, boolean serverCount, long dialogId, int value) {
            this.reaction = reaction;
            this.serverCount = serverCount;
            this.topic = false;
            this.dialogId = dialogId;
            this.topicId = 0;
            this.value = value;
        }

        Entry(long dialogId, long topicId, int value) {
            this.reaction = false;
            this.serverCount = false;
            this.topic = true;
            this.dialogId = dialogId;
            this.topicId = topicId;
            this.value = value;
        }
    }

    static boolean rememberServerCount(boolean loaded, LongSparseArray<Integer> counts, long key, int serverCount) {
        if (!loaded || counts == null) {
            return false;
        }
        counts.put(key, Math.max(0, serverCount));
        return true;
    }

    static void applyDiskCount(LongSparseArray<Integer> counts, long key, int diskCount) {
        if (counts != null && counts.get(key) == null) {
            counts.put(key, diskCount);
        }
    }

    static void applyReactionLine(HashSet<Integer> messages, HashSet<Integer> tombstones, int messageId) {
        if (messageId <= 0 || messages == null) {
            return;
        }
        if (tombstones != null && tombstones.contains(messageId)) {
            return;
        }
        messages.add(messageId);
    }

    static boolean ensureLedgerFile(File ledger, int mode, long clientUserId, Set<String> lines, OpengramSnapshotFile.Sealer sealer) {
        if (ledger == null || clientUserId == 0) {
            return false;
        }
        if (mode != OpengramSnapshotFile.MODE_STRONGBOX && mode != OpengramSnapshotFile.MODE_PLAINTEXT) {
            return false;
        }
        if (mode == OpengramSnapshotFile.MODE_STRONGBOX && sealer == null) {
            return false;
        }
        Set<String> peers = lines == null ? new LinkedHashSet<String>() : lines;
        return OpengramSnapshotFile.write(ledger, new OpengramSnapshotFile.Record(mode, 0L, new long[] {clientUserId}, peers), sealer);
    }

    private static int storedServerReactionCount(long clientUserId, long dialogId) {
        if (serverReactionCounts == null) {
            return -1;
        }
        LongSparseArray<Integer> counts = serverReactionCounts.get(clientUserId);
        if (counts == null) {
            return -1;
        }
        Integer value = counts.get(dialogId);
        return value == null ? -1 : value;
    }

    private static int storedTopicReactionCount(long clientUserId, long dialogId, long topicId) {
        if (topicReactionCounts == null) {
            return -1;
        }
        LongSparseArray<LongSparseArray<Integer>> dialogs = topicReactionCounts.get(clientUserId);
        if (dialogs == null) {
            return -1;
        }
        LongSparseArray<Integer> topics = dialogs.get(dialogId);
        if (topics == null) {
            return -1;
        }
        Integer value = topics.get(topicId);
        return value == null ? -1 : value;
    }

    private static LongSparseArray<Integer> serverCounts(long clientUserId) {
        if (serverReactionCounts == null) {
            serverReactionCounts = new LongSparseArray<>();
        }
        LongSparseArray<Integer> counts = serverReactionCounts.get(clientUserId);
        if (counts == null) {
            counts = new LongSparseArray<>();
            serverReactionCounts.put(clientUserId, counts);
        }
        return counts;
    }

    private static LongSparseArray<Integer> topicCounts(long clientUserId, long dialogId) {
        if (topicReactionCounts == null) {
            topicReactionCounts = new LongSparseArray<>();
        }
        LongSparseArray<LongSparseArray<Integer>> dialogs = topicReactionCounts.get(clientUserId);
        if (dialogs == null) {
            dialogs = new LongSparseArray<>();
            topicReactionCounts.put(clientUserId, dialogs);
        }
        LongSparseArray<Integer> topics = dialogs.get(dialogId);
        if (topics == null) {
            topics = new LongSparseArray<>();
            dialogs.put(dialogId, topics);
        }
        return topics;
    }

    private static HashSet<Integer> tombstones(long clientUserId, long dialogId) {
        if (reactionTombstones == null) {
            reactionTombstones = new LongSparseArray<>();
        }
        LongSparseArray<HashSet<Integer>> dialogs = reactionTombstones.get(clientUserId);
        if (dialogs == null) {
            dialogs = new LongSparseArray<>();
            reactionTombstones.put(clientUserId, dialogs);
        }
        HashSet<Integer> ids = dialogs.get(dialogId);
        if (ids == null) {
            ids = new HashSet<>();
            dialogs.put(dialogId, ids);
        }
        return ids;
    }

    private static HashSet<Integer> existingTombstones(long clientUserId, long dialogId) {
        if (reactionTombstones == null) {
            return null;
        }
        LongSparseArray<HashSet<Integer>> dialogs = reactionTombstones.get(clientUserId);
        if (dialogs == null) {
            return null;
        }
        return dialogs.get(dialogId);
    }

    public interface StoryFloor {
        void raise(long dialogId, int maxReadId);
    }
}
