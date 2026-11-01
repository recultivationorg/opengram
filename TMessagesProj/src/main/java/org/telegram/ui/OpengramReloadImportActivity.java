package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.OpengramReloadCrypto;
import org.telegram.messenger.OpengramReloadImportMachine;
import org.telegram.messenger.OpengramReloadWords;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

public class OpengramReloadImportActivity extends BaseFragment {

    private static final int REQUEST_OPEN = 41;

    private final OpengramReloadWindow secure = new OpengramReloadWindow();
    private ButtonWithCounterView fileButton;
    private ButtonWithCounterView loadButton;
    private EditTextBoldCursor passphraseField;
    private Uri fileUri;
    private AlertDialog progress;
    private int reloadOp;
    private int validateToken;
    private boolean accepted;
    private boolean destroyed;
    private TLRPC.User serverUser;
    private final OpengramReloadImportMachine machine = new OpengramReloadImportMachine(new ImportBackend());
    private static final boolean[] rollbackPending = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.OpengramReload));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        OpengramReloadChrome chrome = new OpengramReloadChrome(context);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.addView(chrome.page, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        LinearLayout column = chrome.column(true);
        LinearLayout card = chrome.card();
        card.addView(chrome.body(getString(R.string.OpengramReloadImportInfo)));

        passphraseField = new EditTextBoldCursor(context);
        passphraseField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        passphraseField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        passphraseField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        passphraseField.setHint(getString(R.string.OpengramReloadPassphraseHint));
        chrome.roundField(passphraseField);
        passphraseField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        passphraseField.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        passphraseField.setSingleLine(false);
        passphraseField.setMinLines(3);
        passphraseField.setMaxLines(6);
        passphraseField.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(passphraseField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 16f, 0f, 0f));
        column.addView(card);

        fileButton = chrome.button(getString(R.string.OpengramReloadChooseFile), v -> openPicker());
        column.addView(fileButton);
        loadButton = chrome.button(getString(R.string.OpengramReloadImportAction), v -> load());
        column.addView(loadButton);
        chrome.show(column, true);
        secure.acquire(getParentActivity());
        return fragmentView = scrollView;
    }

    @Override
    public void onResume() {
        super.onResume();
        secure.acquire(getParentActivity());
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (!accepted) {
            if (needsRecovery()) {
                recover(null);
            } else {
                machine.cancel();
            }
        }
        if (validateToken != 0) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(validateToken, false);
            validateToken = 0;
        }
        wipe();
        secure.release(getParentActivity());
        super.onFragmentDestroy();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_OPEN || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        fileUri = data.getData();
        fileButton.setText(displayName(fileUri));
    }

    private void openPicker() {
        if (getParentActivity() == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_OPEN);
    }

    private void load() {
        if (getParentActivity() == null || loadButton == null || !loadButton.isEnabled()) {
            return;
        }
        if (needsRecovery()) {
            recover(this::load);
            return;
        }
        if (fileUri == null || passphraseField.getText() == null) {
            return;
        }
        int length = passphraseField.getText().length();
        char[] passphrase = new char[length];
        passphraseField.getText().getChars(0, length, passphrase, 0);
        passphraseField.setText("");
        loadButton.setEnabled(false);
        progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.show();
        final int account = currentAccount;
        final Uri uri = fileUri;
        final ContentResolver resolver = getParentActivity().getContentResolver();
        Utilities.globalQueue.postRunnable(() -> {
            byte[] file = readFile(resolver, uri);
            OpengramReloadWords.Phrase phrase = OpengramReloadWords.parse(passphrase);
            Arrays.fill(passphrase, '\0');
            char[] normalized = phrase == null ? null : phrase.toPassphrase();
            OpengramReloadCrypto.Opened opened = normalized == null || file == null ? null : OpengramReloadCrypto.open(file, normalized);
            if (normalized != null) {
                Arrays.fill(normalized, '\0');
            }
            wipeBytes(file);
            String early = opened == null ? getString(R.string.OpengramReloadImportFailed) : rejection(account, opened.userId);
            AndroidUtilities.runOnUIThread(() -> {
                if (isFinishing()) {
                    if (opened != null) {
                        opened.wipe();
                    }
                    return;
                }
                if (early != null) {
                    if (opened != null) {
                        opened.wipe();
                    }
                    finishAttempt(early);
                    return;
                }
                final long userId = opened.userId;
                boolean started = machine.start(opened.dcId, opened.authKey, userId, new OpengramReloadImportMachine.Listener() {
                    @Override
                    public void onSuccess() {
                        setRollbackPending(currentAccount, false);
                        dismiss(progress);
                    }

                    @Override
                    public void onFailure(int reason) {
                        setRollbackPending(currentAccount, reason == OpengramReloadImportMachine.ROLLBACK_FAILED);
                        if (destroyed) {
                            dismiss(progress);
                            return;
                        }
                        finishAttempt(failureMessage(reason, userId));
                    }
                });
                opened.wipe();
                if (!started) {
                    finishAttempt(needsRecovery() ? getString(R.string.OpengramReloadRollbackFailed) : getString(R.string.OpengramReloadImportFailed));
                }
            });
        });
    }

    private boolean needsRecovery() {
        return machine.needsRollback() || pendingRollback(currentAccount);
    }

    private void recover(Runnable next) {
        if (loadButton != null) {
            loadButton.setEnabled(false);
        }
        if (progress == null && getParentActivity() != null && !destroyed) {
            progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
            progress.show();
        }
        OpengramReloadImportMachine.Listener listener = new OpengramReloadImportMachine.Listener() {
            @Override
            public void onSuccess() {
                setRollbackPending(currentAccount, false);
                dismiss(progress);
                if (loadButton != null) {
                    loadButton.setEnabled(true);
                }
                if (next != null && !destroyed) {
                    next.run();
                }
            }

            @Override
            public void onFailure(int reason) {
                setRollbackPending(currentAccount, true);
                if (destroyed) {
                    dismiss(progress);
                    return;
                }
                finishAttempt(getString(R.string.OpengramReloadRollbackFailed));
            }
        };
        boolean started = machine.needsRollback() ? machine.retryRollback(listener) : machine.recoverRollback(listener);
        if (!started && !destroyed) {
            finishAttempt(getString(R.string.OpengramReloadRollbackFailed));
        }
    }

    private String failureMessage(int reason, long userId) {
        if (reason == OpengramReloadImportMachine.ROLLBACK_FAILED) {
            return getString(R.string.OpengramReloadRollbackFailed);
        }
        if (reason == OpengramReloadImportMachine.REJECTED) {
            String message = rejection(currentAccount, userId);
            if (message != null) {
                return message;
            }
        }
        return getString(R.string.OpengramReloadImportFailed);
    }

    private static boolean pendingRollback(int account) {
        return account >= 0 && account < rollbackPending.length && rollbackPending[account];
    }

    private static void setRollbackPending(int account, boolean pending) {
        if (account >= 0 && account < rollbackPending.length) {
            rollbackPending[account] = pending;
        }
    }

    private void finishAttempt(String message) {
        dismiss(progress);
        if (loadButton != null) {
            loadButton.setEnabled(true);
        }
        if (!isFinishing() && message != null) {
            showNotice(message);
        }
    }

    private String rejection(int account, long userId) {
        if (userId <= 0 || ConnectionsManager.getInstance(account).isTestBackend()) {
            return getString(R.string.OpengramReloadImportFailed);
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (config.isClientActivated() && config.getClientUserId() == userId) {
                return getString(R.string.OpengramReloadAccountInUse);
            }
        }
        if (UserConfig.getInstance(account).isClientActivated()) {
            return getString(R.string.OpengramReloadSlotBusy);
        }
        return null;
    }

    private final class ImportBackend implements OpengramReloadImportMachine.Backend {
        @Override
        public boolean accountFree(long expectedUserId) {
            return rejection(currentAccount, expectedUserId) == null;
        }

        @Override
        public void stage(int dcId, byte[] key, OpengramReloadImportMachine.Done done) {
            reloadOp = ConnectionsManager.getInstance(currentAccount).beginStagePermAuthKey(dcId, key, (ok, ignoredDc, ignoredKey) -> AndroidUtilities.runOnUIThread(() -> {
                reloadOp = 0;
                done.onComplete(ok);
            }));
            if (reloadOp == 0) {
                AndroidUtilities.runOnUIThread(() -> done.onComplete(false));
            }
        }

        @Override
        public void commit(OpengramReloadImportMachine.Done done) {
            reloadOp = ConnectionsManager.getInstance(currentAccount).beginCommitPermAuthKey((ok, ignoredDc, ignoredKey) -> AndroidUtilities.runOnUIThread(() -> {
                reloadOp = 0;
                done.onComplete(ok);
            }));
            if (reloadOp == 0) {
                AndroidUtilities.runOnUIThread(() -> done.onComplete(false));
            }
        }

        @Override
        public void rollback(OpengramReloadImportMachine.Done done) {
            reloadOp = ConnectionsManager.getInstance(currentAccount).beginRollbackPermAuthKey((ok, ignoredDc, ignoredKey) -> AndroidUtilities.runOnUIThread(() -> {
                reloadOp = 0;
                done.onComplete(ok);
            }));
            if (reloadOp == 0) {
                done.onComplete(false);
            }
        }

        @Override
        public void validate(OpengramReloadImportMachine.ValidateDone done) {
            TLRPC.TL_users_getUsers request = new TLRPC.TL_users_getUsers();
            request.id.add(new TLRPC.TL_inputUserSelf());
            ConnectionsManager manager = ConnectionsManager.getInstance(currentAccount);
            manager.preserveAuthKeyOnInitError = true;
            validateToken = manager.sendRequest(request, (response, error) -> {
                TLRPC.User found = null;
                if (error == null && response instanceof Vector) {
                    Vector vector = (Vector) response;
                    for (int i = 0; i < vector.objects.size(); i++) {
                        if (vector.objects.get(i) instanceof TLRPC.User) {
                            TLRPC.User user = (TLRPC.User) vector.objects.get(i);
                            if (user.id != 0) {
                                found = user;
                                break;
                            }
                        }
                    }
                }
                TLRPC.User server = found;
                AndroidUtilities.runOnUIThread(() -> {
                    manager.preserveAuthKeyOnInitError = false;
                    validateToken = 0;
                    serverUser = server;
                    done.onComplete(server == null ? 0 : server.id);
                });
            }, ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors);
        }

        @Override
        public boolean activate(long serverUserId) {
            if (serverUser == null || serverUser.id != serverUserId) {
                return false;
            }
            LoginActivity login = loginHost();
            if (login == null) {
                return false;
            }
            accepted = true;
            login.completeReload(serverUser);
            return UserConfig.getInstance(currentAccount).isClientActivated();
        }

        @Override
        public void cancelPending() {
            if (reloadOp != 0) {
                ConnectionsManager.getInstance(currentAccount).cancelReload(reloadOp);
                reloadOp = 0;
            }
            if (validateToken != 0) {
                ConnectionsManager manager = ConnectionsManager.getInstance(currentAccount);
                manager.preserveAuthKeyOnInitError = false;
                manager.cancelRequest(validateToken, false);
                validateToken = 0;
            }
        }
    }

    private LoginActivity loginHost() {
        if (getParentLayout() == null) {
            return null;
        }
        ArrayList<BaseFragment> stack = new ArrayList<>(getParentLayout().getFragmentStack());
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i) instanceof LoginActivity) {
                return (LoginActivity) stack.get(i);
            }
        }
        return null;
    }

    private byte[] readFile(ContentResolver resolver, Uri uri) {
        if (uri == null || resolver == null) {
            return null;
        }
        try {
            InputStream raw = resolver.openInputStream(uri);
            if (raw == null) {
                return null;
            }
            byte[] buffer = new byte[OpengramReloadCrypto.FILE_LENGTH + 1];
            int offset = 0;
            try {
                while (offset < buffer.length) {
                    int count = raw.read(buffer, offset, buffer.length - offset);
                    if (count < 0) {
                        break;
                    }
                    offset += count;
                }
            } finally {
                raw.close();
            }
            if (offset != OpengramReloadCrypto.FILE_LENGTH) {
                Arrays.fill(buffer, (byte) 0);
                return null;
            }
            byte[] file = Arrays.copyOf(buffer, OpengramReloadCrypto.FILE_LENGTH);
            Arrays.fill(buffer, (byte) 0);
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    private String displayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getParentActivity() == null ? null : getParentActivity().getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && name.length() > 0) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return getString(R.string.OpengramReloadFileSelected);
    }

    private void showNotice(String message) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.OpengramReload));
        builder.setMessage(message);
        builder.setPositiveButton(getString(R.string.OK), null);
        showDialog(builder.create());
    }

    private void dismiss(AlertDialog dialog) {
        try {
            if (dialog != null) {
                dialog.dismiss();
            }
        } catch (Exception ignored) {
        }
        if (dialog == progress) {
            progress = null;
        }
    }

    private void wipe() {
        if (passphraseField != null) {
            passphraseField.setText("");
        }
    }

    private static void wipeBytes(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
