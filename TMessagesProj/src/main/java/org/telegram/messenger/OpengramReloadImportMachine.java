package org.telegram.messenger;

import java.util.Arrays;

/**
 * Orders a reload import so the account is activated only after the staged
 * key has been checked against the server. A cancelled or failed attempt
 * stays unfinished until its rollback has been persisted.
 */
public final class OpengramReloadImportMachine {

    public static final int REJECTED = 1;
    public static final int INSTALL_FAILED = 2;
    public static final int VALIDATION_FAILED = 3;
    public static final int ACTIVATION_FAILED = 4;
    public static final int CANCELLED = 5;
    public static final int ROLLBACK_FAILED = 6;

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
    private boolean rollingBack;
    private boolean rollbackRequired;
    private Listener listener;
    private long expectedUserId;

    public OpengramReloadImportMachine(Backend backend) {
        this.backend = backend;
    }

    public boolean needsRollback() {
        synchronized (lock) {
            return rollbackRequired;
        }
    }

    public boolean start(int dcId, byte[] key, long expectedUserId, Listener listener) {
        final int gen;
        synchronized (lock) {
            if (busy || rollingBack || rollbackRequired || key == null || key.length != 256 || expectedUserId <= 0 || listener == null) {
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

    public boolean retryRollback(Listener listener) {
        return requestRollback(listener, true);
    }

    public boolean recoverRollback(Listener listener) {
        return requestRollback(listener, false);
    }

    public void cancel() {
        boolean roll;
        Listener current = null;
        synchronized (lock) {
            if (!busy || rollingBack) {
                return;
            }
            generation++;
            if (!staged) {
                busy = false;
                current = listener;
                listener = null;
                roll = false;
            } else {
                staged = false;
                roll = true;
            }
        }
        backend.cancelPending();
        if (!roll) {
            if (current != null) {
                current.onFailure(CANCELLED);
            }
            return;
        }
        beginRollback(CANCELLED);
    }

    private boolean requestRollback(Listener listener, boolean requirePending) {
        synchronized (lock) {
            if (busy || rollingBack || listener == null) {
                return false;
            }
            if (requirePending && !rollbackRequired) {
                return false;
            }
            busy = true;
            rollingBack = true;
            this.listener = listener;
        }
        backend.rollback(ok -> finishRollback(0, ok));
        return true;
    }

    private void onStaged(int gen, boolean ok) {
        boolean validate = false;
        boolean drop = false;
        int reason = 0;
        synchronized (lock) {
            if (!current(gen)) {
                drop = ok;
            } else if (!ok) {
                reason = INSTALL_FAILED;
                rollingBack = true;
            } else {
                staged = true;
                validate = true;
            }
        }
        if (drop) {
            quietRollback();
        } else if (reason != 0) {
            beginRollback(reason);
        } else if (validate) {
            backend.validate(serverUserId -> onValidated(gen, serverUserId));
        }
    }

    private void onValidated(int gen, long serverUserId) {
        boolean commit = false;
        boolean quiet = false;
        int reason = 0;
        synchronized (lock) {
            if (!current(gen)) {
                quiet = staged && !rollingBack;
            } else if (serverUserId != expectedUserId) {
                reason = VALIDATION_FAILED;
                rollingBack = true;
            } else {
                commit = true;
            }
        }
        if (quiet) {
            quietRollback();
        } else if (reason != 0) {
            beginRollback(reason);
        } else if (commit) {
            backend.commit(ok -> onCommitted(gen, ok, serverUserId));
        }
    }

    private void onCommitted(int gen, boolean ok, long serverUserId) {
        boolean activate = false;
        boolean quiet = false;
        int reason = 0;
        synchronized (lock) {
            if (!current(gen)) {
                quiet = (ok || staged) && !rollingBack;
            } else if (!ok) {
                reason = INSTALL_FAILED;
                rollingBack = true;
            } else {
                activate = true;
            }
        }
        if (quiet) {
            quietRollback();
            return;
        }
        if (reason != 0) {
            beginRollback(reason);
            return;
        }
        if (!activate) {
            return;
        }
        boolean activated;
        try {
            activated = backend.activate(serverUserId);
        } catch (Exception e) {
            activated = false;
        }
        Listener success = null;
        synchronized (lock) {
            if (!activated || !current(gen)) {
                if (!rollingBack) {
                    reason = ACTIVATION_FAILED;
                    rollingBack = true;
                }
            } else {
                staged = false;
                busy = false;
                success = listener;
                listener = null;
            }
        }
        if (reason != 0) {
            beginRollback(reason);
            return;
        }
        if (success != null) {
            success.onSuccess();
        }
    }

    private void beginRollback(int cleanReason) {
        synchronized (lock) {
            rollingBack = true;
            busy = true;
        }
        backend.rollback(ok -> finishRollback(cleanReason, ok));
    }

    private void quietRollback() {
        backend.rollback(ok -> {
            synchronized (lock) {
                if (busy || rollingBack) {
                    return;
                }
                rollbackRequired = !ok;
                staged = !ok;
            }
        });
    }

    private void finishRollback(int cleanReason, boolean ok) {
        Listener current;
        int reason = 0;
        boolean recovered = false;
        synchronized (lock) {
            rollingBack = false;
            busy = false;
            if (!ok) {
                rollbackRequired = true;
                staged = true;
                reason = ROLLBACK_FAILED;
            } else {
                rollbackRequired = false;
                staged = false;
                if (cleanReason == 0) {
                    recovered = true;
                } else {
                    reason = cleanReason;
                }
            }
            current = listener;
            listener = null;
        }
        if (current == null) {
            return;
        }
        if (recovered) {
            current.onSuccess();
        } else {
            current.onFailure(reason);
        }
    }

    private boolean current(int gen) {
        return busy && gen == generation && !rollingBack;
    }
}
