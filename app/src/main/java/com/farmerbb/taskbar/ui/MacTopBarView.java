/*
 * Copyright 2026 Saku-Taskbar contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.farmerbb.taskbar.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

/** Dedicated translucent macOS menu bar rendered as an always-on-top system overlay. */
public final class MacTopBarView extends LinearLayout {
    private final TextView appTitle;
    private final TextView timeLabel;
    private final ImageView wifiIcon;
    private final ImageView bluetoothIcon;
    private final ImageView batteryIcon;

    public MacTopBarView(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(16), 0, dp(16), 0);
        setBackground(GlassBackgrounds.createMacGlassSurface(context, 0, 184));
        setElevation(dp(24));
        setClipChildren(false);
        setClipToPadding(false);

        TextView logo = new TextView(context);
        logo.setText("⌘");
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(19);
        logo.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        logo.setContentDescription("macOS menu");
        logo.setPadding(0, 0, dp(10), 0);
        logo.setOnClickListener(v -> NotificationOverlayPanel.showPowerMenu(context, v));
        addView(logo, new LinearLayout.LayoutParams(dp(28), WindowManager.LayoutParams.MATCH_PARENT));

        appTitle = new TextView(context);
        appTitle.setText("Finder");
        appTitle.setTextColor(Color.WHITE);
        appTitle.setTextSize(13);
        appTitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        appTitle.setSingleLine(true);
        appTitle.setMaxWidth(dp(220));
        addView(appTitle, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.MATCH_PARENT));

        Space spacer = new Space(context);
        addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

        wifiIcon = createStatusIcon("Wi-Fi");
        bluetoothIcon = createStatusIcon("Bluetooth");
        batteryIcon = createStatusIcon("Battery");

        timeLabel = new TextView(context);
        timeLabel.setTextColor(Color.WHITE);
        timeLabel.setTextSize(12);
        timeLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        timeLabel.setPadding(dp(8), 0, 0, 0);
        timeLabel.setSingleLine(true);
        addView(timeLabel, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.MATCH_PARENT));
    }

    public void setAppLabel(String label) {
        appTitle.setText(label == null || label.trim().isEmpty() ? "Finder" : label);
    }
    public TextView getAppTitle() { return appTitle; }
    public TextView getTimeLabel() { return timeLabel; }
    public ImageView getWifiIcon() { return wifiIcon; }
    public ImageView getBluetoothIcon() { return bluetoothIcon; }
    public ImageView getBatteryIcon() { return batteryIcon; }

    private ImageView createStatusIcon(String description) {
        ImageView icon = new ImageView(getContext());
        icon.setContentDescription(description + " status");
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(25), dp(28));
        params.leftMargin = dp(3);
        addView(icon, params);
        return icon;
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
