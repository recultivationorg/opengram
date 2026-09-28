package org.telegram.messenger;

import android.content.Context;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Android owner of the force end-to-end state machine. Policy math lives in
 * {@link OpengramSecurePolicy} and does not touch the disk or the keystore.
 */
public final class OpengramSecureChannel {

    private static final int SCHEMA = 2;
    private static final Object lock = new Object();
    private static final Set<Long> capturedAccounts = new HashSet<>();
    private static final Set<String> baselinePeers = new HashSet<>();
    private static final Set<Integer> captureInFlight = new HashSet<>();

    private static boolean memoryLoaded;
    private static boolean snapshotReadable;
    private static boolean modeMismatch;

    private OpengramSecureChannel() {
    }

    public static OpengramSecurePolicy.Decision evaluate(int account, long dialogId) {
        OpengramSecurePolicy.Input input = inputFor(account, dialogId);
        OpengramSecurePolicy.Decision decision = OpengramSecurePolicy.evaluate(input);
        if (decision == OpengramSecurePolicy.Decision.POLICY_NOT_READY && input.forceEnabled && input.phase == OpengramSecurePolicy.Phase.ACTIVE && input.clientUserId != 0 && !input.capturedAccounts.contains(input.clientUserId)) {
            ensureAccountBaseline(account);
        }
        return decision;
    }

    public static OpengramSecurePolicy.Phase phase() {
        return phaseFromInt(SharedConfig.forceEndToEndPhase);
    }

    public static OpengramStrongBox.Probe probe() {
        return OpengramStrongBox.probe();
    }

    public static void onConfigLoaded() {
        synchronized (lock) {
            if (SharedConfig.forceEndToEndSchema < SCHEMA) {
                boolean wasEnabled = SharedConfig.forceEndToEndEncryption;
                SharedConfig.forceEndToEndPhase = OpengramSecurePolicy.migrateLegacy(wasEnabled).ordinal();
                SharedConfig.forceEndToEndStorageMode = OpengramSecurePolicy.StorageMode.NONE.ordinal();
                SharedConfig.forceEndToEndCutoff = 0;
                SharedConfig.forceEndToEndSchema = SCHEMA;
                if (!wasEnabled) {
                    SharedConfig.forceEndToEndForAllChats = false;
                }
                deleteSnapshotFile();
                memoryLoaded = true;
                snapshotReadable = false;
                modeMismatch = false;
                capturedAccounts.clear();
                baselinePeers.clear();
                SharedConfig.saveConfig();
                return;
            }
            reloadMemory();
            if (SharedConfig.forceEndToEndPhase == OpengramSecurePolicy.Phase.ACTIVE.ordinal() && (!snapshotReadable || modeMismatch)) {
                SharedConfig.forceEndToEndPhase = OpengramSecurePolicy.Phase.ERROR.ordinal();
                SharedConfig.saveConfig();
            } else if (SharedConfig.forceEndToEndPhase == OpengramSecurePolicy.Phase.CAPTURING.ordinal()) {
                AndroidUtilities.runOnUIThread(() -> captureAll(false));
            }
        }
    }

    public static void enable(OpengramSecurePolicy.StorageMode mode) {
        if (mode != OpengramSecurePolicy.StorageMode.STRONGBOX && mode != OpengramSecurePolicy.StorageMode.PLAINTEXT_CONSENT) {
            return;
        }
        synchronized (lock) {
            SharedConfig.forceEndToEndEncryption = true;
            SharedConfig.forceEndToEndPhase = OpengramSecurePolicy.Phase.CAPTURING.ordinal();
            SharedConfig.forceEndToEndStorageMode = mode.ordinal();
            SharedConfig.forceEndToEndCutoff = System.currentTimeMillis();
            SharedConfig.forceEndToEndSchema = SCHEMA;
            SharedConfig.forceEndToEndChatsSnapshotted = false;
            capturedAccounts.clear();
            baselinePeers.clear();
            snapshotReadable = false;
            modeMismatch = false;
            memoryLoaded = true;
            SharedConfig.saveConfig();
        }
        captureAll(false);
    }

    public static boolean disable() {
        synchronized (lock) {
            boolean removed = deleteSnapshotFile();
            if (!removed) {
                SharedConfig.forceEndToEndPhase = OpengramSecurePolicy.Phase.ERROR.ordinal();
                SharedConfig.saveConfig();
                return false;
            }
            SharedConfig.forceEndToEndEncryption = false;
            SharedConfig.forceEndToEndForAllChats = false;
            SharedConfig.forceEndToEndCutoff = 0;
            SharedConfig.forceEndToEndStorageMode = OpengramSecurePolicy.StorageMode.NONE.ordinal();
            SharedConfig.forceEndToEndPhase = OpengramSecurePolicy.Phase.DISABLED.ordinal();
            SharedConfig.forceEndToEndChatsSnapshotted = false;
            capturedAccounts.clear();
            baselinePeers.clear();
            snapshotReadable = false;
            modeMismatch = false;
            memoryLoaded = true;
            SharedConfig.saveConfig();
            return true;
        }
    }

    public static void onAccountRemoved(long clientUserId) {
        if (clientUserId == 0) {
            return;
        }
        synchronized (lock) {
            if (phase() == OpengramSecurePolicy.Phase.DISABLED) {
                return;
            }
            reloadMemory();
            capturedAccounts.remove(clientUserId);
            String prefix = clientUserId + ":";
            java.util.Iterator<String> peers = baselinePeers.iterator();
            while (peers.hasNext()) {
                if (peers.next().startsWith(prefix)) {
                    peers.remove();
                }
            }
            if (!writeCurrent(SharedConfig.forceEndToEndCutoff)) {
                SharedConfig.forceEndToEndPhase = OpengramSecurePolicy.Phase.ERROR.ordinal();
                snapshotReadable = false;
                SharedConfig.saveConfig();
            }
        }
    }

    public static void ensureAccountBaseline(int account) {
        if (!UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        long clientUserId = UserConfig.getInstance(account).getClientUserId();
        synchronized (lock) {
            if (phase() != OpengramSecurePolicy.Phase.ACTIVE && phase() != OpengramSecurePolicy.Phase.CAPTURING) {
                return;
            }
            if (clientUserId == 0 || capturedAccounts.contains(clientUserId) || captureInFlight.contains(account)) {
                return;
            }
            captureInFlight.add(account);
        }
        MessagesStorage.getInstance(account).loadPositiveDialogIds(ids -> AndroidUtilities.runOnUIThread(() -> finishAccountCapture(account, clientUserId, ids)));
    }

    private static void captureAll(boolean append) {
        ArrayList<Integer> accounts = new ArrayList<>();
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()) {
                accounts.add(account);
            }
        }
        if (accounts.isEmpty()) {
            synchronized (lock) {
                SharedConfig.forceEndToEndPhase = OpengramSecurePolicy.Phase.ERROR.ordinal();
                SharedConfig.saveConfig();
            }
            return;
        }
        int[] remaining = new int[] {accounts.size()};
        boolean[] failed = new boolean[] {false};
        Set<Long> accountsFound = new LinkedHashSet<>();
        Set<String> peers = new LinkedHashSet<>();
        for (int account : accounts) {
            long clientUserId = UserConfig.getInstance(account).getClientUserId();
            MessagesStorage.getInstance(account).loadPositiveDialogIds(ids -> AndroidUtilities.runOnUIThread(() -> {
                synchronized (lock) {
                    if (ids == null || clientUserId == 0) {
                        failed[0] = true;
                    } else {
                        accountsFound.add(clientUserId);
                        for (int i = 0; i < ids.size(); i++) {
                            peers.add(OpengramSecurePolicy.peerKey(clientUserId, ids.get(i)));
                        }
                    }
                    remaining[0]--;
                    if (remaining[0] == 0) {
                        finishFullCapture(append, failed[0], accountsFound, peers);
                    }
                }
            }));
        }
    }

    private static void finishFullCapture(boolean append, boolean failed, Set<Long> accountsFound, Set<String> peers) {
        if (phase() == OpengramSecurePolicy.Phase.DISABLED) {
            return;
        }
        if (failed) {
            SharedConfig.forceEndToEndPhase = OpengramSecurePolicy.Phase.ERROR.ordinal();
            snapshotReadable = false;
            SharedConfig.forceEndToEndChatsSnapshotted = false;
            SharedConfig.saveConfig();
            return;
        }
        if (append) {
            capturedAccounts.addAll(accountsFound);
            baselinePeers.addAll(peers);
        } else {
            capturedAccounts.clear();
            capturedAccounts.addAll(accountsFound);
            baselinePeers.clear();
            baselinePeers.addAll(peers);
        }
        if (!writeCurrent(SharedConfig.forceEndToEndCutoff)) {
            SharedConfig.forceEndToEndPhase = OpengramSecurePolicy.Phase.ERROR.ordinal();
            snapshotReadable = false;
            SharedConfig.forceEndToEndChatsSnapshotted = false;
        } else {
            SharedConfig.forceEndToEndPhase = OpengramSecurePolicy.Phase.ACTIVE.ordinal();
            snapshotReadable = true;
            modeMismatch = false;
            memoryLoaded = true;
            SharedConfig.forceEndToEndChatsSnapshotted = true;
        }
        SharedConfig.saveConfig();
    }

    private static void finishAccountCapture(int account, long clientUserId, ArrayList<Long> ids) {
        synchronized (lock) {
            captureInFlight.remove(account);
            if (phase() == OpengramSecurePolicy.Phase.DISABLED) {
                return;
            }
            if (ids == null || clientUserId == 0) {
                SharedConfig.forceEndToEndPhase = OpengramSecurePolicy.Phase.ERROR.ordinal();
                snapshotReadable = false;
                SharedConfig.saveConfig();
                return;
            }
            capturedAccounts.add(clientUserId);
            for (int i = 0; i < ids.size(); i++) {
                baselinePeers.add(OpengramSecurePolicy.peerKey(clientUserId, ids.get(i)));
            }
            if (!writeCurrent(SharedConfig.forceEndToEndCutoff)) {
                SharedConfig.forceEndToEndPhase = OpengramSecurePolicy.Phase.ERROR.ordinal();
                snapshotReadable = false;
            } else if (phase() != OpengramSecurePolicy.Phase.CAPTURING) {
                SharedConfig.forceEndToEndPhase = OpengramSecurePolicy.Phase.ACTIVE.ordinal();
                snapshotReadable = true;
                modeMismatch = false;
            }
            SharedConfig.saveConfig();
        }
    }

    private static boolean writeCurrent(long cutoff) {
        int mode = fileMode(storageMode());
        if (mode == 0) {
            return false;
        }
        OpengramSnapshotFile.Sealer sealer = mode == OpengramSnapshotFile.MODE_STRONGBOX ? OpengramStrongBox.SEALER : null;
        long[] accounts = new long[capturedAccounts.size()];
        int index = 0;
        for (Long accountId : capturedAccounts) {
            accounts[index++] = accountId;
        }
        OpengramSnapshotFile.Record record = new OpengramSnapshotFile.Record(mode, cutoff, accounts, new LinkedHashSet<>(baselinePeers));
        return OpengramSnapshotFile.write(snapshotFile(), record, sealer);
    }

    private static void reloadMemory() {
        capturedAccounts.clear();
        baselinePeers.clear();
        snapshotReadable = false;
        modeMismatch = false;
        memoryLoaded = true;
        int mode = fileMode(storageMode());
        if (phase() == OpengramSecurePolicy.Phase.DISABLED || mode == 0) {
            return;
        }
        OpengramSnapshotFile.Sealer sealer = mode == OpengramSnapshotFile.MODE_STRONGBOX ? OpengramStrongBox.SEALER : null;
        OpengramSnapshotFile.ReadResult result = OpengramSnapshotFile.read(snapshotFile(), mode, sealer);
        if (result.status == OpengramSnapshotFile.Status.OK && result.record != null) {
            for (long accountId : result.record.accountIds) {
                capturedAccounts.add(accountId);
            }
            baselinePeers.addAll(result.record.peers);
            snapshotReadable = true;
            return;
        }
        modeMismatch = result.status == OpengramSnapshotFile.Status.MODE_MISMATCH;
        snapshotReadable = false;
    }

    private static OpengramSecurePolicy.Input inputFor(int account, long dialogId) {
        OpengramSecurePolicy.Input input = new OpengramSecurePolicy.Input();
        synchronized (lock) {
            if (!memoryLoaded && SharedConfig.forceEndToEndSchema >= SCHEMA) {
                reloadMemory();
            }
            input.forceEnabled = SharedConfig.forceEndToEndEncryption && phase() != OpengramSecurePolicy.Phase.DISABLED;
            input.forceAll = SharedConfig.forceEndToEndForAllChats;
            input.phase = phase();
            input.clientUserId = UserConfig.getInstance(account).getClientUserId();
            input.peerUserId = dialogId;
            input.kind = peerKind(account, dialogId, input.clientUserId);
            input.incomingCloudDialog = dialogId > 0 && MessagesController.getInstance(account).dialogs_dict.get(dialogId) != null;
            input.snapshotReadable = snapshotReadable;
            input.modeMismatch = modeMismatch;
            input.capturedAccounts = new HashSet<>(capturedAccounts);
            input.baselinePeers = new HashSet<>(baselinePeers);
        }
        return input;
    }

    static OpengramSecurePolicy.PeerKind peerKind(int account, long dialogId, long clientUserId) {
        if (dialogId <= 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return OpengramSecurePolicy.PeerKind.EXEMPT;
        }
        if (dialogId == clientUserId || UserObject.isReplyUser(dialogId) || UserObject.isService(dialogId)) {
            return OpengramSecurePolicy.PeerKind.EXEMPT;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        if (user == null) {
            return OpengramSecurePolicy.PeerKind.UNKNOWN;
        }
        if (user.bot || user.self || UserObject.isDeleted(user)) {
            return OpengramSecurePolicy.PeerKind.EXEMPT;
        }
        return OpengramSecurePolicy.PeerKind.ORDINARY_USER;
    }

    private static OpengramSecurePolicy.StorageMode storageMode() {
        OpengramSecurePolicy.StorageMode[] values = OpengramSecurePolicy.StorageMode.values();
        int index = SharedConfig.forceEndToEndStorageMode;
        if (index < 0 || index >= values.length) {
            return OpengramSecurePolicy.StorageMode.NONE;
        }
        return values[index];
    }

    private static OpengramSecurePolicy.Phase phaseFromInt(int index) {
        OpengramSecurePolicy.Phase[] values = OpengramSecurePolicy.Phase.values();
        if (index < 0 || index >= values.length) {
            return OpengramSecurePolicy.Phase.ERROR;
        }
        return values[index];
    }

    private static int fileMode(OpengramSecurePolicy.StorageMode mode) {
        if (mode == OpengramSecurePolicy.StorageMode.STRONGBOX) {
            return OpengramSnapshotFile.MODE_STRONGBOX;
        }
        if (mode == OpengramSecurePolicy.StorageMode.PLAINTEXT_CONSENT) {
            return OpengramSnapshotFile.MODE_PLAINTEXT;
        }
        return 0;
    }

    private static boolean deleteSnapshotFile() {
        return OpengramSnapshotFile.delete(snapshotFile());
    }

    static File snapshotFile() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        return new File(context.getNoBackupFilesDir(), "opengram-e2e-chats.bin");
    }
}
