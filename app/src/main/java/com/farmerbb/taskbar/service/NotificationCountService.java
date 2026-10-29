/* Copyright 2020 Braden Farmer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.farmerbb.taskbar.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;

import com.farmerbb.taskbar.util.U;

import static com.farmerbb.taskbar.util.Constants.*;

public class NotificationCountService extends NotificationListenerService {

    public static final String ACTION_NOTIFICATION_OVERLAY_CHANGED =
            "com.farmerbb.taskbar.action.NOTIFICATION_OVERLAY_CHANGED";

    private static volatile NotificationCountService currentInstance;

    public static boolean isConnected() {
        return currentInstance != null;
    }

    public static StatusBarNotification[] getActiveNotificationsSnapshot() {
        NotificationCountService service = currentInstance;
        if(service == null) return new StatusBarNotification[0];

        try {
            StatusBarNotification[] notifications = service.getActiveNotifications();
            return notifications != null ? notifications : new StatusBarNotification[0];
        } catch(Exception ignored) {
            return new StatusBarNotification[0];
        }
    }

    public static void dismissNotificationByKey(String key) {
        NotificationCountService service = currentInstance;
        if(service == null || key == null) return;

        try {
            service.cancelNotification(key);
        } catch(Exception ignored) {}
    }

    public static void dismissAllNotifications() {
        NotificationCountService service = currentInstance;
        if(service == null) return;

        try {
            service.cancelAllNotifications();
        } catch(Exception ignored) {}
    }

    private final BroadcastReceiver requestCountReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            broadcastNotificationCount();
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        currentInstance = this;
        U.registerReceiver(this, requestCountReceiver, ACTION_REQUEST_NOTIFICATION_COUNT);
        broadcastNotificationCount();
        broadcastOverlayChanged();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if(currentInstance == this) currentInstance = null;
        U.unregisterReceiver(this, requestCountReceiver);
        broadcastNotificationCount(0);
        broadcastOverlayChanged();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        broadcastNotificationCount();
        broadcastOverlayChanged();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        broadcastNotificationCount();
        broadcastOverlayChanged();
    }

    private void broadcastNotificationCount() {
        int count = 0;

        StatusBarNotification[] notifications;
        try {
            notifications = getActiveNotifications();
        } catch (Exception e) {
            notifications = new StatusBarNotification[0];
        }

        for(StatusBarNotification notification : notifications) {
            if(notification != null
                    && (notification.getNotification().flags & NotificationCompat.FLAG_GROUP_SUMMARY) == 0
                    && notification.isClearable()) count++;
        }

        broadcastNotificationCount(count);
    }

    private void broadcastNotificationCount(int count) {
        Intent intent = new Intent(ACTION_NOTIFICATION_COUNT_CHANGED);
        intent.putExtra(EXTRA_COUNT, getValidCount(count));
        U.sendBroadcast(this, intent);
    }

    private void broadcastOverlayChanged() {
        Intent intent = new Intent(ACTION_NOTIFICATION_OVERLAY_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    @VisibleForTesting
    int getValidCount(int count) {
        return Math.min(count, 99);
    }
}
