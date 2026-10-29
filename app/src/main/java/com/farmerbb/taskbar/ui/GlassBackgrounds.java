/*
 * Copyright 2026 Saku-Taskbar contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.farmerbb.taskbar.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/**
 * Lightweight glass-style surfaces. Android does not expose real-time backdrop blur to ordinary
 * overlays, so these use translucent dark fills and a subtle outline without a blur/rendering pass.
 */
public final class GlassBackgrounds {
    private GlassBackgrounds() {}

    public static GradientDrawable createGlassSurface(Context context, float radiusDp) {
        return createGlassSurface(context, radiusDp, Color.argb(232, 22, 27, 39));
    }

    public static GradientDrawable createGlassSurface(Context context, float radiusDp, int fillColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setColor(withAlpha(fillColor, 232));
        drawable.setStroke(Math.max(1, dp(context, 1)), Color.argb(64, 255, 255, 255));
        return drawable;
    }

    public static GradientDrawable createMacGlassSurface(Context context, float radiusDp, int alpha) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(context, radiusDp));
        int safeAlpha = Math.max(96, Math.min(224, alpha));
        drawable.setColor(Color.argb(safeAlpha, 30, 36, 54));
        drawable.setStroke(Math.max(1, dp(context, 1)), Color.argb(68, 255, 255, 255));
        return drawable;
    }

    public static GradientDrawable createPill(Context context, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(context, 999));
        drawable.setColor(selected
                ? Color.argb(220, 62, 101, 166)
                : Color.argb(120, 255, 255, 255));
        drawable.setStroke(Math.max(1, dp(context, 1)),
                selected ? Color.argb(135, 160, 194, 255) : Color.argb(48, 255, 255, 255));
        return drawable;
    }

    public static void applyGlass(View view, Context context, float radiusDp) {
        view.setBackground(createGlassSurface(context, radiusDp));
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
