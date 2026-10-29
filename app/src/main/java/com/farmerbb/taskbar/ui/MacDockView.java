/*
 * Copyright 2026 Saku-Taskbar contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.farmerbb.taskbar.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;

/** Windows 11 taskbar surface with touch-friendly pinned apps. */
public final class MacDockView extends ViewGroup {
    private static final float MAX_ICON_WIDTH_DP = 54f;
    private static final float ITEM_HEIGHT_DP = 52f;
    private static final float ICON_GAP_DP = 7f;

    private final int itemHeight;
    private final int itemGap;

    public MacDockView(Context context, AttributeSet attrs) {
        this(context);
    }

    public MacDockView(Context context) {
        super(context);
        itemHeight = dp(ITEM_HEIGHT_DP);
        itemGap = dp(ICON_GAP_DP);
        setPadding(dp(10), dp(6), dp(10), dp(6));
        setClipChildren(false);
        setClipToPadding(false);
        setElevation(dp(10));

        GradientDrawable taskbar = new GradientDrawable();
        taskbar.setColor(Color.argb(224, 20, 27, 42));
        taskbar.setCornerRadius(dp(14));
        taskbar.setStroke(Math.max(1, dp(1)), Color.argb(88, 190, 215, 255));
        setBackground(taskbar);
        setFocusable(false);
        setClickable(false);
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        child.setOnHoverListener((view, event) -> {
            if(event.getAction() == MotionEvent.ACTION_HOVER_ENTER
                    || event.getAction() == MotionEvent.ACTION_HOVER_MOVE) {
                // Keep the taskbar stable: no magnification animation on low-memory devices.
            }
            if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
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

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
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
        public LayoutParams(Context context, AttributeSet attrs) { super(context, attrs); }
        public LayoutParams(int width, int height) { super(width, height); }
        public LayoutParams(ViewGroup.LayoutParams source) { super(source); }
    }
}