package com.spagreen.linphonesdk;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Native Android incoming call screen.
 *
 * Launched via full-screen intent from the notification – works even when
 * the Flutter app is terminated. Plays the device ringtone and vibrates
 * exactly like a normal phone call.
 *
 * Features:
 * - Dark gradient background with animated pulsing rings
 * - Caller avatar with blue glow
 * - Accept (green) / Decline (red) buttons
 * - Ringtone + vibration
 * - Shows over lock screen and turns screen on
 */
public class IncomingCallActivity extends Activity {
    private static final String TAG = "IncomingCallActivity";

    public static final String EXTRA_CALLER_NAME = "caller_name";
    public static final String EXTRA_CALLER_URI = "caller_uri";

    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    private AnimatorSet pulseAnimator;
    private boolean isAnswered = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Static instance so we can dismiss it from outside
    private static IncomingCallActivity currentInstance;

    /** Dismiss the incoming call screen if it is showing */
    public static void finishIfRunning() {
        if (currentInstance != null && !currentInstance.isFinishing()) {
            currentInstance.stopRinging();
            currentInstance.stopVibrating();
            currentInstance.finish();
        }
    }

    // Pulse ring views (stored for animation)
    private View ring0, ring1, ring2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply full-screen theme BEFORE super
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setTheme(android.R.style.Theme_Material_NoActionBar_Fullscreen);
        } else {
            setTheme(android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        }
        super.onCreate(savedInstanceState);
        currentInstance = this;

        setupWindowFlags();

        String callerName = getIntent().getStringExtra(EXTRA_CALLER_NAME);
        String callerUri = getIntent().getStringExtra(EXTRA_CALLER_URI);
        if (callerName == null || callerName.isEmpty())
            callerName = "Unknown";
        if (callerUri == null)
            callerUri = "";

        setContentView(buildLayout(callerName, callerUri));

        startRinging();
        startVibrating();
        startPulseAnimation();
        checkCallStillActive();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRinging();
        stopVibrating();
        stopPulseAnimation();
        handler.removeCallbacksAndMessages(null);
        if (currentInstance == this)
            currentInstance = null;
    }

    @Override
    public void onBackPressed() {
        // Block back button – user must Accept or Decline
    }

    // ==================================================================
    // Window flags – show over lock screen, turn screen on
    // ==================================================================

    private void setupWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null)
                km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Immersive sticky
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
    }

    // ==================================================================
    // UI – built entirely programmatically (no XML needed)
    // ==================================================================

    private View buildLayout(final String callerName, final String callerUri) {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Dark gradient background
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF1a1a2e, 0xFF16213e, 0xFF0f3460, 0xFF1a1a2e });
        root.setBackground(bg);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), 0, dp(24), 0);
        root.addView(content, matchParent());

        // Top spacer
        addSpacer(content, 80);

        // "Incoming Call" label
        TextView label = new TextView(this);
        label.setText("Incoming Call");
        label.setTextColor(0x99FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            label.setLetterSpacing(0.15f);
        content.addView(label, wrapCenter());

        addSpacer(content, 50);

        // Avatar with pulsing rings
        FrameLayout avatarArea = buildAvatarArea(callerName);
        LinearLayout.LayoutParams aParams = new LinearLayout.LayoutParams(dp(200), dp(200));
        aParams.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(avatarArea, aParams);

        addSpacer(content, 35);

        // Caller name
        TextView nameView = new TextView(this);
        nameView.setText(callerName);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        nameView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nameView.setGravity(Gravity.CENTER);
        content.addView(nameView, wrapCenter());

        // Caller URI
        if (!callerUri.isEmpty()) {
            addSpacer(content, 8);
            TextView uriView = new TextView(this);
            uriView.setText(callerUri);
            uriView.setTextColor(0x80FFFFFF);
            uriView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            uriView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            uriView.setGravity(Gravity.CENTER);
            content.addView(uriView, wrapCenter());
        }

        // Flex spacer
        View flex = new View(this);
        LinearLayout.LayoutParams flexP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        flexP.weight = 1;
        content.addView(flex, flexP);

        // Buttons row
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        buttons.addView(buildCallButton(0xFFe53935,
                android.R.drawable.ic_menu_close_clear_cancel,
                "Decline", this::declineCall));

        View spacer = new View(this);
        buttons.addView(spacer, new LinearLayout.LayoutParams(dp(80), dp(1)));

        buttons.addView(buildCallButton(0xFF4caf50,
                android.R.drawable.ic_menu_call,
                "Accept", () -> acceptCall(callerName, callerUri)));

        content.addView(buttons, wrapCenter());
        addSpacer(content, 70);

        return root;
    }

    private FrameLayout buildAvatarArea(String name) {
        FrameLayout container = new FrameLayout(this);

        // Three pulsing rings
        ring0 = makeRing(dp(200));
        ring1 = makeRing(dp(175));
        ring2 = makeRing(dp(150));

        FrameLayout.LayoutParams ringP0 = centered(dp(200));
        FrameLayout.LayoutParams ringP1 = centered(dp(175));
        FrameLayout.LayoutParams ringP2 = centered(dp(150));
        container.addView(ring0, ringP0);
        container.addView(ring1, ringP1);
        container.addView(ring2, ringP2);

        // Avatar circle
        int avatarSize = dp(110);
        FrameLayout avatar = new FrameLayout(this);
        GradientDrawable avatarBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF4facfe, 0xFF00f2fe });
        avatarBg.setShape(GradientDrawable.OVAL);
        avatar.setBackground(avatarBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            avatar.setElevation(dp(8));

        TextView initials = new TextView(this);
        initials.setText(getInitials(name));
        initials.setTextColor(Color.WHITE);
        initials.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        initials.setTypeface(Typeface.DEFAULT_BOLD);
        initials.setGravity(Gravity.CENTER);
        avatar.addView(initials, matchParent());

        container.addView(avatar, centered(avatarSize));
        return container;
    }

    private View makeRing(int size) {
        View v = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setStroke(dp(2), 0x264facfe);
        d.setColor(Color.TRANSPARENT);
        v.setBackground(d);
        return v;
    }

    private View buildCallButton(int color, int iconRes, String label, Runnable action) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout circle = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        circle.setBackground(bg);
        circle.setClickable(true);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(18);
        icon.setPadding(pad, pad, pad, pad);
        circle.addView(icon, matchParent());

        circle.setOnClickListener(v -> action.run());
        col.addView(circle, new LinearLayout.LayoutParams(dp(72), dp(72)));

        addSpacer(col, 10);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xB3FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setGravity(Gravity.CENTER);
        col.addView(tv, wrapCenter());

        return col;
    }

    // ==================================================================
    // Call actions
    // ==================================================================

    private void acceptCall(String callerName, String callerUri) {
        if (isAnswered)
            return;
        isAnswered = true;
        stopRinging();
        stopVibrating();

        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper != null)
            helper.answerCall();

        // Launch native active call screen
        Intent intent = new Intent(this, ActiveCallActivity.class);
        intent.putExtra(ActiveCallActivity.EXTRA_CALLER_NAME, callerName);
        intent.putExtra(ActiveCallActivity.EXTRA_CALLER_URI, callerUri);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private void declineCall() {
        stopRinging();
        stopVibrating();
        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper != null)
            helper.rejectCall();
        finish();
    }

    // ==================================================================
    // Ringtone
    // ==================================================================

    private void startRinging() {
        try {
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (ringtoneUri == null)
                ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, ringtoneUri);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                mediaPlayer.setAudioAttributes(attrs);
            } else {
                mediaPlayer.setAudioStreamType(AudioManager.STREAM_RING);
            }
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
            Log.d(TAG, "Ringtone started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to play ringtone", e);
        }
    }

    private void stopRinging() {
        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying())
                    mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping ringtone", e);
        }
    }

    // ==================================================================
    // Vibration
    // ==================================================================

    private void startVibrating() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                if (vm != null)
                    vibrator = vm.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = { 0, 1000, 1000 }; // on 1s, off 1s
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start vibration", e);
        }
    }

    private void stopVibrating() {
        try {
            if (vibrator != null) {
                vibrator.cancel();
                vibrator = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping vibration", e);
        }
    }

    // ==================================================================
    // Pulse animation
    // ==================================================================

    private void startPulseAnimation() {
        if (ring0 == null)
            return;

        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                makePulse(ring0, 0),
                makePulse(ring1, 300),
                makePulse(ring2, 600));
        set.start();
        pulseAnimator = set;
    }

    private AnimatorSet makePulse(View v, long startDelay) {
        ObjectAnimator sx = ObjectAnimator.ofFloat(v, "scaleX", 0.85f, 1.25f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(v, "scaleY", 0.85f, 1.25f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(v, "alpha", 1f, 0f);

        AnimatorSet s = new AnimatorSet();
        s.playTogether(sx, sy, alpha);
        s.setDuration(2000);
        s.setStartDelay(startDelay);
        s.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        s.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!isFinishing())
                    s.start();
            }
        });
        return s;
    }

    private void stopPulseAnimation() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }

    // ==================================================================
    // Periodic check – auto-dismiss if call is no longer ringing
    // ==================================================================

    private void checkCallStillActive() {
        handler.postDelayed(() -> {
            if (isFinishing() || isAnswered)
                return;
            LinPhoneHelper helper = LinPhoneHelper.getInstance();
            if (helper == null || !helper.hasActiveCall()) {
                stopRinging();
                stopVibrating();
                finish();
            } else {
                checkCallStillActive();
            }
        }, 2000);
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private String getInitials(String name) {
        if (name == null || name.isEmpty())
            return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length >= 2)
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
        return name.substring(0, 1).toUpperCase();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void addSpacer(LinearLayout parent, int heightDp) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
    }

    private LinearLayout.LayoutParams wrapCenter() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        return p;
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private FrameLayout.LayoutParams centered(int size) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(size, size);
        p.gravity = Gravity.CENTER;
        return p;
    }
}
