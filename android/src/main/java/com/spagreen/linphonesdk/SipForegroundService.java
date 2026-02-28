package com.spagreen.linphonesdk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that keeps the SIP connection alive for incoming calls.
 *
 * Without this service Android may kill the process or throttle network access
 * when the app goes to background, which prevents incoming SIP INVITE messages
 * from reaching the Linphone stack.
 *
 * The service shows a low-priority persistent notification ("SIP Connected")
 * and holds a partial wake-lock so the CPU stays alive to process incoming
 * SIP messages even when the screen is off.
 */
public class SipForegroundService extends Service {
    private static final String TAG = "SipForegroundService";
    private static final String CHANNEL_ID = "linphone_sip_channel";
    private static final int NOTIFICATION_ID = 10001;

    private PowerManager.WakeLock wakeLock;

    // ------------------------------------------------------------------
    // Static helpers so LinPhoneHelper can start / stop us easily
    // ------------------------------------------------------------------

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, SipForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Log.d(TAG, "Requested service start");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start SipForegroundService", e);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, SipForegroundService.class));
            Log.d(TAG, "Requested service stop");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop SipForegroundService", e);
        }
    }

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");

        Notification notification = buildNotification();

        // On Android Q+ we can specify a foreground-service type.
        // phoneCall requires FOREGROUND_SERVICE_PHONE_CALL (we have it).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            // Fallback: start without explicit type (still works on most devices)
            Log.w(TAG, "startForeground with type failed, retrying without type", e);
            startForeground(NOTIFICATION_ID, notification);
        }

        // Keep CPU alive so belle-sip can process incoming packets
        acquireWakeLock();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SIP Connection",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Maintains SIP connection for incoming calls");
            channel.setShowBadge(false);
            channel.enableLights(false);
            channel.enableVibration(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SIP Connected")
                .setContentText("Ready to receive calls")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    // ------------------------------------------------------------------
    // Wake-lock
    // ------------------------------------------------------------------

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "LinphoneSDK::SipKeepAlive");
                // 24-hour ceiling – released explicitly in onDestroy()
                wakeLock.acquire(24 * 60 * 60 * 1000L);
                Log.d(TAG, "Wake lock acquired");
            }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
            Log.d(TAG, "Wake lock released");
        }
    }
}
