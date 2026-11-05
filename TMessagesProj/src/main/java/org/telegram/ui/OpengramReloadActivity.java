package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.app.KeyguardManager;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.OpengramClientFingerprint;
import org.telegram.messenger.OpengramReloadCrypto;
import org.telegram.messenger.OpengramReloadWords;
import org.telegram.messenger.R;
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
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

public class OpengramReloadActivity extends BaseFragment {

    private static final int REQUEST_SAVE = 40;

    private final OpengramReloadWindow secure = new OpengramReloadWindow();
    private OpengramReloadChrome chrome;
    private ScrollView scrollView;
    private byte[] authKey;
    private byte[] userBlob;
    private byte[] pendingFile;
    private long userId;
    private int dcId;
    private OpengramReloadWords.Phrase words;
    private int[] quiz;
    private EditTextBoldCursor[] quizFields;
    private ButtonWithCounterView quizButton;
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
        chrome = new OpengramReloadChrome(context);
        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.addView(chrome.page, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
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
        LinearLayout column = chrome.column(true);
        LinearLayout card = chrome.card();
        card.addView(chrome.body(getString(R.string.OpengramReloadInfo)));
        column.addView(card);
        column.addView(chrome.button(getString(R.string.OpengramReloadExport), v -> confirmExport()));
        chrome.show(column, true);
    }

    private void confirmExport() {
        Activity activity = getParentActivity();
        if (activity == null || exporting || confirming) {
            return;
        }
        if (!(activity instanceof FragmentActivity) || !deviceCredentialAvailable(activity)) {
            showNotice(getString(R.string.OpengramReloadPasscodeRequired));
            return;
        }
        confirming = true;
        BiometricPrompt prompt = new BiometricPrompt((FragmentActivity) activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                confirming = false;
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    return;
                }
                if (errString.length() > 0) {
                    showNotice(errString.toString());
                }
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                confirming = false;
                startExport();
            }
        });
        BiometricPrompt.PromptInfo.Builder builder = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.OpengramReloadPasscodeTitle))
                .setDescription(getString(R.string.OpengramReloadPasscodeMessage));
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            allowDeviceCredential(builder);
        }
        try {
            prompt.authenticate(builder.build());
        } catch (Exception e) {
            confirming = false;
            showNotice(getString(R.string.OpengramReloadPasscodeRequired));
        }
    }

    private boolean deviceCredentialAvailable(Activity activity) {
        KeyguardManager keyguard = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            return false;
        }
        if (Build.VERSION.SDK_INT < 30) {
            return true;
        }
        BiometricManager manager = BiometricManager.from(activity);
        int biometric = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        int credential = manager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        return biometric == BiometricManager.BIOMETRIC_SUCCESS || credential == BiometricManager.BIOMETRIC_SUCCESS;
    }

    @SuppressWarnings("deprecation")
    private void allowDeviceCredential(BiometricPrompt.PromptInfo.Builder builder) {
        builder.setDeviceCredentialAllowed(true);
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
        exportOp = ConnectionsManager.getInstance(account).beginCopyPermAuthKey((ok, dc, key) -> {
            final byte[] owned = OpengramReloadCrypto.takeDeliveredKey(key);
            AndroidUtilities.runOnUIThread(() -> {
                exportOp = 0;
                dismiss(progress);
                exporting = false;
                TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
                byte[] blob = user == null ? null : OpengramReloadCrypto.serializeUser(user);
                if (generation != exportGeneration || isFinishing() || getParentActivity() == null) {
                    wipeBytes(owned);
                    wipeBytes(blob);
                    return;
                }
                if (!ok || owned == null || user == null || user.id <= 0 || dc < 1 || dc > 5) {
                    wipeBytes(owned);
                    wipeBytes(blob);
                    showNotice(getString(R.string.OpengramReloadExportUnavailable));
                    return;
                }
                if (blob == null) {
                    wipeBytes(owned);
                    showNotice(getString(R.string.OpengramReloadSaveFailed));
                    return;
                }
                wipe();
                authKey = owned;
                userBlob = blob;
                userId = user.id;
                dcId = dc;
                words = OpengramReloadWords.generate(new SecureRandom());
                showWords();
            });
        });
        if (exportOp == 0) {
            exporting = false;
            dismiss(progress);
            showNotice(getString(R.string.OpengramReloadExportUnavailable));
        }
    }

    private void showWords() {
        actionBar.setTitle(getString(R.string.OpengramReloadWordsTitle));
        LinearLayout column = chrome.column(false);
        LinearLayout card = chrome.card();
        card.addView(chrome.body(getString(R.string.OpengramReloadWordsInfo)));
        for (int i = 0; i < OpengramReloadWords.WORD_COUNT; i++) {
            TextView word = new TextView(getContext());
            word.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            word.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            word.setGravity(Gravity.CENTER_HORIZONTAL);
            word.setText((i + 1) + ".  " + words.word(i));
            word.setTextIsSelectable(false);
            word.setLongClickable(false);
            word.setOnLongClickListener(v -> true);
            card.addView(word, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, i == 0 ? 12f : 0f, 0f, 8f));
        }
        column.addView(card);
        column.addView(chrome.button(getString(R.string.Copy), v -> copyWords()));
        column.addView(chrome.button(getString(R.string.OpengramReloadWroteThemDown), v -> {
            clearClipboard();
            showQuiz();
        }));
        chrome.show(column, false);
    }

    private void copyWords() {
        if (words == null) {
            return;
        }
        char[] passphrase = words.toPassphrase();
        try {
            // ClipboardManager accepts only a CharSequence. The service copies that text into its own process, and this screen cannot wipe the service copy in place.
            if (AndroidUtilities.addToClipboard(new PassphraseClip(passphrase))) {
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
        char[] secret = clipboardSecret;
        clipboardSecret = null;
        if (ApplicationLoader.applicationContext != null) {
            ClipboardManager manager = (ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                ClipData clip = manager.getPrimaryClip();
                CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
                int action = OpengramReloadWords.clipboardClearAction(Build.VERSION.SDK_INT, secret, text);
                if (action == OpengramReloadWords.CLIP_CLEAR && Build.VERSION.SDK_INT >= 28) {
                    manager.clearPrimaryClip();
                } else if (action == OpengramReloadWords.CLIP_REPLACE) {
                    manager.setPrimaryClip(ClipData.newPlainText("", ""));
                }
            }
        }
        Arrays.fill(secret, '\0');
    }

    private void showQuiz() {
        actionBar.setTitle(getString(R.string.OpengramReloadConfirmTitle));
        quiz = OpengramReloadWords.quizPositions(new SecureRandom());
        quizFields = new EditTextBoldCursor[quiz.length];
        LinearLayout column = chrome.column(false);
        LinearLayout card = chrome.card();
        card.addView(chrome.body(getString(R.string.OpengramReloadConfirmInfo)));
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
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setText(formatString(R.string.OpengramReloadWordN, quiz[i] + 1));
            card.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, i == 0 ? 12f : 12f, 0f, 4f));
            EditTextBoldCursor field = new EditTextBoldCursor(getContext());
            field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            field.setGravity(Gravity.CENTER_HORIZONTAL);
            chrome.roundField(field);
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            field.setSingleLine(true);
            field.setTextIsSelectable(false);
            field.addTextChangedListener(watcher);
            quizFields[i] = field;
            card.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 0f));
        }
        column.addView(card);
        quizButton = chrome.button(getString(R.string.OpengramReloadContinue), v -> confirmQuiz());
        quizButton.setEnabled(false);
        quizButton.setAlpha(0.5f);
        column.addView(quizButton);
        chrome.show(column, false);
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
        final int account = currentAccount;
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            OpengramClientFingerprint.Identity reported = OpengramClientFingerprint.resolve(ApplicationLoader.applicationContext, account);
            OpengramReloadCrypto.ClientIdentity identity = reported == null ? null : new OpengramReloadCrypto.ClientIdentity(reported.deviceModel, reported.timezone);
            byte[] file = OpengramReloadCrypto.seal(dc, key, uid, blob, identity, passphrase, new SecureRandom());
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
        LinearLayout column = chrome.column(true);
        LinearLayout card = chrome.card();
        card.addView(chrome.body(getString(R.string.OpengramReloadSaveInfo)));
        column.addView(card);
        column.addView(chrome.button(getString(R.string.OpengramReloadSave), v -> openSavePicker()));
        chrome.show(column, true);
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

    private static final class PassphraseClip implements CharSequence {
        private final char[] text;

        private PassphraseClip(char[] text) {
            this.text = text;
        }

        @Override
        public int length() {
            return text.length;
        }

        @Override
        public char charAt(int index) {
            return text[index];
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new String(text, start, end - start);
        }

        @Override
        public String toString() {
            return new String(text);
        }
    }
}
