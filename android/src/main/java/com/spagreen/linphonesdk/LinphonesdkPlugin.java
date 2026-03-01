package com.spagreen.linphonesdk;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;

/**
 * LinphonesdkPlugin
 *
 * Bridges the Flutter engine to the native Linphone SIP service.
 * The LinPhoneHelper singleton is owned by SipForegroundService;
 * this plugin just wires up Flutter event / method channels when
 * the engine is attached.
 */
public class LinphonesdkPlugin implements FlutterPlugin, ActivityAware {
    private static final String TAG = "LinphonesdkPlugin";
    private MethodChannel channel;
    private EventChannelHelper loginEventListener;
    private EventChannelHelper callEventListener;
    private EventChannelHelper callDataListener;
    private Activity activity;
    private BinaryMessenger binaryMessenger;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        binaryMessenger = flutterPluginBinding.getBinaryMessenger();
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (channel != null) {
            channel.setMethodCallHandler(null);
            channel = null;
        }
        this.activity = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        Log.d(TAG, "onAttachedToActivity: " + activity.getClass().getSimpleName());

        Context ctx = activity.getApplicationContext();

        // Get or create the singleton LinPhoneHelper (doesn't start core on its own)
        LinPhoneHelper helper = LinPhoneHelper.getOrCreateInstance(ctx);

        // Wire up Flutter event channels
        loginEventListener = new EventChannelHelper(binaryMessenger, "linphonesdk/login_listener");
        callEventListener = new EventChannelHelper(binaryMessenger, "linphonesdk/call_event_listener");
        callDataListener = new EventChannelHelper(binaryMessenger, "linphonesdk/call_data_listener");
        helper.setLoginListener(loginEventListener);
        helper.setCallEventListener(callEventListener);
        helper.setCallDataListener(callDataListener);

        // Wire up method channel
        channel = new MethodChannel(binaryMessenger, "linphonesdk");
        channel.setMethodCallHandler(new MethodChannelHandler(activity, helper));

        // Service handling: only start if NOT already running
        if (SipForegroundService.getInstance() == null) {
            // Service not running — check if user previously logged in
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("sip_credentials", Context.MODE_PRIVATE);
            if (prefs.getString("username", null) != null) {
                // Credentials exist: service was killed, restart it (auto-login)
                Log.d(TAG, "Service not running but has stored creds — restarting");
                SipForegroundService.start(ctx);
            }
            // else: fresh install — Flutter handles permissions then login
        } else {
            // Service already running (app reopened) — just broadcast current state
            Log.d(TAG, "Service already running — broadcasting current call data");
            helper.broadcastCallData();
        }
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        // Don't null listeners — config changes are transient
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivity() {
        Log.d(TAG, "onDetachedFromActivity");
        // Null out Flutter event channels so the service doesn't try
        // to send events to a dead engine, but do NOT stop the Core.
        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper != null) {
            helper.setLoginListener(null);
            helper.setCallEventListener(null);
            helper.setCallDataListener(null);
        }
        activity = null;
    }
}
