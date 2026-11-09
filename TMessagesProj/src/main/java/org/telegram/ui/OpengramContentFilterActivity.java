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
import org.telegram.messenger.DialogObject;
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
    private static final int ROW_ADD_KEYWORD = 2;
    private static final int ROW_ADD_CHANNEL = 3;
    private static final int ROW_ADD_EXCLUSION = 4;
    private static final int ROW_CHANNEL = 5;
    private static final int ROW_EXCLUSION = 6;
    private static final int ROW_KEYWORD = 100;

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
        if (phase == OpengramContentFilter.Phase.ERROR) {
            items.add(UItem.asShadow(getString(R.string.MessageFilterError)));
        } else {
            items.add(UItem.asShadow(null));
        }
        boolean ready = phase == OpengramContentFilter.Phase.READY;
        items.add(UItem.asHeader(getString(R.string.MessageFilterKeywords)));
        items.add(UItem.asButton(ROW_ADD_KEYWORD, getString(R.string.MessageFilterAddKeyword)).setEnabled(ready));
        if (ready) {
            ArrayList<String> keywords = OpengramContentFilter.keywords(currentAccount);
            for (int a = 0; a < keywords.size(); a++) {
                String keyword = keywords.get(a);
                items.add(UItem.asButton(ROW_KEYWORD + a, keyword).setCloseIcon(v -> removeKeyword(keyword)));
            }
        }
        items.add(UItem.asShadow(getString(R.string.MessageFilterInfo)));
        items.add(UItem.asHeader(getString(R.string.MessageFilterHiddenChannels)));
        items.add(UItem.asButton(ROW_ADD_CHANNEL, getString(R.string.MessageFilterAddChannel)).setEnabled(ready));
        if (ready) {
            ArrayList<Long> channels = OpengramContentFilter.hiddenChannels(currentAccount);
            for (int a = 0; a < channels.size(); a++) {
                long dialogId = channels.get(a);
                items.add(UItem.asFilterChat(false, dialogId).setId(ROW_CHANNEL).setCloseIcon(v -> removeChannel(dialogId)));
            }
        }
        items.add(UItem.asShadow(getString(R.string.MessageFilterChannelsInfo)));
        items.add(UItem.asHeader(getString(R.string.MessageFilterExcludedChats)));
        items.add(UItem.asButton(ROW_ADD_EXCLUSION, getString(R.string.MessageFilterAddExcludedChat)).setEnabled(ready));
        if (ready) {
            ArrayList<Long> excluded = OpengramContentFilter.excludedChats(currentAccount);
            for (int a = 0; a < excluded.size(); a++) {
                long dialogId = excluded.get(a);
                items.add(UItem.asFilterChat(false, dialogId).setId(ROW_EXCLUSION).setCloseIcon(v -> removeExclusion(dialogId)));
            }
        }
        items.add(UItem.asShadow(getString(R.string.MessageFilterExcludedChatsInfo)));
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
        } else if (item.id == ROW_ADD_KEYWORD) {
            showAddKeywordDialog();
        } else if (item.id >= ROW_KEYWORD && item.text != null) {
            removeKeyword(item.text.toString());
        } else if (item.id == ROW_ADD_CHANNEL) {
            openChannelPicker();
        } else if (item.id == ROW_ADD_EXCLUSION) {
            openExclusionPicker();
        } else if (item.id == ROW_CHANNEL) {
            removeChannel(item.dialogId);
        } else if (item.id == ROW_EXCLUSION) {
            removeExclusion(item.dialogId);
        }
    }

    private void removeKeyword(String keyword) {
        if (!OpengramContentFilter.removeKeyword(currentAccount, keyword)) {
            showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
        }
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void removeChannel(long dialogId) {
        if (!OpengramContentFilter.removeChannel(currentAccount, dialogId)) {
            showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
        }
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void removeExclusion(long dialogId) {
        if (!OpengramContentFilter.removeExclusion(currentAccount, dialogId)) {
            showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
        }
        if (listView != null) {
            listView.adapter.update(true);
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
            if (chat != null && chat.broadcast) {
                ArrayList<Long> hidden = OpengramContentFilter.hiddenChannels(currentAccount);
                boolean full = !hidden.contains(dialogId) && hidden.size() >= OpengramContentFilter.MAX_CHANNELS;
                if (!full && !OpengramContentFilter.addChannel(currentAccount, dialogId)) {
                    showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
                }
            }
            if (listView != null) {
                listView.adapter.update(true);
            }
            return true;
        });
        presentFragment(picker);
    }

    private void openExclusionPicker() {
        if (getParentActivity() == null || OpengramContentFilter.phase(currentAccount) != OpengramContentFilter.Phase.READY) {
            return;
        }
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", false);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) {
                return false;
            }
            fragment.finishFragment();
            long dialogId = dids.get(0).dialogId;
            if (!exclusionApplies(dialogId)) {
                showNotice(getString(R.string.MessageFilter), getString(R.string.MessageFilterExcludeOnlyFiltered));
            } else {
                ArrayList<Long> excluded = OpengramContentFilter.excludedChats(currentAccount);
                boolean full = !excluded.contains(dialogId) && excluded.size() >= OpengramContentFilter.MAX_EXCLUSIONS;
                if (full) {
                    showNotice(getString(R.string.MessageFilter), getString(R.string.MessageFilterExcludeFull));
                } else if (!OpengramContentFilter.addExclusion(currentAccount, dialogId)) {
                    showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
                }
            }
            if (listView != null) {
                listView.adapter.update(true);
            }
            return true;
        });
        presentFragment(picker);
    }

    private boolean exclusionApplies(long dialogId) {
        if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        if (DialogObject.isChatDialog(dialogId)) {
            TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            return chat != null && chat.broadcast;
        }
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            return user != null && user.bot;
        }
        return false;
    }

    private void enableFilter(int mode) {
        if (!OpengramContentFilter.enable(currentAccount, mode)) {
            showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
        }
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void showAddKeywordDialog() {
        if (getParentActivity() == null || OpengramContentFilter.phase(currentAccount) != OpengramContentFilter.Phase.READY) {
            return;
        }
        if (OpengramContentFilter.keywords(currentAccount).size() >= OpengramContentFilter.MAX_KEYWORDS) {
            return;
        }
        final EditTextBoldCursor editText = new EditTextBoldCursor(getParentActivity());
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        editText.setHint(getString(R.string.MessageFilterKeywordHint));
        editText.setBackground(Theme.createEditTextDrawable(getParentActivity(), true));
        editText.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editText.setSingleLine(true);
        editText.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        FrameLayout container = new FrameLayout(getParentActivity());
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 6, 24, 6));

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.MessageFilterAddKeyword));
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.Add), (dialog, which) -> {
            if (!OpengramContentFilter.addKeyword(currentAccount, editText.getText().toString())) {
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
