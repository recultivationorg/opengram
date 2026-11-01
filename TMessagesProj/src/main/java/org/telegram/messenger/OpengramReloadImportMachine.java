package org.telegram.messenger;

import java.util.Arrays;

/**
 * Orders a reload import so the account is activated only after the staged
 * key has been checked against the server. A cancelled or stale result rolls
 * the key back and cannot activate the account.
 */
public final class OpengramReloadImportMachine {

    public static final int REJECTED = 1;
    public static final int INSTALL_FAILED = 2;
    public static final int VALIDATION_FAILED = 3;
    public static final int ACTIVATION_FAILED = 4;
    public static final int CANCELLED = 5;

    public interface Done {
        void onComplete(boolean ok);
    }

    public interface ValidateDone {
        void onComplete(long serverUserId);
    }

    public interface Backend {
        boolean accountFree(long expectedUserId);

        void stage(int dcId, byte[] key, Done done);

        void commit(Done done);

        void rollback(Done done);

        void validate(ValidateDone done);

        boolean activate(long serverUserId);

        void cancelPending();
    }

    public interface Listener {
        void onSuccess();

        void onFailure(int reason);
    }

    private final Object lock = new Object();
    private final Backend backend;
    private int generation;
    private boolean busy;
    private boolean staged;
    private Listener listener;
    private long expectedUserId;

    public OpengramReloadImportMachine(Backend backend) {
        this.backend = backend;
    }

    public boolean start(int dcId, byte[] key, long expectedUserId, Listener listener) {
        final int gen;
        synchronized (lock) {
            if (busy || key == null || key.length != 256 || expectedUserId <= 0 || listener == null) {
                return false;
            }
            if (!backend.accountFree(expectedUserId)) {
                listener.onFailure(REJECTED);
                return true;
            }
            busy = true;
            staged = false;
            this.listener = listener;
            this.expectedUserId = expectedUserId;
            gen = ++generation;
        }
        byte[] copy = Arrays.copyOf(key, key.length);
        try {
            backend.stage(dcId, copy, ok -> onStaged(gen, ok));
        } finally {
            Arrays.fill(copy, (byte) 0);
        }
        return true;
    }

    public void cancel() {
        Listener current;
        boolean roll;
        synchronized (lock) {
            if (!busy) {
                return;
            }
            generation++;
            busy = false;
            roll = staged;
            staged = false;
            current = listener;
            listener = null;
        }
        backend.cancelPending();
        if (roll) {
            backend.rollback(ok -> { });
        }
        if (current != null) {
            current.onFailure(CANCELLED);
        }
    }

    private void onStaged(int gen, boolean ok) {
        boolean validate = false;
        boolean drop = false;
        synchronized (lock) {
            if (!current(gen)) {
                drop = ok;
            } else if (!ok) {
                fail(INSTALL_FAILED);
            } else {
                staged = true;
                validate = true;
            }
        }
        if (drop) {
            backend.rollback(ignored -> { });
        } else if (validate) {
            backend.validate(serverUserId -> onValidated(gen, serverUserId));
        }
    }

    private void onValidated(int gen, long serverUserId) {
        boolean commit = false;
        boolean roll = false;
        int reason = 0;
        synchronized (lock) {
            if (!current(gen)) {
                roll = staged;
            } else if (serverUserId != expectedUserId) {
                roll = true;
                staged = false;
                reason = VALIDATION_FAILED;
            } else {
                commit = true;
            }
        }
        if (roll) {
            backend.rollback(ignored -> { });
        }
        if (reason != 0) {
            synchronized (lock) {
                fail(reason);
            }
        } else if (commit) {
            backend.commit(ok -> onCommitted(gen, ok, serverUserId));
        }
    }

    private void onCommitted(int gen, boolean ok, long serverUserId) {
        boolean activate = false;
        boolean roll = false;
        int reason = 0;
        synchronized (lock) {
            if (!current(gen)) {
                roll = ok || staged;
            } else if (!ok) {
                roll = true;
                staged = false;
                reason = INSTALL_FAILED;
            } else {
                activate = true;
            }
        }
        if (roll && !activate) {
            backend.rollback(ignored -> { });
        }
        if (reason != 0) {
            synchronized (lock) {
                fail(reason);
            }
            return;
        }
        if (!activate) {
            return;
        }
        boolean activated = false;
        try {
            activated = backend.activate(serverUserId);
        } catch (Exception e) {
            activated = false;
        }
        Listener success = null;
        synchronized (lock) {
            if (!activated || !current(gen)) {
                roll = true;
                reason = ACTIVATION_FAILED;
                staged = false;
            } else {
                staged = false;
                busy = false;
                success = listener;
                listener = null;
            }
        }
        if (roll) {
            backend.rollback(ignored -> { });
            synchronized (lock) {
                fail(reason);
            }
            return;
        }
        if (success != null) {
            success.onSuccess();
        }
    }

    private boolean current(int gen) {
        return busy && gen == generation;
    }

    private void fail(int reason) {
        busy = false;
        staged = false;
        Listener current = listener;
        listener = null;
        if (current != null) {
            current.onFailure(reason);
        }
    }
}
