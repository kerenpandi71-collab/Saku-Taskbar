/*
 * Copyright 2026 Saku-Taskbar contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.farmerbb.taskbar.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;

/** Floating, content-sized macOS Dock with neighbor-aware pointer magnification. */
public final class MacDockView extends ViewGroup {
    private static final float MAX_ICON_WIDTH_DP = 76f;
    private static final float ITEM_HEIGHT_DP = 58f;
    private static final float ICON_GAP_DP = 5f;
    private static final float MAGNIFICATION_RADIUS_DP = 126f;
    private static final float MAX_SCALE = 1.42f;

    private final int itemHeight;
    private final int itemGap;

    public MacDockView(Context context) {
        super(context);
        itemHeight = dp(ITEM_HEIGHT_DP);
        itemGap = dp(ICON_GAP_DP);
        setPadding(dp(12), dp(8), dp(12), dp(12));
        setClipChildren(false);
        setClipToPadding(false);
        setElevation(dp(18));

        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Color.argb(204, 82, 91, 108), Color.argb(188, 30, 35, 48) });
        glass.setCornerRadius(dp(24));
        glass.setStroke(Math.max(1, dp(1)), Color.argb(100, 255, 255, 255));
        setBackground(glass);
        setFocusable(false);
        setClickable(false);
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        child.setOnHoverListener((view, event) -> {
            int action = event.getAction();
            if(action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE)
                magnifyAt(event.getRawX());
            else if(action == MotionEvent.ACTION_HOVER_EXIT)
                resetMagnification();
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                view.setPointerIcon(PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_DEFAULT));
            return false;
        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int totalWidth = getPaddingLeft() + getPaddingRight();
        int childWidthSpec = MeasureSpec.makeMeasureSpec(dp(MAX_ICON_WIDTH_DP), MeasureSpec.AT_MOST);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(itemHeight, MeasureSpec.EXACTLY);
        int visibleChildren = 0;
        for(int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if(child.getVisibility() == GONE) continue;
            child.measure(childWidthSpec, childHeightSpec);
            totalWidth += child.getMeasuredWidth();
            visibleChildren++;
        }
        if(visibleChildren > 1) totalWidth += itemGap * (visibleChildren - 1);
        int totalHeight = getPaddingTop() + itemHeight + getPaddingBottom();
        setMeasuredDimension(resolveSize(totalWidth, widthMeasureSpec),
                resolveSize(totalHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int x = getPaddingLeft();
        int y = getPaddingTop();
        for(int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if(child.getVisibility() == GONE) continue;
            int width = child.getMeasuredWidth();
            child.layout(x, y, x + width, y + child.getMeasuredHeight());
            x += width + itemGap;
        }
    }

    private void magnifyAt(float rawX) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        float pointerX = rawX - location[0];
        float radius = dp(MAGNIFICATION_RADIUS_DP);
        for(int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if(child.getVisibility() == GONE) continue;
            float center = child.getLeft() + child.getWidth() / 2f;
            float influence = Math.max(0f, 1f - Math.abs(pointerX - center) / radius);
            float scale = 1f + (MAX_SCALE - 1f) * influence;
            child.animate().cancel();
            child.setPivotX(child.getWidth() / 2f);
            child.setPivotY(child.getHeight());
            child.animate().scaleX(scale).scaleY(scale)
                    .translationY(-dp(18) * (scale - 1f) / (MAX_SCALE - 1f))
                    .setDuration(90).start();
        }
    }

    private void resetMagnification() {
        for(int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.animate().cancel();
            child.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(150).start();
        }
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
    }

    @Override
    public LayoutParams generateLayoutParams(android.util.AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams params) {
        return new LayoutParams(params);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams params) {
        return params instanceof LayoutParams;
    }

    public static final class LayoutParams extends ViewGroup.LayoutParams {
        public LayoutParams(Context context, android.util.AttributeSet attrs) { super(context, attrs); }
        public LayoutParams(int width, int height) { super(width, height); }
        public LayoutParams(ViewGroup.LayoutParams source) { super(source); }
    }
}
