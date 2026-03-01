package com.spagreen.linphonesdk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.linphone.core.TransportType;

/**
 * Persistent foreground service that:
 * 1. Owns the Linphone Core (via LinPhoneHelper singleton)
 * 2. Stores credentials and auto-re-registers on restart
 * 3. Shows a Samsung-style in-call notification with icon buttons
 * (Mute / Speaker / Hang Up) — minimal notification when idle
 * 4. Launches native IncomingCallActivity directly (no notification)
 * 5. Registration watchdog: checks every 3s & refreshes when unregistered
 * 6. Emits JSON call data via LinPhoneHelper → Flutter EventChannel
 */
public class SipForegroundService extends Service {
    private static final String TAG = "SipForegroundService";

    // Notification channels
    private static final String CHANNEL_CALL = "linphone_call_channel";
    private static final String CHANNEL_IDLE = "hatif_service_channel";
    private static final int NOTIF_ID = 10001;
    private static final int NOTIF_IDLE_ID = 10002;
    private boolean serviceRunning = false;

    // Actions
    public static final String ACTION_START_AVAILABLE = "com.spagreen.linphonesdk.START_AVAILABLE";
    public static final String ACTION_START_CALL = "com.spagreen.linphonesdk.START_CALL";
    public static final String ACTION_STOP_CALL = "com.spagreen.linphonesdk.STOP_CALL";
    public static final String ACTION_HANG_UP = "com.spagreen.linphonesdk.HANG_UP";
    public static final String ACTION_ANSWER = "com.spagreen.linphonesdk.ANSWER";
    public static final String ACTION_REJECT = "com.spagreen.linphonesdk.REJECT";
    public static final String ACTION_LOGIN = "com.spagreen.linphonesdk.LOGIN";
    public static final String ACTION_MUTE = "com.spagreen.linphonesdk.MUTE";
    public static final String ACTION_SPEAKER = "com.spagreen.linphonesdk.SPEAKER";
    public static final String ACTION_STOP_SERVICE = "com.spagreen.linphonesdk.STOP_SERVICE";

    // Extras
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_CALLER = "caller";
    public static final String EXTRA_CALLER_NAME = "caller_name";
    public static final String EXTRA_DOMAIN = "domain";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_TRANSPORT = "transport";

    // Credential storage
    private static final String CRED_PREFS = "sip_credentials";
    private static final String CRED_USERNAME = "username";
    private static final String CRED_DOMAIN = "domain";
    private static final String CRED_PASSWORD = "password";
    private static final String CRED_TRANSPORT = "transport";

    private PowerManager.WakeLock wakeLock;
    private String currentUsername = "User";
    private String registrationState = "None";

    // In-call state
    private Handler timerHandler;
    private long callStartTime = 0;
    private boolean isInCall = false;
    private String currentCallState = "";
    private boolean callMuted = false;
    private boolean callOnHold = false;
    private boolean callSpeaker = false;
    private String callRemoteName = "";

    // Timer to refresh notification every second during a call
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isInCall) {
                refreshNotification();
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    // Registration watchdog — checks every 3 seconds
    private Handler regWatchdogHandler;
    private static final long REG_WATCHDOG_INTERVAL = 3000;
    private final Runnable regWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            checkAndRefreshRegistration();
            if (regWatchdogHandler != null) {
                regWatchdogHandler.postDelayed(this, REG_WATCHDOG_INTERVAL);
            }
        }
    };

    // Static reference
    private static SipForegroundService instance;

    public static SipForegroundService getInstance() {
        return instance;
    }

    // ------------------------------------------------------------------
    // Static helpers – called from LinPhoneHelper / MethodChannelHandler
    // ------------------------------------------------------------------

    public static void start(Context context) {
        start(context, null);
    }

    public static void start(Context context, String username) {
        try {
            Intent intent = new Intent(context, SipForegroundService.class);
            intent.setAction(ACTION_START_AVAILABLE);
            if (username != null)
                intent.putExtra(EXTRA_USERNAME, username);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service", e);
        }
    }

    /** Stop the service entirely — user pressed stop */
    public static void stopServiceCompletely(Context context) {
        try {
            Intent intent = new Intent(context, SipForegroundService.class);
            intent.setAction(ACTION_STOP_SERVICE);
            context.startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop service", e);
        }
    }

    /** Store credentials and start service with login action */
    public static void loginViaService(Context context, String username, String domain,
            String password, String transport) {
        try {
            Intent intent = new Intent(context, SipForegroundService.class);
            intent.setAction(ACTION_LOGIN);
            intent.putExtra(EXTRA_USERNAME, username);
            intent.putExtra(EXTRA_DOMAIN, domain);
            intent.putExtra(EXTRA_PASSWORD, password);
            intent.putExtra(EXTRA_TRANSPORT, transport);
            context.startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service with login", e);
        }
    }

    /** Directly launch IncomingCallActivity — NO notification */
    public static void showIncomingCall(Context context, String callerUri, String callerName) {
        try {
            Intent intent = new Intent(context, IncomingCallActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra(IncomingCallActivity.EXTRA_CALLER_NAME,
                    callerName != null ? callerName : callerUri);
            intent.putExtra(IncomingCallActivity.EXTRA_CALLER_URI, callerUri);
            context.startActivity(intent);
            Log.d(TAG, "Launched IncomingCallActivity for: " + callerName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch IncomingCallActivity", e);
        }
    }

    public static void startCallMode(Context context) {
        try {
            Intent intent = new Intent(context, SipForegroundService.class);
            intent.setAction(ACTION_START_CALL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start call mode", e);
        }
    }

    public static void stopCallMode(Context context) {
        try {
            Intent intent = new Intent(context, SipForegroundService.class);
            intent.setAction(ACTION_STOP_CALL);
            context.startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop call mode", e);
        }
    }

    /** Launch native active call screen */
    public static void launchActiveCallScreen(Context context, String callerName, String callerUri) {
        try {
            Intent intent = new Intent(context, ActiveCallActivity.class);
            intent.putExtra(ActiveCallActivity.EXTRA_CALLER_NAME, callerName);
            intent.putExtra(ActiveCallActivity.EXTRA_CALLER_URI, callerUri);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch ActiveCallActivity", e);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, SipForegroundService.class));
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop service", e);
        }
    }

    // ------------------------------------------------------------------
    // State updates from LinPhoneHelper
    // ------------------------------------------------------------------

    /** Update registration state and refresh the idle notification */
    public static void updateRegistrationState(String state) {
        SipForegroundService svc = instance;
        if (svc == null)
            return;
        svc.registrationState = state != null ? state : "None";
        Log.d(TAG, "Registration state: " + svc.registrationState);
        // Refresh idle notification to show current state
        if (svc.serviceRunning && !svc.isInCall) {
            svc.showIdleNotification();
        }
    }

    /** Update call details → refreshes in-call notification */
    public static void updateCallDetails(String callState, String remoteName,
            boolean muted, boolean onHold, boolean speaker) {
        SipForegroundService svc = instance;
        if (svc == null)
            return;
        svc.currentCallState = callState != null ? callState : "";
        svc.callRemoteName = remoteName != null ? remoteName : "";
        svc.callMuted = muted;
        svc.callOnHold = onHold;
        svc.callSpeaker = speaker;
        if (svc.isInCall)
            svc.refreshNotification();
    }

    // ------------------------------------------------------------------
    // Credential storage for auto-login on restart
    // ------------------------------------------------------------------

    private void storeCredentials(String username, String domain, String password, String transport) {
        getSharedPreferences(CRED_PREFS, MODE_PRIVATE).edit()
                .putString(CRED_USERNAME, username)
                .putString(CRED_DOMAIN, domain)
                .putString(CRED_PASSWORD, password)
                .putString(CRED_TRANSPORT, transport)
                .apply();
    }

    private boolean hasStoredCredentials() {
        return getSharedPreferences(CRED_PREFS, MODE_PRIVATE)
                .getString(CRED_USERNAME, null) != null;
    }

    private void autoLogin() {
        // Skip if core is already alive — prevents re-registration
        if (LinPhoneHelper.getCore() != null) {
            Log.d(TAG, "Core already alive, skipping auto-login");
            SharedPreferences prefs = getSharedPreferences(CRED_PREFS, MODE_PRIVATE);
            String un = prefs.getString(CRED_USERNAME, null);
            if (un != null)
                currentUsername = un;
            return;
        }

        SharedPreferences prefs = getSharedPreferences(CRED_PREFS, MODE_PRIVATE);
        String username = prefs.getString(CRED_USERNAME, null);
        String domain = prefs.getString(CRED_DOMAIN, null);
        String password = prefs.getString(CRED_PASSWORD, null);
        String transport = prefs.getString(CRED_TRANSPORT, "tcp");

        if (username == null || domain == null || password == null) {
            Log.d(TAG, "No stored credentials for auto-login");
            return;
        }

        Log.d(TAG, "Auto-login: user=" + username + " domain=" + domain);
        currentUsername = username;
        registrationState = "Registering";

        LinPhoneHelper helper = LinPhoneHelper.getOrCreateInstance(this);
        helper.login(username, domain, password, parseTransportType(transport));
    }

    private void clearCredentials() {
        getSharedPreferences(CRED_PREFS, MODE_PRIVATE).edit().clear().apply();
    }

    private static TransportType parseTransportType(String transport) {
        if (transport == null)
            return TransportType.Tcp;
        switch (transport.toLowerCase()) {
            case "tls":
                return TransportType.Tls;
            case "udp":
                return TransportType.Udp;
            default:
                return TransportType.Tcp;
        }
    }

    // ------------------------------------------------------------------
    // Registration watchdog
    // ------------------------------------------------------------------

    private void startRegWatchdog() {
        if (regWatchdogHandler == null) {
            regWatchdogHandler = new Handler(Looper.getMainLooper());
        }
        regWatchdogHandler.removeCallbacks(regWatchdogRunnable);
        regWatchdogHandler.postDelayed(regWatchdogRunnable, REG_WATCHDOG_INTERVAL);
        Log.d(TAG, "Registration watchdog started (3s interval)");
    }

    private void stopRegWatchdog() {
        if (regWatchdogHandler != null) {
            regWatchdogHandler.removeCallbacks(regWatchdogRunnable);
        }
    }

    private void checkAndRefreshRegistration() {
        // Only refresh if NOT currently registered or in progress
        if ("Ok".equals(registrationState) || "Progress".equals(registrationState)) {
            return;
        }
        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper != null && LinPhoneHelper.getCore() != null) {
            Log.d(TAG, "Watchdog: state=" + registrationState + ", refreshing registration");
            helper.refreshRegistration();
        } else {
            Log.d(TAG, "Watchdog: core dead, waiting for user to start service");
        }
    }

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        timerHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        acquireWakeLock();
        startRegWatchdog();
        Log.d(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (action == null) {
            // System restart (START_STICKY) — do NOT auto-login
            Log.d(TAG, "Service restarted (no action) — skipping auto-login");
            return START_STICKY;
        }

        Log.d(TAG, "onStartCommand action=" + action);

        switch (action) {
            case ACTION_LOGIN: {
                String username = intent.getStringExtra(EXTRA_USERNAME);
                String domain = intent.getStringExtra(EXTRA_DOMAIN);
                String password = intent.getStringExtra(EXTRA_PASSWORD);
                String transport = intent.getStringExtra(EXTRA_TRANSPORT);
                if (username != null)
                    currentUsername = username;
                storeCredentials(username, domain, password, transport);
                registrationState = "Registering";
                LinPhoneHelper loginHelper = LinPhoneHelper.getOrCreateInstance(this);
                loginHelper.login(username, domain, password, parseTransportType(transport));
                break;
            }

            case ACTION_START_AVAILABLE:
                if (intent.hasExtra(EXTRA_USERNAME)) {
                    currentUsername = intent.getStringExtra(EXTRA_USERNAME);
                }
                serviceRunning = true;
                showIdleNotification();
                break;

            case ACTION_STOP_SERVICE: {
                Log.d(TAG, "User requested service stop");
                serviceRunning = false;
                stopInCallTimer();
                // Logout SIP
                LinPhoneHelper lh = LinPhoneHelper.getInstance();
                if (lh != null)
                    lh.logout();
                removeForegroundNotification();
                // Also cancel idle notification
                NotificationManager nmStop = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nmStop != null)
                    nmStop.cancel(NOTIF_IDLE_ID);
                clearCredentials();
                stopSelf();
                break;
            }

            case ACTION_START_CALL:
                isInCall = true;
                // Sync callStartTime with SDK duration for accuracy
                LinPhoneHelper callHelper = LinPhoneHelper.getInstance();
                int callDur = (callHelper != null) ? callHelper.getCallDuration() : 0;
                callStartTime = System.currentTimeMillis() - (callDur * 1000L);
                // Cancel idle notification before switching to call notification
                NotificationManager nmCall = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nmCall != null)
                    nmCall.cancel(NOTIF_IDLE_ID);
                startForegroundSafe(buildCallNotification());
                timerHandler.postDelayed(timerRunnable, 1000);
                break;

            case ACTION_STOP_CALL:
                stopInCallTimer();
                // Switch back to idle notification instead of removing
                if (serviceRunning) {
                    showIdleNotification();
                } else {
                    removeForegroundNotification();
                }
                IncomingCallActivity.finishIfRunning();
                ActiveCallActivity.finishIfRunning();
                break;

            case ACTION_HANG_UP: {
                Log.d(TAG, "Hang up from notification");
                stopInCallTimer();
                LinPhoneHelper h = LinPhoneHelper.getInstance();
                if (h != null)
                    h.hangUp();
                // Switch back to idle notification
                if (serviceRunning) {
                    showIdleNotification();
                } else {
                    removeForegroundNotification();
                }
                ActiveCallActivity.finishIfRunning();
                break;
            }

            case ACTION_ANSWER: {
                Log.d(TAG, "Answer from service");
                LinPhoneHelper ah = LinPhoneHelper.getInstance();
                if (ah != null) {
                    ah.answerCall();
                    launchActiveCallScreen(this, ah.getCurrentCallRemoteName(), "");
                }
                IncomingCallActivity.finishIfRunning();
                break;
            }

            case ACTION_REJECT: {
                Log.d(TAG, "Reject from service");
                LinPhoneHelper rh = LinPhoneHelper.getInstance();
                if (rh != null)
                    rh.rejectCall();
                // Switch back to idle notification
                if (serviceRunning) {
                    showIdleNotification();
                } else {
                    removeForegroundNotification();
                }
                IncomingCallActivity.finishIfRunning();
                break;
            }

            case ACTION_MUTE: {
                Log.d(TAG, "Mute toggle from notification");
                LinPhoneHelper mh = LinPhoneHelper.getInstance();
                if (mh != null)
                    mh.toggleMute();
                // Notification refreshed via updateCallDetails callback
                break;
            }

            case ACTION_SPEAKER: {
                Log.d(TAG, "Speaker toggle from notification");
                LinPhoneHelper sh = LinPhoneHelper.getInstance();
                if (sh != null)
                    sh.toggleSpeaker();
                // Notification refreshed via updateCallDetails callback
                break;
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        instance = null;
        stopInCallTimer();
        stopRegWatchdog();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "Task removed — service continues running");
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null)
                return;

            // Call channel — only used during active calls
            NotificationChannel call = new NotificationChannel(
                    CHANNEL_CALL, "Active Call",
                    NotificationManager.IMPORTANCE_LOW);
            call.setDescription("In-call controls");
            call.setShowBadge(false);
            call.enableLights(false);
            call.enableVibration(false);
            nm.createNotificationChannel(call);

            // Idle / service status channel — low priority, silent
            NotificationChannel idle = new NotificationChannel(
                    CHANNEL_IDLE, "Hatif Service",
                    NotificationManager.IMPORTANCE_LOW);
            idle.setDescription("Shows registration status while Hatif is running");
            idle.setShowBadge(false);
            idle.enableLights(false);
            idle.enableVibration(false);
            nm.createNotificationChannel(idle);

            // =====================================================
            // NUKE all known Linphone SDK notification channels
            // The SDK's CoreService creates its own foreground
            // notification. We removed CoreService from the manifest
            // but the SDK may still try to create channels.
            // Delete them ALL to prevent any keep-alive notification.
            // =====================================================
            nm.deleteNotificationChannel("linphone_idle_channel");
            nm.deleteNotificationChannel("linphone_service_channel");
            nm.deleteNotificationChannel("org.linphone.core.service_notification_channel");
            nm.deleteNotificationChannel("linphone_notification_channel");
            nm.deleteNotificationChannel("Linphone");
            nm.deleteNotificationChannel("linphone_channel");
            nm.deleteNotificationChannel("org_linphone_core_service_channel");
            // Also cancel any stale notifications the SDK might have posted
            // (SDK typically uses IDs 1, 2, 3)
            nm.cancel(1);
            nm.cancel(2);
            nm.cancel(3);
            Log.d(TAG, "Purged all known Linphone SDK notification channels");
        }
    }

    /** Samsung-style in-call notification with icon action buttons */
    private Notification buildCallNotification() {
        // Tap → open ActiveCallActivity (with caller info)
        Intent contentIntent = new Intent(this, ActiveCallActivity.class);
        contentIntent.putExtra(ActiveCallActivity.EXTRA_CALLER_NAME, callRemoteName);
        contentIntent.putExtra(ActiveCallActivity.EXTRA_CALLER_URI, "");
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPending = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // === ACTION: Mute / Unmute ===
        Intent muteIntent = new Intent(this, SipForegroundService.class);
        muteIntent.setAction(ACTION_MUTE);
        PendingIntent mutePending = PendingIntent.getService(this, 201, muteIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // === ACTION: Speaker toggle ===
        Intent speakerIntent = new Intent(this, SipForegroundService.class);
        speakerIntent.setAction(ACTION_SPEAKER);
        PendingIntent speakerPending = PendingIntent.getService(this, 202, speakerIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // === ACTION: Hang Up ===
        Intent hangUpIntent = new Intent(this, SipForegroundService.class);
        hangUpIntent.setAction(ACTION_HANG_UP);
        PendingIntent hangUpPending = PendingIntent.getService(this, 200, hangUpIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Title: caller name or number
        String title = !callRemoteName.isEmpty() ? callRemoteName : "Call";

        // Subtitle: call state only (no mute/speaker text)
        StringBuilder subtitle = new StringBuilder();
        if (!currentCallState.isEmpty())
            subtitle.append(currentCallState);
        if (callOnHold) {
            if (subtitle.length() > 0)
                subtitle.append("  ·  ");
            subtitle.append("\u23F8 On Hold");
        }
        if (subtitle.length() == 0)
            subtitle.append("Ongoing call");

        // Distinct icons: mic vs speaker vs hang-up — instantly recognizable
        int muteIcon = callMuted
                ? R.drawable.ic_notif_mic_off // Mic with slash = muted
                : R.drawable.ic_notif_mic_on; // Mic = unmuted
        String muteLabel = callMuted ? "\uD83D\uDD07 Unmute" : "\uD83C\uDF99 Mute";

        int speakerIcon = callSpeaker
                ? R.drawable.ic_notif_speaker_on // Full volume = speaker on
                : R.drawable.ic_notif_speaker_off; // Low volume = earpiece
        String speakerLabel = callSpeaker ? "\uD83D\uDD08 Earpiece" : "\uD83D\uDD0A Speaker";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_CALL)
                .setSmallIcon(R.drawable.ic_notif_call_active) // Phone-in-talk icon
                .setContentTitle(title)
                .setContentText(subtitle.toString())
                .setSubText("\uD83D\uDCDE Active Call")
                .setOngoing(true) // Sticky — cannot swipe away
                .setAutoCancel(false) // Tap does NOT dismiss
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setUsesChronometer(true)
                .setWhen(callStartTime)
                .setContentIntent(contentPending)
                .setColorized(true)
                .setColor(0xFF1a73e8) // Blue accent
                // 3 distinct icon action buttons — each visually unique
                .addAction(new NotificationCompat.Action.Builder(
                        muteIcon, muteLabel, mutePending).build())
                .addAction(new NotificationCompat.Action.Builder(
                        speakerIcon, speakerLabel, speakerPending).build())
                .addAction(new NotificationCompat.Action.Builder(
                        R.drawable.ic_notif_call_end,
                        "\u260E End", hangUpPending).build())
                // Media style shows all 3 actions in compact notification
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0, 1, 2));

        Notification notification = builder.build();
        // Belt-and-suspenders: set raw flags so Android absolutely cannot dismiss it
        notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR
                | Notification.FLAG_FOREGROUND_SERVICE;
        return notification;
    }

    /** Refresh the in-call notification (no-op when idle) */
    private void refreshNotification() {
        if (isInCall) {
            startForegroundSafe(buildCallNotification());
        }
    }

    /** Build and show the idle/registration status notification */
    private void showIdleNotification() {
        String contentText;
        if ("Ok".equalsIgnoreCase(registrationState)) {
            String name = (currentUsername != null && !currentUsername.isEmpty())
                    ? currentUsername
                    : "User";
            contentText = name + " Avail";
        } else if ("Progress".equalsIgnoreCase(registrationState)
                || "None".equalsIgnoreCase(registrationState)) {
            contentText = "Connecting...";
        } else if ("Failed".equalsIgnoreCase(registrationState)
                || "Cleared".equalsIgnoreCase(registrationState)) {
            contentText = "Connecting...";
        } else {
            contentText = "Connecting...";
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_IDLE)
                .setSmallIcon(R.drawable.ic_hatif_logo)
                .setContentTitle("Hatif")
                .setContentText(contentText)
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        Notification notification = builder.build();
        notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR
                | Notification.FLAG_FOREGROUND_SERVICE;

        startForegroundIdleSafe(notification);
    }

    /** Start foreground with idle notification (uses NOTIF_IDLE_ID) */
    private void startForegroundIdleSafe(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_IDLE_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            } else {
                startForeground(NOTIF_IDLE_ID, notification);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground idle failed, retrying", e);
            try {
                startForeground(NOTIF_IDLE_ID, notification);
            } catch (Exception e2) {
                Log.e(TAG, "startForeground idle completely failed", e2);
            }
        }
    }

    /** Remove the foreground notification and demote to background service */
    private void removeForegroundNotification() {
        try {
            stopForeground(true);
            // Also explicitly cancel both notification IDs
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIF_ID);
                nm.cancel(NOTIF_IDLE_ID);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error removing foreground notification", e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void startForegroundSafe(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground with type failed, retrying", e);
            try {
                startForeground(NOTIF_ID, notification);
            } catch (Exception e2) {
                Log.e(TAG, "startForeground completely failed", e2);
            }
        }
    }

    private void stopInCallTimer() {
        isInCall = false;
        callStartTime = 0;
        callRemoteName = "";
        currentCallState = "";
        callMuted = false;
        callOnHold = false;
        callSpeaker = false;
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "LinphoneSDK::SipKeepAlive");
                wakeLock.acquire(24 * 60 * 60 * 1000L);
            }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }
}
