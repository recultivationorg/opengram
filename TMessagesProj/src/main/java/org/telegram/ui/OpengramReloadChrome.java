package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * Shared layout for the reload screens: a gray page, liquid-glass panels,
 * and centered round buttons.
 */
final class OpengramReloadChrome {

    final GlassPage page;
    private final BlurredBackgroundDrawableViewFactory factory;

    OpengramReloadChrome(Context context) {
        page = new GlassPage(context);
        factory = page.factory;
    }

    void show(LinearLayout content, boolean center) {
        page.removeAllViews();
        int gravity = center ? Gravity.CENTER : Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        page.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, gravity, 16, center ? 0 : 12, 16, 16));
    }

    LinearLayout column(boolean center) {
        LinearLayout column = new LinearLayout(page.getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(center ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        return column;
    }

    LinearLayout card() {
        LinearLayout card = new LinearLayout(page.getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);
        glass(card, 22);
        card.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return card;
    }

    TextView body(String text) {
        TextView view = new TextView(page.getContext());
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setText(text);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setLineSpacing(dp(2), 1f);
        view.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return view;
    }

    ButtonWithCounterView button(String text, View.OnClickListener listener) {
        ButtonWithCounterView button = new ButtonWithCounterView(page.getContext(), null).setRound();
        button.setText(text, false);
        button.setMinWidth(dp(196));
        button.setPadding(dp(28), 0, dp(28), 0);
        button.setOnClickListener(listener);
        button.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 48, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
        return button;
    }

    void roundField(EditTextBoldCursor field) {
        field.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_windowBackgroundWhite)));
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        field.setClipToOutline(true);
        field.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
    }

    private void glass(View view, float radiusDp) {
        BlurredBackgroundDrawable drawable = factory.create(view, BlurredBackgroundProviderImpl.mainTabs(null));
        drawable.setRadius(dp(radiusDp));
        view.setBackground(drawable);
    }

    static final class GlassPage extends FrameLayout {

        final BlurredBackgroundDrawableViewFactory factory;
        private final BlurredBackgroundSourceColor colorSource;
        private final BlurredBackgroundSourceRenderNode glassSource;

        GlassPage(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            colorSource = new BlurredBackgroundSourceColor();
            colorSource.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                glassSource = new BlurredBackgroundSourceRenderNode(colorSource);
                factory = new BlurredBackgroundDrawableViewFactory(glassSource);
                factory.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
            } else {
                glassSource = null;
                factory = new BlurredBackgroundDrawableViewFactory(colorSource);
            }
            factory.setSourceRootView(new ViewPositionWatcher(this), this);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (glassSource != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && getWidth() > 0 && getHeight() > 0 && !glassSource.inRecording()) {
                Canvas recording = glassSource.beginRecording(getWidth(), getHeight());
                recording.drawColor(Theme.getColor(Theme.key_windowBackgroundGray));
                glassSource.endRecording();
            }
            super.dispatchDraw(canvas);
        }
    }
}
