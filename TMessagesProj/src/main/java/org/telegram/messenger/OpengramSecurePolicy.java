package org.telegram.messenger;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Side-effect-free decision for whether a one-to-one cloud dialog may carry
 * user-authored content.
 *
 * <p>Threat model: the person holding the phone asked that some peers only be
 * contacted over a secret chat. Ordinary Telegram cloud dialogs are readable
 * by the service, so a cloud send to a protected peer defeats that request.
 * Hiding a compose field is not the boundary. Callers that never show a chat
 * still have to honor {@link Decision}.
 *
 * <p>Exemptions, and why they are safe relative to this objective:
 * encrypted-dialog ids are already secret chats; groups, channels, bots,
 * service accounts, saved messages, and deleted accounts are not person chats
 * this option covers. Read receipts, typing, and online status are not
 * user-authored content and stay under ghost mode.
 *
 * <p>{@link Decision#POLICY_NOT_READY} is fail-closed. A missing baseline,
 * a capture still in progress, or an unreadable snapshot must not be treated
 * as "every chat is old, so cloud sends are allowed."
 */
public final class OpengramSecurePolicy {

    public enum Decision {
        ALLOW,
        REQUIRE_SECRET_CHAT,
        POLICY_NOT_READY
    }

    public enum Phase {
        DISABLED,
        CAPTURING,
        ACTIVE,
        ERROR
    }

    public enum StorageMode {
        NONE,
        STRONGBOX,
        PLAINTEXT_CONSENT
    }

    public enum PeerKind {
        ORDINARY_USER,
        EXEMPT,
        UNKNOWN
    }

    public static final class Input {
        public boolean forceEnabled;
        public boolean forceAll;
        public Phase phase = Phase.DISABLED;
        public PeerKind kind = PeerKind.EXEMPT;
        public long clientUserId;
        public long peerUserId;
        public boolean snapshotReadable;
        public boolean modeMismatch;
        public Set<Long> capturedAccounts = Collections.emptySet();
        public Set<String> baselinePeers = Collections.emptySet();
        public boolean incomingCloudDialog;
    }

    private OpengramSecurePolicy() {
    }

    public static String peerKey(long clientUserId, long peerUserId) {
        return clientUserId + ":" + peerUserId;
    }

    public static boolean allowsCloudTransport(Decision decision) {
        return decision == Decision.ALLOW;
    }

    public static Decision evaluate(Input input) {
        if (input == null || input.kind == PeerKind.EXEMPT) {
            return Decision.ALLOW;
        }
        if (!input.forceEnabled) {
            return Decision.ALLOW;
        }
        if (input.kind == PeerKind.UNKNOWN) {
            return Decision.POLICY_NOT_READY;
        }
        if (input.forceAll) {
            return Decision.REQUIRE_SECRET_CHAT;
        }
        if (input.incomingCloudDialog) {
            return Decision.ALLOW;
        }
        if (input.phase != Phase.ACTIVE) {
            return Decision.POLICY_NOT_READY;
        }
        if (!input.snapshotReadable || input.modeMismatch) {
            return Decision.POLICY_NOT_READY;
        }
        if (input.capturedAccounts == null || !input.capturedAccounts.contains(input.clientUserId)) {
            return Decision.POLICY_NOT_READY;
        }
        if (input.baselinePeers != null && input.baselinePeers.contains(peerKey(input.clientUserId, input.peerUserId))) {
            return Decision.ALLOW;
        }
        return Decision.REQUIRE_SECRET_CHAT;
    }

    /**
     * A pre-schema snapshot cannot be proven. Keeping the feature on without a
     * trustworthy baseline must block cloud sends instead of treating every
     * peer as already known.
     */
    public static Phase migrateLegacy(boolean forceEnabled) {
        return forceEnabled ? Phase.ERROR : Phase.DISABLED;
    }

    public static Input copy(Input input) {
        Input copy = new Input();
        copy.forceEnabled = input.forceEnabled;
        copy.forceAll = input.forceAll;
        copy.phase = input.phase;
        copy.kind = input.kind;
        copy.clientUserId = input.clientUserId;
        copy.peerUserId = input.peerUserId;
        copy.snapshotReadable = input.snapshotReadable;
        copy.modeMismatch = input.modeMismatch;
        copy.capturedAccounts = input.capturedAccounts == null ? Collections.emptySet() : new HashSet<>(input.capturedAccounts);
        copy.baselinePeers = input.baselinePeers == null ? Collections.emptySet() : new HashSet<>(input.baselinePeers);
        copy.incomingCloudDialog = input.incomingCloudDialog;
        return copy;
    }
}
