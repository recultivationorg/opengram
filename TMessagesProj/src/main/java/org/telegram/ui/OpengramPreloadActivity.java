package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.OpengramPreload;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

public class OpengramPreloadActivity extends BaseFragment {

    private UniversalRecyclerView listView;

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

        FrameLayout contentView = new FrameLayout(context);
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null) {
            @Override
            public Integer getSelectorColor(int position) {
                UItem item = adapter.getItem(position);
                if (item != null && item.id == 2) {
                    return 0;
                }
                return super.getSelectorColor(position);
            }
        };
        listView.setSections();
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);
        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(1, getString(R.string.ForceEndToEndEncryption)).setChecked(SharedConfig.forceEndToEndEncryption));
        items.add(UItem.asShadow(getString(R.string.ForceEndToEndEncryptionInfo)));
        boolean enabled = OpengramPreload.contains(UserConfig.getInstance(currentAccount).getClientUserId());
        items.add(UItem.asButton(
            2,
            getString(R.string.OpengramPreloadStatus),
            getString(enabled ? R.string.OpengramPreloadEnabled : R.string.OpengramPreloadNotFound)
        ));
        items.add(UItem.asShadow(getString(R.string.OpengramPreloadInfo)));
        items.add(UItem.asButton(3, getString(R.string.OpengramPreloadEnable)).accent());
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 1) {
            SharedConfig.forceEndToEndEncryption = !SharedConfig.forceEndToEndEncryption;
            SharedConfig.saveConfig();
            listView.adapter.update(true);
        } else if (item.id == 3) {
            Browser.openUrl(getParentActivity(), "https://t.me/opengrampreloadbot");
        }
    }
}
