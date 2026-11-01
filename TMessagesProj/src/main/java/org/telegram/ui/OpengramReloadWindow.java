package org.telegram.ui;

import android.app.Activity;
import android.view.Window;
import android.view.WindowManager;

final class OpengramReloadWindow {

    private int flags;
    private boolean applied;

    void acquire(Activity activity) {
        if (activity == null || applied) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        flags = window.getAttributes().flags;
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        applied = true;
    }

    void release(Activity activity) {
        if (!applied) {
            return;
        }
        applied = false;
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        if (window != null && (flags & WindowManager.LayoutParams.FLAG_SECURE) == 0) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
