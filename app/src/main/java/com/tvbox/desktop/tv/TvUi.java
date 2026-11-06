package com.tvbox.desktop.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

final class TvUi {
    private TvUi() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        return view;
    }

    static TextView button(Context context, String label) {
        TextView view = text(context, label, 17, Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setClickable(true);
        view.setPadding(dp(context, 22), 0, dp(context, 22), 0);
        view.setBackground(selector(context, 8, "#25313B", "#4ED7BA", "#667482", 2));
        view.setOnFocusChangeListener((item, focused) -> {
            item.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(100).start();
            ((TextView) item).setTextColor(focused ? Color.rgb(10, 22, 24) : Color.WHITE);
        });
        return view;
    }

    static StateListDrawable selector(Context context, int radiusDp, String normal, String focused,
                                      String stroke, int strokeDp) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},
                shape(context, radiusDp, focused, focused, strokeDp));
        states.addState(new int[]{}, shape(context, radiusDp, normal, stroke, strokeDp));
        return states;
    }

    static StateListDrawable focusBorderSelector(Context context, int radiusDp, String fill,
                                                 String focusedStroke, int focusedStrokeDp) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},
                shape(context, radiusDp, fill, focusedStroke, focusedStrokeDp));
        states.addState(new int[]{}, shape(context, radiusDp, fill, fill, 0));
        return states;
    }

    static GradientDrawable shape(Context context, int radiusDp, String fill, String stroke, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(fill));
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(context, strokeDp), Color.parseColor(stroke));
        return drawable;
    }

    static void focusScale(View view) {
        view.setOnFocusChangeListener((item, focused) ->
                item.animate().scaleX(focused ? 1.055f : 1f).scaleY(focused ? 1.055f : 1f)
                        .setDuration(110).start());
    }
}
