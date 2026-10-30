/*
 * Copyright 2026 Saku-Taskbar contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.farmerbb.taskbar.ui;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.accessibilityservice.AccessibilityService;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.farmerbb.taskbar.activity.MainActivity;
import com.farmerbb.taskbar.service.NotificationCountService;
import com.farmerbb.taskbar.util.U;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A lightweight notification / quick-settings panel hosted on the display containing Taskbar.
 * It intentionally does not invoke Android's global notification-shade actions, which are
 * commonly routed to display 0 on secondary-display desktops.
 */
public final class NotificationOverlayPanel {
    private static final int MODE_NOTIFICATIONS = 0;
    private static final int MODE_QUICK_SETTINGS = 1;
    private static final int MODE_POWER = 2;
    private static final int SHIZUKU_REQUEST_CODE = 4107;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService COMMAND_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Saku-Taskbar-System-Commands");
        thread.setDaemon(true);
        return thread;
    });

    private static NotificationOverlayPanel activePanel;

    private final Context context;
    private final WindowManager windowManager;
    private final int displayId;
    private final int windowType;
    private FrameLayout overlayRoot;
    private LinearLayout panelLayout;
    private LinearLayout contentLayout;
    private int mode;
    private boolean receiverRegistered;
    private boolean attached;

    private final android.content.BroadcastReceiver notificationReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if(intent != null && NotificationCountService.ACTION_NOTIFICATION_OVERLAY_CHANGED
                            .equals(intent.getAction()) && mode == MODE_NOTIFICATIONS) {
                        renderContent();
                    }
                }
            };

    private NotificationOverlayPanel(Context baseContext, View anchor, int requestedMode) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        Context displayContext = baseContext;
        int requestedDisplayId = -1;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && anchor.getDisplay() != null) {
            displayContext = baseContext.createDisplayContext(anchor.getDisplay());
            requestedDisplayId = anchor.getDisplay().getDisplayId();
        }
        Context hostContext = displayContext;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                hostContext = displayContext.createWindowContext(type, null);
            } catch(IllegalArgumentException | SecurityException ignored) {}
        }
        context = hostContext;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        displayId = requestedDisplayId;
        windowType = type;
        mode = requestedMode;
    }

    public static void toggleNotifications(Context context, View anchor) {
        open(context, anchor, MODE_NOTIFICATIONS, true);
    }

    public static void showQuickSettings(Context context, View anchor) {
        open(context, anchor, MODE_QUICK_SETTINGS, false);
    }

    public static void showPowerMenu(Context context, View anchor) {
        open(context, anchor, MODE_POWER, true);
    }

    private static synchronized void open(Context context, View anchor, int requestedMode,
                                           boolean toggleSameMode) {
        if(anchor == null) return;
        int requestedDisplay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                && anchor.getDisplay() != null ? anchor.getDisplay().getDisplayId() : -1;

        if(activePanel != null) {
            if(activePanel.displayId == requestedDisplay && activePanel.mode == requestedMode) {
                if(toggleSameMode) {
                    activePanel.dismiss();
                    return;
                }
                activePanel.renderHeaderAndContent();
                return;
            }
            if(activePanel.displayId == requestedDisplay) {
                activePanel.mode = requestedMode;
                activePanel.updatePanelPlacement();
                activePanel.renderHeaderAndContent();
                return;
            }
            activePanel.dismiss();
        }

        NotificationOverlayPanel panel = new NotificationOverlayPanel(context, anchor, requestedMode);
        try {
            panel.attach();
            activePanel = panel;
        } catch(SecurityException | IllegalArgumentException e) {
            Toast.makeText(context, "Taskbar needs permission to draw overlays.", Toast.LENGTH_LONG).show();
            panel.dismiss();
            panel.openOverlaySettings();
        } catch(WindowManager.BadTokenException e) {
            Toast.makeText(context, "Couldn't open the custom Taskbar panel on this display.", Toast.LENGTH_LONG).show();
            panel.dismiss();
        }
    }

    private void attach() {
        overlayRoot = new FrameLayout(context);
        overlayRoot.setBackgroundColor(Color.argb(105, 0, 0, 0));
        overlayRoot.setClickable(true);
        overlayRoot.setOnClickListener(v -> dismiss());

        panelLayout = new LinearLayout(context);
        panelLayout.setOrientation(LinearLayout.VERTICAL);
        panelLayout.setPadding(dp(14), dp(12), dp(14), dp(14));
        panelLayout.setBackground(GlassBackgrounds.createGlassSurface(context, 22));
        panelLayout.setClickable(true);
        panelLayout.setOnClickListener(v -> {});

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                getPanelWidth(), WindowManager.LayoutParams.WRAP_CONTENT);
        overlayRoot.addView(panelLayout, panelParams);
        updatePanelPlacement();
        buildHeader();
        contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        contentParams.topMargin = dp(8);
        panelLayout.addView(contentLayout, contentParams);
        renderContent();

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        windowManager.addView(overlayRoot, params);
        attached = true;
        registerNotificationReceiver();
    }

    private void buildHeader() {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = makeText(modeTitle(), 18, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(34), 1f));
        TextView close = makeButton("×", false);
        close.setTextSize(23);
        close.setContentDescription("Close panel");
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(38), dp(34)));
        panelLayout.addView(header);

        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(6), 0, dp(2));
        tabs.setVisibility(mode == MODE_POWER ? View.GONE : View.VISIBLE);
        TextView notifications = makeButton("Notifications", mode == MODE_NOTIFICATIONS);
        notifications.setOnClickListener(v -> {
            mode = MODE_NOTIFICATIONS;
            updatePanelPlacement();
            renderHeaderAndContent();
        });
        TextView quickSettings = makeButton("Quick settings", mode == MODE_QUICK_SETTINGS);
        quickSettings.setOnClickListener(v -> {
            mode = MODE_QUICK_SETTINGS;
            updatePanelPlacement();
            renderHeaderAndContent();
        });
        tabs.addView(notifications, new LinearLayout.LayoutParams(0, dp(38), 1f));
        LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        quickParams.leftMargin = dp(8);
        tabs.addView(quickSettings, quickParams);
        panelLayout.addView(tabs);
        panelLayout.setTag(tabs);
    }

    private void renderHeaderAndContent() {
        if(panelLayout == null) return;
        panelLayout.removeAllViews();
        buildHeader();
        contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        contentParams.topMargin = dp(8);
        panelLayout.addView(contentLayout, contentParams);
        renderContent();
    }

    private void renderContent() {
        if(contentLayout == null) return;
        contentLayout.removeAllViews();
        if(mode == MODE_NOTIFICATIONS) {
            buildNotificationsContent();
        } else if(mode == MODE_QUICK_SETTINGS) {
            buildQuickSettingsContent();
        } else {
            buildPowerMenuContent();
        }
        updatePanelPlacement();
    }

    private void buildNotificationsContent() {
        if(!NotificationCountService.isConnected()) {
            TextView info = makeText("Notification access is not enabled for Saku-Taskbar.", 13,
                    Color.LTGRAY, false);
            info.setPadding(dp(4), dp(8), dp(4), dp(8));
            contentLayout.addView(info);
            TextView enable = makeButton("Enable notification access", true);
            enable.setOnClickListener(v -> launchOnDisplay(
                    new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
            LinearLayout.LayoutParams enableParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
            enableParams.topMargin = dp(6);
            contentLayout.addView(enable, enableParams);
            return;
        }

        TextView summary = makeText("Recent notifications", 12, Color.LTGRAY, true);
        summary.setPadding(dp(3), dp(1), dp(3), dp(7));
        contentLayout.addView(summary);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        LinearLayout notificationItems = new LinearLayout(context);
        notificationItems.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(notificationItems, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        StatusBarNotification[] notifications = NotificationCountService.getActiveNotificationsSnapshot();
        Arrays.sort(notifications, Comparator.comparingLong(StatusBarNotification::getPostTime).reversed());
        if(notifications.length == 0) {
            TextView empty = makeText("You're all caught up.", 13, Color.LTGRAY, false);
            empty.setPadding(dp(4), dp(16), dp(4), dp(16));
            notificationItems.addView(empty);
        } else {
            for(StatusBarNotification sbn : notifications) {
                if(sbn != null && sbn.getNotification() != null) {
                    notificationItems.addView(createNotificationCard(sbn));
                }
            }
        }
        contentLayout.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.min(dp(310), Math.max(dp(92), getDisplayHeight() - dp(210)))));

        TextView clearAll = makeButton("Clear all notifications", false);
        clearAll.setOnClickListener(v -> {
            NotificationCountService.dismissAllNotifications();
            renderContent();
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        clearParams.topMargin = dp(8);
        contentLayout.addView(clearAll, clearParams);
    }

    private View createNotificationCard(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(8), dp(8));
        card.setBackground(GlassBackgrounds.createGlassSurface(context, 13,
                Color.argb(150, 35, 42, 56)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(7);
        card.setLayoutParams(cardParams);

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);

        String packageLabel = getApplicationLabel(sbn.getPackageName());
        CharSequence titleValue = notification.extras != null
                ? notification.extras.getCharSequence(Notification.EXTRA_TITLE) : null;
        String title = titleValue != null && titleValue.length() > 0
                ? titleValue.toString() : packageLabel;
        TextView titleView = makeText(title, 13, Color.WHITE, true);
        titleView.setMaxLines(2);
        top.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if(sbn.isClearable()) {
            TextView dismissButton = makeButton("×", false);
            dismissButton.setTextSize(18);
            dismissButton.setOnClickListener(v -> {
                NotificationCountService.dismissNotificationByKey(sbn.getKey());
                renderContent();
            });
            top.addView(dismissButton, new LinearLayout.LayoutParams(dp(34), dp(30)));
        }
        card.addView(top);

        CharSequence textValue = notification.extras != null
                ? notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) : null;
        if(textValue == null && notification.extras != null) {
            textValue = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        }
        if(textValue != null && textValue.length() > 0) {
            TextView text = makeText(textValue.toString(), 12, Color.LTGRAY, false);
            text.setMaxLines(4);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            textParams.topMargin = dp(3);
            card.addView(text, textParams);
        }

        Notification.Action[] actions = notification.actions;
        if(actions != null && actions.length > 0) {
            LinearLayout actionsRow = new LinearLayout(context);
            actionsRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            actionsParams.topMargin = dp(5);
            int added = 0;
            for(Notification.Action action : actions) {
                if(action == null || action.actionIntent == null || added >= 3) continue;
                TextView actionButton = makeButton(action.title != null ? action.title.toString() : "Open", false);
                actionButton.setMaxLines(1);
                actionButton.setOnClickListener(v -> {
                    sendPendingIntent(action.actionIntent);
                    dismiss();
                });
                LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(34), 1f);
                if(added > 0) actionParams.leftMargin = dp(4);
                actionsRow.addView(actionButton, actionParams);
                added++;
            }
            if(added > 0) card.addView(actionsRow, actionsParams);
        }

        card.setOnClickListener(v -> {
            if(notification.contentIntent != null) {
                sendPendingIntent(notification.contentIntent);
                if((notification.flags & Notification.FLAG_AUTO_CANCEL) != 0) {
                    NotificationCountService.dismissNotificationByKey(sbn.getKey());
                }
                dismiss();
            }
        });
        return card;
    }

    private void buildQuickSettingsContent() {
        TextView volumeLabel = makeText("Volume media", 13, Color.WHITE, true);
        contentLayout.addView(volumeLabel);
        SeekBar volume = new SeekBar(context);
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if(audio != null) {
            int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            volume.setMax(max);
            volume.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if(fromUser) {
                        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0); }
                        catch(SecurityException ignored) {}
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }
        contentLayout.addView(volume, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        TextView brightnessLabel = makeText("Kecerahan layar", 13, Color.WHITE, true);
        LinearLayout.LayoutParams brightLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brightLabelParams.topMargin = dp(8);
        contentLayout.addView(brightnessLabel, brightLabelParams);
        SeekBar brightness = new SeekBar(context);
        brightness.setMax(255);
        brightness.setProgress(Settings.System.getInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, 128));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if(!fromUser) return;
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(context)) {
                    try {
                        Settings.System.putInt(context.getContentResolver(),
                                Settings.System.SCREEN_BRIGHTNESS, Math.max(1, progress));
                    } catch(SecurityException ignored) {}
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    intent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
                    launchOnDisplay(intent);
                    Toast.makeText(context, "Izinkan Modify system settings, lalu atur kecerahan lagi.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        contentLayout.addView(brightness, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        TextView connectivity = makeText("Koneksi", 13, Color.WHITE, true);
        LinearLayout.LayoutParams connectivityParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        connectivityParams.topMargin = dp(10);
        connectivityParams.bottomMargin = dp(7);
        contentLayout.addView(connectivity, connectivityParams);

        LinearLayout firstRow = new LinearLayout(context);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        boolean wifiEnabled = isWifiEnabled();
        TextView wifi = makeQuickSettingTile("◉", "Wi-Fi", wifiEnabled ? "Aktif" : "Nonaktif", wifiEnabled);
        wifi.setOnClickListener(v -> executeSystemCommand(
                "svc wifi " + (isWifiEnabled() ? "disable" : "enable"), "Wi-Fi"));
        boolean bluetoothEnabled = isBluetoothEnabled();
        TextView bluetooth = makeQuickSettingTile("ᛒ", "Bluetooth",
                bluetoothEnabled ? "Aktif" : "Nonaktif", bluetoothEnabled);
        bluetooth.setOnClickListener(v -> executeSystemCommand(
                "svc bluetooth " + (isBluetoothEnabled() ? "disable" : "enable"), "Bluetooth"));
        LinearLayout.LayoutParams tileLeft = new LinearLayout.LayoutParams(0, dp(76), 1f);
        tileLeft.rightMargin = dp(7);
        firstRow.addView(wifi, tileLeft);
        firstRow.addView(bluetooth, new LinearLayout.LayoutParams(0, dp(76), 1f));
        contentLayout.addView(firstRow);

        LinearLayout secondRow = new LinearLayout(context);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        boolean mobileEnabled = Settings.Global.getInt(context.getContentResolver(), "mobile_data", 0) == 1;
        TextView mobile = makeQuickSettingTile("▥", "Data seluler",
                mobileEnabled ? "Aktif" : "Nonaktif", mobileEnabled);
        mobile.setOnClickListener(v -> executeSystemCommand(
                "svc data " + (Settings.Global.getInt(context.getContentResolver(), "mobile_data", 0) == 1
                        ? "disable" : "enable"), "Data seluler"));
        boolean airplaneEnabled = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        TextView airplane = makeQuickSettingTile("✈", "Mode pesawat",
                airplaneEnabled ? "Aktif" : "Nonaktif", airplaneEnabled);
        airplane.setOnClickListener(v -> {
            boolean enable = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 1;
            executeSystemCommand("settings put global airplane_mode_on " + (enable ? "1" : "0")
                    + "; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + enable,
                    "Mode pesawat");
        });
        LinearLayout.LayoutParams mobileParams = new LinearLayout.LayoutParams(0, dp(76), 1f);
        mobileParams.rightMargin = dp(7);
        mobileParams.topMargin = dp(7);
        secondRow.addView(mobile, mobileParams);
        LinearLayout.LayoutParams airplaneParams = new LinearLayout.LayoutParams(0, dp(76), 1f);
        airplaneParams.topMargin = dp(7);
        secondRow.addView(airplane, airplaneParams);
        contentLayout.addView(secondRow);

        TextView settings = makeButton("Pengaturan layar lainnya  ›", false);
        settings.setGravity(Gravity.CENTER_VERTICAL);
        settings.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        settingsParams.topMargin = dp(9);
        contentLayout.addView(settings, settingsParams);
        settings.setOnClickListener(v -> launchOnDisplay(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));

        TextView note = makeText("Kontrol koneksi memerlukan akses Shizuku atau root. "
                + "Kecerahan memerlukan izin Modify system settings.", 11, Color.LTGRAY, false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(7);
        contentLayout.addView(note, noteParams);
    }

    private TextView makeQuickSettingTile(String icon, String title, String state, boolean selected) {
        TextView tile = makeText(icon + "   " + title + "\n" + state, 12, Color.WHITE, true);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(13), dp(7), dp(8), dp(7));
        tile.setBackground(GlassBackgrounds.createGlassSurface(context, 18,
                selected ? Color.rgb(28, 91, 178) : Color.rgb(42, 46, 54)));
        tile.setClickable(true);
        return tile;
    }

    private void buildPowerMenuContent() {
        LinearLayout profile = new LinearLayout(context);
        profile.setGravity(Gravity.CENTER_VERTICAL);
        profile.setOrientation(LinearLayout.HORIZONTAL);
        profile.setPadding(dp(4), dp(4), dp(4), dp(12));

        String name = currentUserName();
        TextView avatar = makeText(name.isEmpty() ? "U" : name.substring(0, 1).toUpperCase(), 17,
                Color.WHITE, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(GlassBackgrounds.createPill(context, true));
        profile.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout userInfo = new LinearLayout(context);
        userInfo.setOrientation(LinearLayout.VERTICAL);
        userInfo.setPadding(dp(10), 0, 0, 0);
        TextView userTitle = makeText(name, 14, Color.WHITE, true);
        TextView userSubtitle = makeText("Saku desktop session", 11, Color.LTGRAY, false);
        userInfo.addView(userTitle);
        userInfo.addView(userSubtitle);
        profile.addView(userInfo, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        contentLayout.addView(profile);

        addPowerAction("↻   Restart", () -> confirmPowerAction("Restart device?",
                "The device will reboot.", "reboot"));
        addPowerAction("⏻   Power off", () -> confirmPowerAction("Power off device?",
                "The device will shut down.", "reboot -p"));
        addPowerAction("▣   Lock screen", () -> {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                U.sendAccessibilityAction(context, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                dismiss();
            } else {
                Toast.makeText(context, "Lock screen action requires Android 9 or later.",
                        Toast.LENGTH_LONG).show();
            }
        });
        addPowerAction("⚙   Taskbar settings", () -> {
            launchOnDisplay(new Intent(context, MainActivity.class));
            dismiss();
        });
    }

    private void addPowerAction(String label, Runnable action) {
        TextView button = makeButton(label, false);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(13), 0, dp(13), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        params.topMargin = dp(5);
        contentLayout.addView(button, params);
        button.setOnClickListener(v -> action.run());
    }

    private void confirmPowerAction(String title, String message, String command) {
        // This panel is not an Activity, so use a second tap as a deliberate confirmation.
        TextView confirm = makeButton("Tap again to confirm: " + title, true);
        confirm.setOnClickListener(v -> executeSystemCommand(command, title));
        contentLayout.removeAllViews();
        TextView titleView = makeText(title, 16, Color.WHITE, true);
        contentLayout.addView(titleView);
        TextView messageView = makeText(message, 12, Color.LTGRAY, false);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(6);
        contentLayout.addView(messageView, messageParams);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        confirmParams.topMargin = dp(10);
        contentLayout.addView(confirm, confirmParams);
        TextView cancel = makeButton("Cancel", false);
        cancel.setOnClickListener(v -> {
            mode = MODE_POWER;
            renderHeaderAndContent();
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        cancelParams.topMargin = dp(4);
        contentLayout.addView(cancel, cancelParams);
    }

    private void executeSystemCommand(String command, String label) {
        Toast.makeText(context, "Requesting " + label + " control…", Toast.LENGTH_SHORT).show();
        COMMAND_EXECUTOR.execute(() -> {
            boolean success = runPrivilegedCommand(command);
            MAIN_HANDLER.post(() -> {
                if(success) {
                    if(command.startsWith("reboot")) {
                        Toast.makeText(context, label + " command sent.", Toast.LENGTH_LONG).show();
                        dismiss();
                    } else {
                        Toast.makeText(context, label + " command sent.", Toast.LENGTH_SHORT).show();
                        if(activePanel == this && mode == MODE_QUICK_SETTINGS) renderContent();
                    }
                } else {
                    Toast.makeText(context,
                            "Command failed. Start Shizuku and grant Saku-Taskbar permission, or enable root.",
                            Toast.LENGTH_LONG).show();
                    if(activePanel == this && mode == MODE_QUICK_SETTINGS) renderContent();
                }
            });
        });
    }

    private boolean runPrivilegedCommand(String command) {
        // Prefer Shizuku shell/root so the user can grant access explicitly.
        try {
            Class<?> shizuku = Class.forName("rikka.shizuku.Shizuku");
            boolean binderAvailable = (Boolean) shizuku.getMethod("pingBinder").invoke(null);
            if(binderAvailable) {
                int permission = (Integer) shizuku.getMethod("checkSelfPermission").invoke(null);
                if(permission == PackageManager.PERMISSION_GRANTED) {
                    Method newProcess = shizuku.getMethod("newProcess",
                            String[].class, String[].class, String.class);
                    Process process = (Process) newProcess.invoke(null,
                            new String[]{"sh", "-c", command}, null, null);
                    if(waitForCommand(process, command)) return true;
                }
                shizuku.getMethod("requestPermission", int.class).invoke(null, SHIZUKU_REQUEST_CODE);
            }
        } catch(Exception ignored) {}

        // Root is a fallback for devices that use Magisk without a running Shizuku server.
        try {
            Process process = new ProcessBuilder("su", "-c", command).start();
            return waitForCommand(process, command);
        } catch(Exception ignored) {
            return false;
        }
    }

    private boolean waitForCommand(Process process, String command) {
        try {
            boolean finished = process.waitFor(1800, TimeUnit.MILLISECONDS);
            if(!finished) {
                // Reboot normally terminates the shell before it can report an exit code.
                return command.startsWith("reboot");
            }
            return process.exitValue() == 0;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean isWifiEnabled() {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            return wifi != null && wifi.isWifiEnabled();
        } catch(SecurityException ignored) {
            return false;
        }
    }

    private boolean isBluetoothEnabled() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            return adapter != null && adapter.isEnabled();
        } catch(SecurityException ignored) {
            return Settings.Global.getInt(context.getContentResolver(), "bluetooth_on", 0) == 1;
        }
    }

    private void sendPendingIntent(PendingIntent pendingIntent) {
        try {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && displayId >= 0) {
                ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    pendingIntent.send(context, 0, null, null, null, null, options.toBundle());
                } else {
                    pendingIntent.send();
                }
            } else {
                pendingIntent.send();
            }
        } catch(PendingIntent.CanceledException | SecurityException | IllegalArgumentException ignored) {}
    }

    private void launchOnDisplay(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && displayId >= 0) {
                BundleHolder.start(context, intent, displayId);
            } else {
                context.startActivity(intent);
            }
        } catch(ActivityNotFoundException | SecurityException | IllegalArgumentException ignored) {
            try { context.startActivity(intent); } catch(Exception ignoredAgain) {}
        }
    }

    private static final class BundleHolder {
        static void start(Context context, Intent intent, int displayId) {
            android.os.Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId).toBundle();
            context.startActivity(intent, options);
        }
    }

    private void registerNotificationReceiver() {
        if(receiverRegistered) return;
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(
                    NotificationCountService.ACTION_NOTIFICATION_OVERLAY_CHANGED);
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(notificationReceiver, filter);
            }
            receiverRegistered = true;
        } catch(Exception ignored) {}
    }

    private void openOverlaySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
            launchOnDisplay(intent);
        } catch(Exception ignored) {}
    }

    private void updatePanelPlacement() {
        if(panelLayout == null || overlayRoot == null) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panelLayout.getLayoutParams();
        if(params == null) {
            params = new FrameLayout.LayoutParams(getPanelWidth(), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        params.width = getPanelWidth();
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        if(mode == MODE_POWER) {
            params.gravity = Gravity.BOTTOM | Gravity.START;
            params.leftMargin = dp(12);
            params.rightMargin = dp(12);
            params.topMargin = dp(12);
            params.bottomMargin = dp(62);
        } else {
            params.gravity = Gravity.TOP | Gravity.END;
            params.leftMargin = dp(12);
            params.rightMargin = dp(12);
            params.topMargin = dp(12);
            params.bottomMargin = dp(12);
        }
        panelLayout.setLayoutParams(params);
    }

    private int getPanelWidth() {
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        return Math.max(dp(260), Math.min(dp(380), screenWidth - dp(24)));
    }

    private int getDisplayHeight() {
        return context.getResources().getDisplayMetrics().heightPixels;
    }

    private String currentUserName() {
        try {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
                if(users != null) {
                    String name = users.getUserName();
                    if(name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch(Exception ignored) {}
        return "User";
    }

    private String getApplicationLabel(String packageName) {
        try {
            return context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch(Exception ignored) {
            return packageName;
        }
    }

    private String modeTitle() {
        if(mode == MODE_QUICK_SETTINGS) return "Quick settings";
        if(mode == MODE_POWER) return "Power";
        return "Notification center";
    }

    private TextView makeText(String label, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if(bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView makeButton(String label, boolean selected) {
        TextView button = makeText(label, 12, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), dp(3), dp(8), dp(3));
        button.setBackground(GlassBackgrounds.createPill(context, selected));
        button.setClickable(true);
        return button;
    }

    private int dp(float value) {
        return GlassBackgrounds.dp(context, value);
    }

    private void dismiss() {
        synchronized(NotificationOverlayPanel.class) {
            if(attached && overlayRoot != null) {
                try { windowManager.removeView(overlayRoot); } catch(Exception ignored) {}
                attached = false;
            }
            if(receiverRegistered) {
                try { context.unregisterReceiver(notificationReceiver); } catch(Exception ignored) {}
                receiverRegistered = false;
            }
            if(activePanel == this) activePanel = null;
        }
    }
}
