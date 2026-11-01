package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.OpengramReloadCrypto;
import org.telegram.messenger.OpengramReloadWords;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.PasscodeView;
import org.telegram.ui.Components.LayoutHelper;

import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

public class OpengramReloadActivity extends BaseFragment {

    private static final int REQUEST_SAVE = 40;

    private final OpengramReloadWindow secure = new OpengramReloadWindow();
    private ScrollView scrollView;
    private byte[] authKey;
    private byte[] userBlob;
    private byte[] pendingFile;
    private long userId;
    private int dcId;
    private OpengramReloadWords.Phrase words;
    private int[] quiz;
    private EditTextBoldCursor[] quizFields;
    private TextView quizButton;
    private int exportOp;
    private int exportGeneration;
    private boolean exporting;
    private boolean confirming;
    private char[] clipboardSecret;
    private int clipboardGeneration;

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
        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        showIntro();
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
        exportGeneration++;
        if (exportOp != 0) {
            ConnectionsManager.getInstance(currentAccount).cancelReload(exportOp);
            exportOp = 0;
        }
        clearClipboard();
        wipe();
        secure.release(getParentActivity());
        super.onFragmentDestroy();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_SAVE || pendingFile == null || getParentActivity() == null) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        boolean wrote = false;
        try {
            OutputStream out = getParentActivity().getContentResolver().openOutputStream(uri);
            if (out == null) {
                showNotice(getString(R.string.OpengramReloadSaveFailed));
                return;
            }
            try {
                out.write(pendingFile);
                out.flush();
                wrote = true;
            } finally {
                out.close();
            }
        } catch (Exception e) {
            wrote = false;
        }
        if (!wrote) {
            showNotice(getString(R.string.OpengramReloadSaveFailed));
            return;
        }
        wipe();
        finishFragment();
    }

    private void showIntro() {
        actionBar.setTitle(getString(R.string.OpengramReload));
        LinearLayout column = column();
        column.addView(body(getString(R.string.OpengramReloadInfo)));
        column.addView(button(getString(R.string.OpengramReloadExport), v -> confirmExport()));
        swap(column);
    }

    private void confirmExport() {
        if (getParentActivity() == null || exporting || confirming) {
            return;
        }
        if (SharedConfig.passcodeHash.length() == 0) {
            showNotice(getString(R.string.OpengramReloadPasscodeRequired));
            return;
        }
        confirming = true;
        final Dialog dialog = new Dialog(getParentActivity(), R.style.TransparentDialog);
        final PasscodeView passcodeView = new PasscodeView(getParentActivity(), getString(R.string.OpengramReloadPasscodeTitle), getString(R.string.OpengramReloadPasscodeMessage)) {
            @Override
            protected boolean isAppLock() {
                return false;
            }

            @Override
            protected void onHidden() {
                super.onHidden();
                confirming = false;
                dialog.dismiss();
            }
        };
        passcodeView.setDelegate(view -> startExport());
        FrameLayout frame = new FrameLayout(getParentActivity());
        frame.addView(passcodeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        dialog.setContentView(frame);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                passcodeView.dismiss();
                return true;
            }
            return false;
        });
        dialog.show();
        passcodeView.onShow(true, false);
    }

    private void startExport() {
        if (getParentActivity() == null || exporting) {
            return;
        }
        exporting = true;
        final int generation = ++exportGeneration;
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.show();
        final int account = currentAccount;
        exportOp = ConnectionsManager.getInstance(account).beginCopyPermAuthKey((ok, dc, key) -> AndroidUtilities.runOnUIThread(() -> {
            exportOp = 0;
            dismiss(progress);
            exporting = false;
            TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
            byte[] blob = user == null ? null : OpengramReloadCrypto.serializeUser(user);
            if (generation != exportGeneration || isFinishing() || getParentActivity() == null) {
                wipeBytes(key);
                wipeBytes(blob);
                return;
            }
            if (!ok || key == null || user == null || user.id <= 0 || dc < 1 || dc > 5) {
                wipeBytes(key);
                wipeBytes(blob);
                showNotice(getString(R.string.OpengramReloadExportUnavailable));
                return;
            }
            if (blob == null) {
                wipeBytes(key);
                showNotice(getString(R.string.OpengramReloadSaveFailed));
                return;
            }
            wipe();
            authKey = key;
            userBlob = blob;
            userId = user.id;
            dcId = dc;
            words = OpengramReloadWords.generate(new SecureRandom());
            showWords();
        }));
        if (exportOp == 0) {
            exporting = false;
            dismiss(progress);
            showNotice(getString(R.string.OpengramReloadExportUnavailable));
        }
    }

    private void showWords() {
        actionBar.setTitle(getString(R.string.OpengramReloadWordsTitle));
        LinearLayout column = column();
        column.addView(body(getString(R.string.OpengramReloadWordsInfo)));
        for (int i = 0; i < OpengramReloadWords.WORD_COUNT; i++) {
            TextView word = new TextView(getContext());
            word.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            word.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            word.setText((i + 1) + ".  " + words.word(i));
            word.setTextIsSelectable(false);
            word.setLongClickable(false);
            word.setOnLongClickListener(v -> true);
            column.addView(word, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 8f));
        }
        column.addView(button(getString(R.string.Copy), v -> copyWords()));
        column.addView(button(getString(R.string.OpengramReloadWroteThemDown), v -> {
            clearClipboard();
            showQuiz();
        }));
        swap(column);
    }

    private void copyWords() {
        if (words == null) {
            return;
        }
        char[] passphrase = words.toPassphrase();
        try {
            if (AndroidUtilities.addToClipboard(new String(passphrase))) {
                if (clipboardSecret != null) {
                    Arrays.fill(clipboardSecret, '\0');
                }
                clipboardSecret = passphrase;
                passphrase = null;
                int generation = ++clipboardGeneration;
                AndroidUtilities.runOnUIThread(() -> clearClipboard(generation), 60 * 1000);
                BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
            }
        } finally {
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
        }
    }

    private void clearClipboard() {
        clearClipboard(clipboardGeneration);
    }

    private void clearClipboard(int generation) {
        if (clipboardSecret == null || generation != clipboardGeneration) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 28 && ApplicationLoader.applicationContext != null) {
            ClipboardManager manager = (ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                ClipData clip = manager.getPrimaryClip();
                CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
                if (OpengramReloadWords.clipboardHolds(clipboardSecret, text)) {
                    manager.clearPrimaryClip();
                }
            }
        }
        Arrays.fill(clipboardSecret, '\0');
        clipboardSecret = null;
    }

    private void showQuiz() {
        actionBar.setTitle(getString(R.string.OpengramReloadConfirmTitle));
        quiz = OpengramReloadWords.quizPositions(new SecureRandom());
        quizFields = new EditTextBoldCursor[quiz.length];
        LinearLayout column = column();
        column.addView(body(getString(R.string.OpengramReloadConfirmInfo)));
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshQuizButton();
            }
        };
        for (int i = 0; i < quiz.length; i++) {
            TextView label = new TextView(getContext());
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            label.setText(formatString(R.string.OpengramReloadWordN, quiz[i] + 1));
            column.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, i == 0 ? 0f : 12f, 0f, 4f));
            EditTextBoldCursor field = new EditTextBoldCursor(getContext());
            field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            field.setBackground(Theme.createEditTextDrawable(getContext(), false));
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            field.setSingleLine(true);
            field.setTextIsSelectable(false);
            field.addTextChangedListener(watcher);
            quizFields[i] = field;
            column.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 0f));
        }
        quizButton = button(getString(R.string.OpengramReloadContinue), v -> confirmQuiz());
        quizButton.setEnabled(false);
        quizButton.setAlpha(0.5f);
        column.addView(quizButton);
        swap(column);
    }

    private void refreshQuizButton() {
        if (quizButton == null) {
            return;
        }
        boolean ready = quizMatches();
        quizButton.setEnabled(ready);
        quizButton.setAlpha(ready ? 1f : 0.5f);
    }

    private boolean quizMatches() {
        if (words == null || quiz == null || quizFields == null) {
            return false;
        }
        for (int i = 0; i < quiz.length; i++) {
            if (quizFields[i] == null || quizFields[i].getText() == null) {
                return false;
            }
            if (!OpengramReloadWords.sameWord(words.word(quiz[i]), quizFields[i].getText().toString())) {
                return false;
            }
        }
        return true;
    }

    private void confirmQuiz() {
        if (!quizMatches() || getParentActivity() == null) {
            return;
        }
        final char[] passphrase = words.toPassphrase();
        final byte[] key = authKey;
        final byte[] blob = userBlob;
        final long uid = userId;
        final int dc = dcId;
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            byte[] file = OpengramReloadCrypto.seal(dc, key, uid, blob, passphrase, new SecureRandom());
            Arrays.fill(passphrase, '\0');
            AndroidUtilities.runOnUIThread(() -> {
                dismiss(progress);
                if (isFinishing() || getParentActivity() == null) {
                    wipeBytes(file);
                    return;
                }
                if (file == null) {
                    showNotice(getString(R.string.OpengramReloadSaveFailed));
                    return;
                }
                if (pendingFile != null) {
                    Arrays.fill(pendingFile, (byte) 0);
                }
                pendingFile = file;
                showSave();
                openSavePicker();
            });
        });
    }

    private void showSave() {
        actionBar.setTitle(getString(R.string.OpengramReload));
        LinearLayout column = column();
        column.addView(body(getString(R.string.OpengramReloadSaveInfo)));
        column.addView(button(getString(R.string.OpengramReloadSave), v -> openSavePicker()));
        swap(column);
    }

    private void openSavePicker() {
        if (getParentActivity() == null || pendingFile == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "opengram-reload.bin");
        startActivityForResult(intent, REQUEST_SAVE);
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(20);
        column.setPadding(pad, pad, pad, pad);
        return column;
    }

    private TextView body(String text) {
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        view.setText(text);
        view.setLineSpacing(AndroidUtilities.dp(2), 1f);
        LinearLayout.LayoutParams params = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 16f);
        view.setLayoutParams(params);
        return view;
    }

    private TextView button(String text, View.OnClickListener listener) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        view.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 8));
        view.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        view.setOnClickListener(listener);
        view.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 12f, 0f, 0f));
        return view;
    }

    private void swap(LinearLayout column) {
        scrollView.removeAllViews();
        scrollView.addView(column, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
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
            dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    private void wipe() {
        wipeBytes(authKey);
        wipeBytes(userBlob);
        wipeBytes(pendingFile);
        authKey = null;
        userBlob = null;
        pendingFile = null;
        words = null;
        quiz = null;
        userId = 0;
        dcId = 0;
        if (quizFields != null) {
            for (int i = 0; i < quizFields.length; i++) {
                if (quizFields[i] != null) {
                    quizFields[i].setText("");
                }
            }
            quizFields = null;
        }
    }

    private static void wipeBytes(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
