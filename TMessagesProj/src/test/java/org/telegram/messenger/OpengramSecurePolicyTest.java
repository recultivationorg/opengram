package org.telegram.messenger;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpengramSecurePolicyTest {

    private static OpengramSecurePolicy.Input base() {
        OpengramSecurePolicy.Input input = new OpengramSecurePolicy.Input();
        input.forceEnabled = true;
        input.phase = OpengramSecurePolicy.Phase.ACTIVE;
        input.kind = OpengramSecurePolicy.PeerKind.ORDINARY_USER;
        input.clientUserId = 10L;
        input.peerUserId = 20L;
        input.snapshotReadable = true;
        input.capturedAccounts = new HashSet<>(Collections.singleton(10L));
        input.baselinePeers = new HashSet<>();
        return input;
    }

    @Test
    public void newCloudUserIsBlockedAfterActivation() {
        OpengramSecurePolicy.Input input = base();
        assertEquals(OpengramSecurePolicy.Decision.REQUIRE_SECRET_CHAT, OpengramSecurePolicy.evaluate(input));
        assertFalse(OpengramSecurePolicy.allowsCloudTransport(OpengramSecurePolicy.evaluate(input)));
    }

    @Test
    public void preEnableCloudChatIsAllowedInNewOnlyMode() {
        OpengramSecurePolicy.Input input = base();
        input.baselinePeers.add(OpengramSecurePolicy.peerKey(10L, 20L));
        assertEquals(OpengramSecurePolicy.Decision.ALLOW, OpengramSecurePolicy.evaluate(input));
    }

    @Test
    public void existingCloudChatIsBlockedWhenForceAllIsEnabled() {
        OpengramSecurePolicy.Input input = base();
        input.forceAll = true;
        input.baselinePeers.add(OpengramSecurePolicy.peerKey(10L, 20L));
        input.phase = OpengramSecurePolicy.Phase.ERROR;
        input.snapshotReadable = false;
        assertEquals(OpengramSecurePolicy.Decision.REQUIRE_SECRET_CHAT, OpengramSecurePolicy.evaluate(input));
    }

    @Test
    public void encryptedDialogStaysUsable() {
        OpengramSecurePolicy.Input input = base();
        input.kind = OpengramSecurePolicy.PeerKind.EXEMPT;
        assertEquals(OpengramSecurePolicy.Decision.ALLOW, OpengramSecurePolicy.evaluate(input));
    }

    @Test
    public void botsGroupsSelfAndServiceStayExempt() {
        for (OpengramSecurePolicy.PeerKind kind : Collections.singleton(OpengramSecurePolicy.PeerKind.EXEMPT)) {
            OpengramSecurePolicy.Input input = base();
            input.kind = kind;
            input.peerUserId = -5L;
            assertEquals(OpengramSecurePolicy.Decision.ALLOW, OpengramSecurePolicy.evaluate(input));
        }
    }

    @Test
    public void notificationReplyIsAbsentUnlessAllowed() {
        assertFalse(OpengramSecurePolicy.allowsCloudTransport(OpengramSecurePolicy.Decision.REQUIRE_SECRET_CHAT));
        assertFalse(OpengramSecurePolicy.allowsCloudTransport(OpengramSecurePolicy.Decision.POLICY_NOT_READY));
        assertTrue(OpengramSecurePolicy.allowsCloudTransport(OpengramSecurePolicy.Decision.ALLOW));
    }

    @Test
    public void staleReplyAndDirectSendShareTheSameDecision() {
        OpengramSecurePolicy.Input input = base();
        OpengramSecurePolicy.Decision decision = OpengramSecurePolicy.evaluate(input);
        assertEquals(OpengramSecurePolicy.Decision.REQUIRE_SECRET_CHAT, decision);
        assertFalse(OpengramSecurePolicy.allowsCloudTransport(decision));
    }

    @Test
    public void unknownPeerFailsClosedWhileForceIsOn() {
        OpengramSecurePolicy.Input input = base();
        input.kind = OpengramSecurePolicy.PeerKind.UNKNOWN;
        assertEquals(OpengramSecurePolicy.Decision.POLICY_NOT_READY, OpengramSecurePolicy.evaluate(input));
    }

    @Test
    public void capturingDoesNotFailOpen() {
        OpengramSecurePolicy.Input input = base();
        input.phase = OpengramSecurePolicy.Phase.CAPTURING;
        input.snapshotReadable = false;
        assertEquals(OpengramSecurePolicy.Decision.POLICY_NOT_READY, OpengramSecurePolicy.evaluate(input));
    }

    @Test
    public void errorAndMissingSnapshotFailClosed() {
        OpengramSecurePolicy.Input error = base();
        error.phase = OpengramSecurePolicy.Phase.ERROR;
        assertEquals(OpengramSecurePolicy.Decision.POLICY_NOT_READY, OpengramSecurePolicy.evaluate(error));

        OpengramSecurePolicy.Input missing = base();
        missing.snapshotReadable = false;
        assertEquals(OpengramSecurePolicy.Decision.POLICY_NOT_READY, OpengramSecurePolicy.evaluate(missing));

        OpengramSecurePolicy.Input mismatch = base();
        mismatch.modeMismatch = true;
        assertEquals(OpengramSecurePolicy.Decision.POLICY_NOT_READY, OpengramSecurePolicy.evaluate(mismatch));
    }

    @Test
    public void incomingCloudChatIsAllowedByDefault() {
        OpengramSecurePolicy.Input input = base();
        input.incomingCloudDialog = true;
        assertEquals(OpengramSecurePolicy.Decision.ALLOW, OpengramSecurePolicy.evaluate(input));
    }

    @Test
    public void dialogAfterCutoffIsNotGrandfathered() {
        OpengramSecurePolicy.Input input = base();
        input.baselinePeers.add(OpengramSecurePolicy.peerKey(10L, 1L));
        input.peerUserId = 99L;
        assertEquals(OpengramSecurePolicy.Decision.REQUIRE_SECRET_CHAT, OpengramSecurePolicy.evaluate(input));
    }

    @Test
    public void accountSlotReuseDoesNotInheritAnotherBaseline() {
        OpengramSecurePolicy.Input input = base();
        input.baselinePeers.add(OpengramSecurePolicy.peerKey(10L, 20L));
        input.clientUserId = 77L;
        input.capturedAccounts = new HashSet<>(Arrays.asList(10L));
        assertEquals(OpengramSecurePolicy.Decision.POLICY_NOT_READY, OpengramSecurePolicy.evaluate(input));
    }

    @Test
    public void addedAccountWithoutItsOwnBaselineIsNotTreatedAsOld() {
        OpengramSecurePolicy.Input input = base();
        input.clientUserId = 77L;
        input.capturedAccounts = new HashSet<>(Arrays.asList(10L, 77L));
        input.baselinePeers.add(OpengramSecurePolicy.peerKey(10L, 20L));
        assertEquals(OpengramSecurePolicy.Decision.REQUIRE_SECRET_CHAT, OpengramSecurePolicy.evaluate(input));
    }

    @Test
    public void removedAccountEntriesCannotAuthorizeANewOwner() {
        OpengramSecurePolicy.Input input = base();
        input.capturedAccounts = new HashSet<>(Collections.singleton(77L));
        input.baselinePeers.add(OpengramSecurePolicy.peerKey(77L, 20L));
        input.clientUserId = 10L;
        assertEquals(OpengramSecurePolicy.Decision.POLICY_NOT_READY, OpengramSecurePolicy.evaluate(input));
    }

    @Test
    public void legacyEnabledStateMigratesFailClosed() {
        assertEquals(OpengramSecurePolicy.Phase.ERROR, OpengramSecurePolicy.migrateLegacy(true));
        assertEquals(OpengramSecurePolicy.Phase.DISABLED, OpengramSecurePolicy.migrateLegacy(false));
    }

    @Test
    public void disabledForceAllowsOrdinaryCloudChats() {
        OpengramSecurePolicy.Input input = base();
        input.forceEnabled = false;
        input.phase = OpengramSecurePolicy.Phase.DISABLED;
        assertEquals(OpengramSecurePolicy.Decision.ALLOW, OpengramSecurePolicy.evaluate(input));
    }
}
