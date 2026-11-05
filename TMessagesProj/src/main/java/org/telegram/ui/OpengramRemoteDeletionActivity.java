package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.OpengramRemoteDeletion;
import org.telegram.messenger.OpengramRemoteDeletionExport;
import org.telegram.messenger.OpengramSnapshotFile;
import org.telegram.messenger.OpengramStrongBox;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class OpengramRemoteDeletionActivity extends BaseFragment {

    private static final int REQUEST_EXPORT = 47;

    private UniversalRecyclerView listView;
    private boolean exporting;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.RemoteDeletionOptions));
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
        items.add(UItem.asCheck(1, getString(R.string.RemoteDeletion)).setChecked(getUserConfig().remoteDeletion));
        items.add(UItem.asShadow(getString(R.string.RemoteDeletionInfo)));
        items.add(UItem.asButton(2, getString(R.string.RemoteDeletionExport)));
        items.add(UItem.asShadow(getString(R.string.RemoteDeletionExportInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 1) {
            if (getUserConfig().remoteDeletion) {
                confirmDisable();
                return;
            }
            enable();
        } else if (item.id == 2) {
            openExportPicker();
        }
    }

    private void enable() {
        if (getParentActivity() == null) {
            refresh();
            return;
        }
        long clientUserId = getUserConfig().getClientUserId();
        if (clientUserId <= 0) {
            showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
            refresh();
            return;
        }
        OpengramStrongBox.Probe probe = OpengramStrongBox.forDeletions(clientUserId).probeKey();
        if (probe == OpengramStrongBox.Probe.ERROR) {
            showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
            refresh();
            return;
        }
        if (probe == OpengramStrongBox.Probe.AVAILABLE) {
            if (!OpengramRemoteDeletion.enable(currentAccount, OpengramSnapshotFile.MODE_STRONGBOX)) {
                showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
            }
            refresh();
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(getString(R.string.ForceEndToEndStrongBoxTitle));
            builder.setMessage(getString(R.string.RemoteDeletionStrongBoxWarning));
            builder.setPositiveButton(getString(R.string.OK), (dialogInterface, i) -> {
                if (!OpengramRemoteDeletion.enable(currentAccount, OpengramSnapshotFile.MODE_PLAINTEXT)) {
                    showNotice(getString(R.string.ForceEndToEndStrongBoxTitle), getString(R.string.MessageFilterStorageError));
                }
                refresh();
            });
            builder.setNegativeButton(getString(R.string.Cancel), (dialogInterface, i) -> refresh());
            showDialog(builder.create());
        }
    }

    private void confirmDisable() {
        OpengramRemoteDeletion.KeptMessages kept = OpengramRemoteDeletion.kept(currentAccount);
        if (kept.isEmpty() || getParentActivity() == null) {
            OpengramRemoteDeletion.disable(currentAccount);
            refresh();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.RemoteDeletion));
        builder.setMessage(getString(R.string.RemoteDeletionDisableDelete));
        builder.setPositiveButton(getString(R.string.Delete), (dialogInterface, i) -> {
            OpengramRemoteDeletion.disable(currentAccount);
            OpengramRemoteDeletion.deleteKeptLocally(currentAccount, kept);
            refresh();
        });
        builder.setNegativeButton(getString(R.string.RemoteDeletionDisableKeep), (dialogInterface, i) -> {
            OpengramRemoteDeletion.disable(currentAccount);
            refresh();
        });
        builder.setOnDismissListener(dialog -> refresh());
        showDialog(builder.create());
    }

    private void openExportPicker() {
        if (exporting || getParentActivity() == null) {
            return;
        }
        if (OpengramRemoteDeletion.kept(currentAccount).isEmpty()) {
            showNotice(getString(R.string.RemoteDeletionOptions), getString(R.string.RemoteDeletionExportEmpty));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "opengram-deleted-messages.zip");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_EXPORT || resultCode != Activity.RESULT_OK || data == null || data.getData() == null || getParentActivity() == null) {
            return;
        }
        Uri uri = data.getData();
        ContentResolver resolver = getParentActivity().getContentResolver();
        exporting = true;
        AtomicBoolean cancelled = new AtomicBoolean();
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(true);
        if (showDialog(progress, dialog -> cancelled.set(true)) == null) {
            exporting = false;
            return;
        }
        int account = currentAccount;
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            OpengramRemoteDeletionExport.Result result = null;
            try {
                OutputStream out = resolver.openOutputStream(uri);
                if (out != null) {
                    try {
                        result = OpengramRemoteDeletionExport.write(account, out, cancelled);
                        ok = true;
                    } finally {
                        out.close();
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                ok = false;
            }
            boolean wrote = ok;
            OpengramRemoteDeletionExport.Result exportResult = result;
            AndroidUtilities.runOnUIThread(() -> {
                exporting = false;
                boolean stopped = cancelled.get();
                try {
                    progress.dismiss();
                } catch (Exception ignored) {
                }
                if (stopped || (exportResult != null && exportResult.cancelled)) {
                    return;
                }
                if (!wrote || exportResult == null) {
                    showNotice(getString(R.string.RemoteDeletionOptions), getString(R.string.RemoteDeletionExportFailed));
                } else if (exportResult.written == 0 && exportResult.failed > 0) {
                    showNotice(getString(R.string.RemoteDeletionOptions), getString(R.string.RemoteDeletionExportFailed));
                } else if (exportResult.written == 0) {
                    showNotice(getString(R.string.RemoteDeletionOptions), getString(R.string.RemoteDeletionExportEmpty));
                } else if (exportResult.failed > 0 || exportResult.missing > 0) {
                    showNotice(getString(R.string.RemoteDeletionOptions), getString(R.string.RemoteDeletionExportPartial));
                }
            });
        });
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

    private void refresh() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
