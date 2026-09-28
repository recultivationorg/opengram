package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.OpengramSecureChannel;
import org.telegram.messenger.OpengramSecurePolicy;
import org.telegram.messenger.OpengramStrongBox;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
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
        actionBar.setTitle(getString(R.string.ForceEndToEndEncryption));
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
        OpengramSecureChannel.ensureAccountBaseline(currentAccount);
        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        OpengramSecurePolicy.Phase phase = OpengramSecureChannel.phase();
        boolean featureOn = phase != OpengramSecurePolicy.Phase.DISABLED;
        items.add(UItem.asCheck(1, getString(R.string.ForceEndToEndForNewChats)).setChecked(featureOn));
        String info;
        if (phase == OpengramSecurePolicy.Phase.CAPTURING) {
            info = getString(R.string.ForceEndToEndCapturing);
        } else if (phase == OpengramSecurePolicy.Phase.ERROR) {
            info = getString(R.string.ForceEndToEndErrorState);
        } else {
            info = getString(R.string.ForceEndToEndEncryptionInfo);
        }
        items.add(UItem.asShadow(info));
        items.add(UItem.asCheck(4, getString(R.string.ForceEndToEndForAllChats)).setChecked(featureOn && SharedConfig.forceEndToEndForAllChats).setEnabled(featureOn));
        items.add(UItem.asShadow(getString(R.string.ForceEndToEndForAllChatsInfo)));
        items.add(UItem.asShadow(null));
    }

    private void enableForceEndToEnd(OpengramSecurePolicy.StorageMode mode) {
        OpengramSecureChannel.enable(mode);
        listView.adapter.update(true);
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 1) {
            if (OpengramSecureChannel.phase() != OpengramSecurePolicy.Phase.DISABLED) {
                OpengramSecureChannel.disable();
                listView.adapter.update(true);
                return;
            }
            if (getParentActivity() == null) {
                return;
            }
            OpengramStrongBox.Probe probe = OpengramSecureChannel.probe();
            if (probe == OpengramStrongBox.Probe.ERROR) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle(getString(R.string.ForceEndToEndStrongBoxTitle));
                builder.setMessage(getString(R.string.ForceEndToEndStorageError));
                builder.setPositiveButton(getString(R.string.OK), null);
                showDialog(builder.create());
                return;
            }
            if (probe == OpengramStrongBox.Probe.AVAILABLE) {
                enableForceEndToEnd(OpengramSecurePolicy.StorageMode.STRONGBOX);
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle(getString(R.string.ForceEndToEndStrongBoxTitle));
                builder.setMessage(getString(R.string.ForceEndToEndStrongBoxWarning));
                builder.setPositiveButton(getString(R.string.OK), (dialogInterface, i) -> enableForceEndToEnd(OpengramSecurePolicy.StorageMode.PLAINTEXT_CONSENT));
                builder.setNegativeButton(getString(R.string.Cancel), (dialogInterface, i) -> listView.adapter.update(true));
                showDialog(builder.create());
            }
        } else if (item.id == 4) {
            if (OpengramSecureChannel.phase() == OpengramSecurePolicy.Phase.DISABLED) {
                return;
            }
            SharedConfig.forceEndToEndForAllChats = !SharedConfig.forceEndToEndForAllChats;
            SharedConfig.saveConfig();
            listView.adapter.update(true);
        }
    }
}
