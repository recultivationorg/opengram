package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.OpengramContentFilter;
import org.telegram.messenger.OpengramSnapshotFile;
import org.telegram.messenger.OpengramStrongBox;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

public class OpengramContentFilterActivity extends BaseFragment {

    private static final int ROW_ENABLE = 1;
    private static final int ROW_KEYWORDS = 2;
    private static final int ROW_ADD_CHANNEL = 3;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.MessageFilter));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setSections();
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);
        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        OpengramContentFilter.Phase phase = OpengramContentFilter.phase(currentAccount);
        boolean enabled = phase != OpengramContentFilter.Phase.DISABLED;
        items.add(UItem.asCheck(ROW_ENABLE, getString(R.string.MessageFilter)).setChecked(enabled));
        String info = phase == OpengramContentFilter.Phase.ERROR ? getString(R.string.MessageFilterError) : getString(R.string.MessageFilterInfo);
        items.add(UItem.asShadow(info));
        items.add(UItem.asButton(ROW_KEYWORDS, getString(R.string.MessageFilterKeywords)).setEnabled(phase == OpengramContentFilter.Phase.READY));
        items.add(UItem.asShadow(null));
        boolean ready = phase == OpengramContentFilter.Phase.READY;
        items.add(UItem.asHeader(getString(R.string.MessageFilterHiddenChannels)));
        items.add(UItem.asButton(ROW_ADD_CHANNEL, getString(R.string.MessageFilterAddChannel)).setEnabled(ready));
        if (ready) {
            ArrayList<Long> channels = OpengramContentFilter.hiddenChannels(currentAccount);
            for (int a = 0; a < channels.size(); a++) {
                items.add(UItem.asFilterChat(false, channels.get(a)));
            }
        }
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ROW_ENABLE) {
            if (OpengramContentFilter.phase(currentAccount) != OpengramContentFilter.Phase.DISABLED) {
                OpengramContentFilter.disable(currentAccount);
                listView.adapter.update(true);
                return;
            }
            if (getParentActivity() == null) {
                listView.adapter.update(true);
                return;
            }
            long clientUserId = getUserConfig().getClientUserId();
            if (clientUserId <= 0) {
                showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
                listView.adapter.update(true);
                return;
            }
            OpengramStrongBox.Probe probe = OpengramStrongBox.forFilter(clientUserId).probeKey();
            if (probe == OpengramStrongBox.Probe.ERROR) {
                showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
                listView.adapter.update(true);
                return;
            }
            if (probe == OpengramStrongBox.Probe.AVAILABLE) {
                enableFilter(OpengramSnapshotFile.MODE_STRONGBOX);
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle(getString(R.string.ForceEndToEndStrongBoxTitle));
                builder.setMessage(getString(R.string.MessageFilterStrongBoxWarning));
                builder.setPositiveButton(getString(R.string.OK), (dialogInterface, i) -> enableFilter(OpengramSnapshotFile.MODE_PLAINTEXT));
                builder.setNegativeButton(getString(R.string.Cancel), (dialogInterface, i) -> listView.adapter.update(true));
                showDialog(builder.create());
            }
        } else if (item.id == ROW_KEYWORDS) {
            showKeywordsDialog();
        } else if (item.id == ROW_ADD_CHANNEL) {
            openChannelPicker();
        } else if (item.viewType == UniversalAdapter.VIEW_TYPE_FILTER_CHAT && item.dialogId < 0) {
            if (!OpengramContentFilter.removeChannel(currentAccount, item.dialogId)) {
                showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
            }
            if (listView != null) {
                listView.adapter.update(true);
            }
        }
    }

    private void openChannelPicker() {
        if (getParentActivity() == null || OpengramContentFilter.phase(currentAccount) != OpengramContentFilter.Phase.READY) {
            return;
        }
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", false);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_CHANNELS_ONLY);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) {
                return false;
            }
            fragment.finishFragment();
            long dialogId = dids.get(0).dialogId;
            TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            if (chat != null && chat.broadcast && !OpengramContentFilter.addChannel(currentAccount, dialogId)) {
                showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
            }
            if (listView != null) {
                listView.adapter.update(true);
            }
            return true;
        });
        presentFragment(picker);
    }

    private void enableFilter(int mode) {
        if (!OpengramContentFilter.enable(currentAccount, mode)) {
            showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
        }
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void showKeywordsDialog() {
        if (getParentActivity() == null || OpengramContentFilter.phase(currentAccount) != OpengramContentFilter.Phase.READY) {
            return;
        }
        final EditTextBoldCursor editText = new EditTextBoldCursor(getParentActivity());
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        editText.setHint(getString(R.string.MessageFilterKeywordsHint));
        editText.setBackground(Theme.createEditTextDrawable(getParentActivity(), true));
        editText.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editText.setSingleLine(false);
        editText.setMinLines(1);
        editText.setMaxLines(6);
        editText.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        String current = OpengramContentFilter.keywordsText(currentAccount);
        if (current.length() > 0) {
            editText.setText(current);
            editText.setSelection(current.length());
        }
        FrameLayout container = new FrameLayout(getParentActivity());
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 6, 24, 6));

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.MessageFilterKeywords));
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            if (!OpengramContentFilter.saveKeywords(currentAccount, editText.getText().toString())) {
                showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
            }
            if (listView != null) {
                listView.adapter.update(true);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
        AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 100);
    }

    private void showNotice(String title, String message) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(getString(R.string.OK), null);
        showDialog(builder.create());
    }
}
