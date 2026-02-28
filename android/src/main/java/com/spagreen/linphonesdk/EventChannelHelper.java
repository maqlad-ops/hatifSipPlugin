package com.spagreen.linphonesdk;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;

public class EventChannelHelper {
    private final String TAG;
    public Handler handler;
    // volatile so reads from any thread see the latest write
    private volatile EventChannel.EventSink eventSink;

    public EventChannelHelper(BinaryMessenger messenger, String id) {
        TAG = "EventCh[" + id.replace("linphonesdk/", "") + "]";
        handler = new Handler(Looper.getMainLooper());
        EventChannel eventChannel = new EventChannel(messenger, id);
        eventChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                Log.d(TAG, "*** onListen -> eventSink SET (Flutter is listening)");
                EventChannelHelper.this.eventSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                Log.w(TAG, "*** onCancel -> eventSink CLEARED (Flutter stopped listening)");
                EventChannelHelper.this.eventSink = null;
            }
        });
    }

    /** Returns true if the Flutter side is actively listening */
    public boolean isReady() {
        return eventSink != null;
    }

    public void error(String errorCode, String errorMessage, Object errorDetails) {
        // Capture to local so the lambda uses a stable reference
        final EventChannel.EventSink sink = eventSink;
        if (sink == null) {
            Log.w(TAG, "error() DROPPED (no Flutter listener): code=" + errorCode + " msg=" + errorMessage);
            return;
        }
        handler.post(() -> {
            try {
                sink.error(errorCode, errorMessage, errorDetails);
            } catch (Exception e) {
                Log.e(TAG, "error() delivery failed: " + e.getMessage());
            }
        });
    }

    public void success(String event) {
        // Capture to local so the lambda uses a stable reference
        final EventChannel.EventSink sink = eventSink;
        if (sink == null) {
            Log.w(TAG, "success() DROPPED (no Flutter listener): event=" + event);
            return;
        }
        Log.d(TAG, "success() -> sending event to Flutter: " + event);
        handler.post(() -> {
            try {
                sink.success(event);
            } catch (Exception e) {
                Log.e(TAG, "success() delivery failed: " + e.getMessage());
            }
        });
    }
}
