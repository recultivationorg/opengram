package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SlideChooseView;

public class DownloadSpeedActivity extends BaseFragment {

    private RecyclerListView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.DownloadSpeedScaling));
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
            return 2;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? 0 : 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                SlideChooseView chooseView = new SlideChooseView(context);
                chooseView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                view = chooseView;
            } else {
                view = new TextInfoPrivacyCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position == 0) {
                SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                chooseView.setCallback(index -> {
                    SharedConfig.downloadSpeedScale = index;
                    SharedConfig.saveConfig();
                });
                chooseView.setOptions(
                    SharedConfig.downloadSpeedScale,
                    getString(R.string.DownloadSpeedStandard),
                    getString(R.string.DownloadSpeedFaster),
                    getString(R.string.DownloadSpeedHigh),
                    getString(R.string.DownloadSpeedMaximum)
                );
            } else {
                ((TextInfoPrivacyCell) holder.itemView).setText(getString(R.string.DownloadSpeedScalingInfo));
            }
        }
    }
}
