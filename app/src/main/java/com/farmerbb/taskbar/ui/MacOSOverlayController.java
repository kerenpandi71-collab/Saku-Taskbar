/*
 * Copyright 2026 Saku-Taskbar contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.farmerbb.taskbar.ui;

import android.accessibilityservice.AccessibilityService;
import android.os.BatteryManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.farmerbb.taskbar.R;
import com.farmerbb.taskbar.util.AppEntry;
import com.farmerbb.taskbar.util.U;

import java.util.Date;

/** macOS-inspired desktop chrome with a lightweight material fallback for unsupported blur. */
public final class MacOSOverlayController {
    private static final int MENU_BAR_HEIGHT_DP = 34;
    private static final int REFRESH_INTERVAL_MS = 8000;

    private final Context context;
    private final UIHost host;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View menuBar;
    private View trafficLights;
    private TextView appTitle;
    private TextView timeLabel;
    private ImageView wifiIcon;
    private ImageView bluetoothIcon;
    private ImageView batteryIcon;
    private AppEntry lastLaunchedEntry;
    private String lastLaunchedLabel = "Finder";
    private boolean menuBarAdded;
    private boolean trafficLightsAdded;
    private boolean destroyed;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if(destroyed) return;
            refreshStatus();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    public MacOSOverlayController(Context context, UIHost host) {
        this.context = context;
        this.host = host;
    }

    public void show() {
        createMenuBar();
        createTrafficLights();
        refreshStatus();
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    public void setLastLaunchedApp(AppEntry entry) {
        if(entry == null) return;
        lastLaunchedEntry = entry;
        lastLaunchedLabel = entry.getLabel();
        if(appTitle != null) appTitle.setText(lastLaunchedLabel);
    }

    public void destroy() {
        destroyed = true;
        handler.removeCallbacks(refreshRunnable);
        if(trafficLightsAdded && trafficLights != null) {
            try {
                host.removeView(trafficLights);
            } catch(IllegalArgumentException ignored) {}
        }
        if(menuBarAdded && menuBar != null) {
            try {
                host.removeView(menuBar);
            } catch(IllegalArgumentException ignored) {}
        }
        trafficLightsAdded = false;
        menuBarAdded = false;
        trafficLights = null;
        menuBar = null;
    }

    private void createMenuBar() {
        MacTopBarView bar = new MacTopBarView(context);
        bar.setAppLabel(lastLaunchedLabel);
        appTitle = bar.getAppTitle();
        timeLabel = bar.getTimeLabel();
        wifiIcon = bar.getWifiIcon();
        bluetoothIcon = bar.getBluetoothIcon();
        batteryIcon = bar.getBatteryIcon();

        menuBar = bar;
        ViewParams params = new ViewParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dp(MENU_BAR_HEIGHT_DP),
                Gravity.TOP | Gravity.LEFT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                -1
        ).withBlurBehindRadius(dp(16));

        try {
            host.addView(menuBar, params);
            menuBarAdded = true;
        } catch(IllegalArgumentException | SecurityException ignored) {}
    }

    private ImageView createStatusIcon(LinearLayout parent, String description) {
        ImageView icon = new ImageView(context);
        icon.setContentDescription(description);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(dp(5), dp(5), dp(5), dp(5));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(25), dp(28));
        params.leftMargin = dp(3);
        parent.addView(icon, params);
        return icon;
    }

    private void createTrafficLights() {
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(8), dp(5), dp(8), dp(5));
        controls.setBackground(GlassBackgrounds.createMacGlassSurface(context, 18, 176));
        controls.setElevation(dp(18));

        View close = createTrafficLight(Color.rgb(255, 95, 87), "Close current screen");
        View minimize = createTrafficLight(Color.rgb(254, 188, 46), "Minimize to home");
        View maximize = createTrafficLight(Color.rgb(40, 200, 64), "Maximize last active app");

        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(11), dp(11));
        closeParams.leftMargin = dp(3);
        closeParams.rightMargin = dp(3);
        LinearLayout.LayoutParams minimizeParams = new LinearLayout.LayoutParams(dp(11), dp(11));
        minimizeParams.leftMargin = dp(3);
        minimizeParams.rightMargin = dp(3);
        LinearLayout.LayoutParams maximizeParams = new LinearLayout.LayoutParams(dp(11), dp(11));
        maximizeParams.leftMargin = dp(3);
        maximizeParams.rightMargin = dp(3);
        controls.addView(close, closeParams);
        controls.addView(minimize, minimizeParams);
        controls.addView(maximize, maximizeParams);

        // Back is the public Android analogue for closing the active screen; HOME returns to desktop.
        close.setOnClickListener(v -> U.sendAccessibilityAction(context, AccessibilityService.GLOBAL_ACTION_BACK));
        minimize.setOnClickListener(v -> U.sendAccessibilityAction(context, AccessibilityService.GLOBAL_ACTION_HOME));
        maximize.setOnClickListener(v -> {
            if(lastLaunchedEntry == null) {
                U.showToast(context, "Launch an app from the dock first", Toast.LENGTH_SHORT);
                return;
            }
            U.launchApp(context, lastLaunchedEntry, "fullscreen", true, false, v);
        });

        trafficLights = controls;
        ViewParams params = new ViewParams(
                dp(80),
                dp(30),
                Gravity.TOP | Gravity.LEFT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                dp(MENU_BAR_HEIGHT_DP + 5)
        ).withBlurBehindRadius(dp(10));

        try {
            host.addView(trafficLights, params);
            trafficLightsAdded = true;
        } catch(IllegalArgumentException | SecurityException ignored) {}
    }

    private View createTrafficLight(int color, String description) {
        View dot = new View(context);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        drawable.setStroke(dp(1), Color.argb(80, 255, 255, 255));
        dot.setBackground(drawable);
        dot.setContentDescription(description);
        dot.setClickable(true);
        return dot;
    }

    private void refreshStatus() {
        if(appTitle == null || timeLabel == null) return;
        refreshForegroundApp();
        timeLabel.setText(DateFormat.getTimeFormat(context).format(new Date()));
        updateWifiIcon();
        updateBluetoothIcon();
        updateBatteryIcon();
    }

    private void refreshForegroundApp() {
        String packageName = queryForegroundPackage();
        if(packageName == null) return;

        if(packageName.equals(context.getPackageName())
                || packageName.startsWith("com.farmerbb.taskbar")
                || packageName.startsWith("com.android.systemui")
                || packageName.toLowerCase().contains("launcher")) {
            if(lastLaunchedEntry == null) {
                lastLaunchedLabel = "Finder";
                appTitle.setText(lastLaunchedLabel);
            }
            return;
        }

        AppEntry entry = resolveAppEntry(packageName);
        if(entry != null) {
            lastLaunchedEntry = entry;
            lastLaunchedLabel = entry.getLabel();
            appTitle.setText(lastLaunchedLabel);
        }
    }

    private String queryForegroundPackage() {
        try {
            UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if(manager == null) return null;
            long now = System.currentTimeMillis();
            UsageEvents events = manager.queryEvents(now - 30000, now);
            if(events == null) return null;

            String packageName = null;
            long newestTimestamp = 0;
            UsageEvents.Event event = new UsageEvents.Event();
            while(events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                boolean foregroundEvent = type == UsageEvents.Event.MOVE_TO_FOREGROUND
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && type == UsageEvents.Event.ACTIVITY_RESUMED);
                if(foregroundEvent && event.getTimeStamp() >= newestTimestamp) {
                    newestTimestamp = event.getTimeStamp();
                    packageName = event.getPackageName();
                }
            }
            return packageName;
        } catch(SecurityException | IllegalStateException ignored) {
            return null;
        }
    }

    private AppEntry resolveAppEntry(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if(launchIntent == null || launchIntent.getComponent() == null) return null;
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
            Drawable icon = pm.getApplicationIcon(packageName);
            return new AppEntry(packageName, launchIntent.getComponent().flattenToString(),
                    label.toString(), icon, false);
        } catch(PackageManager.NameNotFoundException | RuntimeException ignored) {
            return null;
        }
    }

    private void updateWifiIcon() {
        if(wifiIcon == null) return;
        Drawable drawable;
        boolean connected = false;
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo network = manager == null ? null : manager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            connected = network != null && network.isConnected();
            if(connected) {
                WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wifiManager == null ? null : wifiManager.getConnectionInfo();
                int level = info == null ? 3 : WifiManager.calculateSignalLevel(info.getRssi(), 5);
                drawable = namedDrawable("tb_signal_wifi_" + Math.max(0, Math.min(4, level)) + "_bar");
            } else {
                drawable = namedDrawable("tb_signal_wifi_0_bar");
            }
        } catch(RuntimeException ignored) {
            drawable = namedDrawable("tb_signal_wifi_0_bar");
        }
        setStatusDrawable(wifiIcon, drawable, connected ? 1f : 0.48f);
    }

    private void updateBluetoothIcon() {
        if(bluetoothIcon == null) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if(adapter == null) {
            bluetoothIcon.setVisibility(View.GONE);
            return;
        }
        boolean enabled;
        try {
            enabled = adapter.isEnabled();
        } catch(SecurityException ignored) {
            enabled = false;
        }
        setStatusDrawable(bluetoothIcon, ContextCompat.getDrawable(context, R.drawable.tb_bluetooth),
                enabled ? 1f : 0.48f);
    }

    private void updateBatteryIcon() {
        if(batteryIcon == null) return;
        Drawable drawable;
        float alpha = 1f;
        try {
            BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            int level = manager == null ? Integer.MIN_VALUE : manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if(level == Integer.MIN_VALUE) {
                drawable = namedDrawable("tb_battery_full");
            } else {
                Intent statusIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int status = statusIntent == null ? -1 : statusIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                int bucket = level >= 95 ? 100 : level >= 80 ? 90 : level >= 60 ? 80
                        : level >= 50 ? 60 : level >= 30 ? 50 : level >= 20 ? 30 : 20;
                String name = charging
                        ? (bucket == 100 ? "tb_battery_charging_full" : "tb_battery_charging_" + bucket)
                        : (bucket == 100 ? "tb_battery_full" : "tb_battery_" + bucket);
                drawable = namedDrawable(name);
                if(level <= 20) alpha = 0.78f;
            }
        } catch(RuntimeException ignored) {
            drawable = namedDrawable("tb_battery_full");
        }
        setStatusDrawable(batteryIcon, drawable, alpha);
    }

    private Drawable namedDrawable(String name) {
        int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
        return id == 0 ? null : ContextCompat.getDrawable(context, id);
    }

    private void setStatusDrawable(ImageView view, Drawable drawable, float alpha) {
        if(drawable == null) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setImageDrawable(drawable.mutate());
        view.setColorFilter(Color.WHITE);
        view.setAlpha(alpha);
    }

    private int dp(float value) {
        return GlassBackgrounds.dp(context, value);
    }
}
