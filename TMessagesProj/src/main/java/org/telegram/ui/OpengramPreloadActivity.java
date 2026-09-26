package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.OpengramPreload;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public class OpengramPreloadActivity extends BaseFragment {

    private static final int ROW_FORCE = 0;
    private static final int ROW_FORCE_INFO = 1;
    private static final int ROW_STATUS = 2;
    private static final int ROW_STATUS_INFO = 3;
    private static final int ROW_ENABLE = 4;

    private RecyclerListView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.OpengramPreload));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position == ROW_FORCE && view instanceof TextCheckCell) {
                SharedConfig.forceEndToEndEncryption = !SharedConfig.forceEndToEndEncryption;
                SharedConfig.saveConfig();
                ((TextCheckCell) view).setChecked(SharedConfig.forceEndToEndEncryption);
            } else if (position == ROW_ENABLE) {
                Browser.openUrl(getParentActivity(), "https://t.me/opengrampreloadbot");
            }
        });
        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return 5;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == ROW_FORCE || position == ROW_ENABLE;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_FORCE) {
                return 0;
            }
            if (position == ROW_STATUS || position == ROW_ENABLE) {
                return 1;
            }
            return 2;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                view = new TextCheckCell(context);
            } else if (viewType == 1) {
                view = new TextSettingsCell(context);
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setBackgroundColor(Theme.getColor(viewType == 2 ? Theme.key_windowBackgroundGray : Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position == ROW_FORCE) {
                ((TextCheckCell) holder.itemView).setTextAndCheck(getString(R.string.ForceEndToEndEncryption), SharedConfig.forceEndToEndEncryption, false);
            } else if (position == ROW_FORCE_INFO) {
                ((TextInfoPrivacyCell) holder.itemView).setText(getString(R.string.ForceEndToEndEncryptionInfo));
            } else if (position == ROW_STATUS) {
                boolean enabled = OpengramPreload.contains(UserConfig.getInstance(currentAccount).getClientUserId());
                ((TextSettingsCell) holder.itemView).setTextAndValue(
                    getString(R.string.OpengramPreloadStatus),
                    getString(enabled ? R.string.OpengramPreloadEnabled : R.string.OpengramPreloadNotFound),
                    false
                );
            } else if (position == ROW_STATUS_INFO) {
                ((TextInfoPrivacyCell) holder.itemView).setText(getString(R.string.OpengramPreloadInfo));
            } else if (position == ROW_ENABLE) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setText(getString(R.string.OpengramPreloadEnable), false);
                cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
            }
        }
    }
}
