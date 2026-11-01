package org.telegram.messenger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpengramReloadImportMachineTest {

    @Test
    public void serverMismatchRollsBackWithoutActivation() {
        Fake backend = new Fake();
        backend.serverId = 8;
        OpengramReloadImportMachine machine = new OpengramReloadImportMachine(backend);
        Result result = new Result();
        assertTrue(machine.start(2, key(), 7, result));
        assertEquals(OpengramReloadImportMachine.VALIDATION_FAILED, result.reason);
        assertEquals(1, backend.rollbacks);
        assertEquals(0, backend.activates);
        assertEquals(0, backend.commits);
    }

    @Test
    public void invalidServerResponseRollsBack() {
        Fake backend = new Fake();
        backend.serverId = 0;
        Result result = new Result();
        assertTrue(new OpengramReloadImportMachine(backend).start(2, key(), 7, result));
        assertEquals(OpengramReloadImportMachine.VALIDATION_FAILED, result.reason);
        assertEquals(1, backend.rollbacks);
        assertEquals(0, backend.activates);
    }

    @Test
    public void successActivatesTheServerUser() {
        Fake backend = new Fake();
        Result result = new Result();
        assertTrue(new OpengramReloadImportMachine(backend).start(2, key(), 7, result));
        assertTrue(result.success);
        assertEquals(1, backend.activates);
        assertEquals(7L, backend.activatedId);
        assertEquals(0, backend.rollbacks);
    }

    @Test
    public void duplicateStartDoesNotStageTwice() {
        Fake backend = new Fake();
        backend.stageNow = false;
        OpengramReloadImportMachine machine = new OpengramReloadImportMachine(backend);
        Result first = new Result();
        Result second = new Result();
        assertTrue(machine.start(2, key(), 7, first));
        assertFalse(machine.start(2, key(), 7, second));
        assertEquals(1, backend.stages);
        backend.fireStage();
        assertTrue(first.success);
        assertFalse(second.success);
    }

    @Test
    public void cancelBeforeStageCompletesRollsBackALateSuccess() {
        Fake backend = new Fake();
        backend.stageNow = false;
        OpengramReloadImportMachine machine = new OpengramReloadImportMachine(backend);
        Result result = new Result();
        machine.start(2, key(), 7, result);
        machine.cancel();
        assertEquals(OpengramReloadImportMachine.CANCELLED, result.reason);
        backend.fireStage();
        assertEquals(1, backend.rollbacks);
        assertEquals(0, backend.commits);
        assertEquals(0, backend.activates);
    }

    @Test
    public void cancelAfterStageRollsBackAndIgnoresValidation() {
        Fake backend = new Fake();
        backend.validateNow = false;
        OpengramReloadImportMachine machine = new OpengramReloadImportMachine(backend);
        Result result = new Result();
        machine.start(2, key(), 7, result);
        assertEquals(1, backend.stages);
        machine.cancel();
        assertEquals(OpengramReloadImportMachine.CANCELLED, result.reason);
        assertEquals(1, backend.rollbacks);
        backend.fireValidate();
        assertEquals(0, backend.commits);
        assertEquals(0, backend.activates);
    }

    @Test
    public void persistenceFailureRollsBack() {
        Fake backend = new Fake();
        backend.commitOk = false;
        Result result = new Result();
        assertTrue(new OpengramReloadImportMachine(backend).start(2, key(), 7, result));
        assertEquals(OpengramReloadImportMachine.INSTALL_FAILED, result.reason);
        assertEquals(1, backend.rollbacks);
        assertEquals(0, backend.activates);
    }

    @Test
    public void activationFailureRollsBack() {
        Fake backend = new Fake();
        backend.activateOk = false;
        Result result = new Result();
        assertTrue(new OpengramReloadImportMachine(backend).start(2, key(), 7, result));
        assertEquals(OpengramReloadImportMachine.ACTIVATION_FAILED, result.reason);
        assertEquals(1, backend.rollbacks);
    }

    @Test
    public void occupiedAccountIsRejectedBeforeStage() {
        Fake backend = new Fake();
        backend.free = false;
        Result result = new Result();
        assertTrue(new OpengramReloadImportMachine(backend).start(2, key(), 7, result));
        assertEquals(OpengramReloadImportMachine.REJECTED, result.reason);
        assertEquals(0, backend.stages);
    }

    @Test
    public void clipboardClearMatchesOnlyTheCopiedPassphrase() {
        char[] phrase = "abandon about".toCharArray();
        assertTrue(OpengramReloadWords.clipboardHolds(phrase, "abandon about"));
        assertFalse(OpengramReloadWords.clipboardHolds(phrase, "abandon other"));
        assertFalse(OpengramReloadWords.clipboardHolds(phrase, null));
        Arrays.fill(phrase, '\0');
    }

    private static byte[] key() {
        byte[] key = new byte[256];
        key[0] = 1;
        return key;
    }

    private static final class Result implements OpengramReloadImportMachine.Listener {
        boolean success;
        int reason = -1;

        @Override
        public void onSuccess() {
            success = true;
        }

        @Override
        public void onFailure(int reason) {
            this.reason = reason;
        }
    }

    private static final class Fake implements OpengramReloadImportMachine.Backend {
        boolean free = true;
        boolean stageOk = true;
        boolean commitOk = true;
        boolean activateOk = true;
        boolean stageNow = true;
        boolean validateNow = true;
        long serverId = 7;
        long activatedId;
        int stages;
        int commits;
        int rollbacks;
        int activates;
        int cancels;
        OpengramReloadImportMachine.Done stageDone;
        final ArrayList<OpengramReloadImportMachine.ValidateDone> validations = new ArrayList<>();

        @Override
        public boolean accountFree(long expectedUserId) {
            return free;
        }

        @Override
        public void stage(int dcId, byte[] key, OpengramReloadImportMachine.Done done) {
            stages++;
            stageDone = done;
            if (stageNow) {
                done.onComplete(stageOk);
            }
        }

        @Override
        public void commit(OpengramReloadImportMachine.Done done) {
            commits++;
            done.onComplete(commitOk);
        }

        @Override
        public void rollback(OpengramReloadImportMachine.Done done) {
            rollbacks++;
            done.onComplete(true);
        }

        @Override
        public void validate(OpengramReloadImportMachine.ValidateDone done) {
            validations.add(done);
            if (validateNow) {
                done.onComplete(serverId);
            }
        }

        @Override
        public boolean activate(long serverUserId) {
            activates++;
            activatedId = serverUserId;
            return activateOk;
        }

        @Override
        public void cancelPending() {
            cancels++;
        }

        void fireStage() {
            stageDone.onComplete(stageOk);
        }

        void fireValidate() {
            validations.get(validations.size() - 1).onComplete(serverId);
        }
    }
}
