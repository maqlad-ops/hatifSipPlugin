package com.spagreen.linphonesdk;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.linphone.core.Call;

/**
 * Native Android active-call screen.
 *
 * Launched when a call is connected (or for outgoing calls from the start).
 * Works even when the Flutter app is terminated. All UI is built
 * programmatically — no XML layouts are required.
 *
 * Features:
 * - Dark gradient background
 * - Caller avatar + name
 * - Chronometer timer showing call duration (persists across open/close)
 * - Action grid: Mute, Speaker, Keypad (+ 3 placeholder buttons)
 * - Toggle-able DTMF keypad overlay
 * - Red hang-up button
 * - Polls call status and syncs mute/speaker state from LinPhoneHelper
 */
public class ActiveCallActivity extends Activity {
    private static final String TAG = "ActiveCallActivity";

    public static final String EXTRA_CALLER_NAME = "caller_name";
    public static final String EXTRA_CALLER_URI = "caller_uri";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Chronometer chronometer;
    private boolean isMuted = false;
    private boolean isSpeaker = false;
    private LinearLayout dtmfPad;
    private boolean dtmfVisible = false;

    // Action button references for toggling backgrounds
    private FrameLayout muteCircle, speakerCircle, keypadCircle;
    private TextView muteLabel, speakerLabel;
    private TextView nameView;

    private static ActiveCallActivity currentInstance;

    /** Dismiss the active call screen if it is showing */
    public static void finishIfRunning() {
        if (currentInstance != null && !currentInstance.isFinishing()) {
            currentInstance.finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setTheme(android.R.style.Theme_Material_NoActionBar_Fullscreen);
        } else {
            setTheme(android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        }
        super.onCreate(savedInstanceState);
        currentInstance = this;
        setupWindowFlags();

        // Resolve caller name: prefer intent extra, fall back to live call data
        String callerName = resolveCallerName(getIntent());
        String callerUri = getIntent().getStringExtra(EXTRA_CALLER_URI);
        if (callerUri == null)
            callerUri = "";

        setContentView(buildLayout(callerName, callerUri));

        // Sync chronometer base to actual call duration (survives activity re-creation)
        syncChronometerToCall();

        // Sync mute/speaker from LinPhoneHelper
        syncToggleStates();

        startTimerAndPoll();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Refresh caller name if a new intent arrives (e.g. tapping notification)
        String callerName = resolveCallerName(intent);
        if (nameView != null && callerName != null) {
            nameView.setText(callerName);
        }
        syncChronometerToCall();
        syncToggleStates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncChronometerToCall();
        syncToggleStates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (currentInstance == this)
            currentInstance = null;
    }

    @Override
    public void onBackPressed() {
        // Toggle DTMF pad off if visible, otherwise ignore
        if (dtmfVisible)
            toggleDtmfPad();
    }

    // ==================================================================
    // Resolve caller name from intent or live call
    // ==================================================================

    private String resolveCallerName(Intent intent) {
        String name = intent != null ? intent.getStringExtra(EXTRA_CALLER_NAME) : null;
        if (!TextUtils.isEmpty(name) && !"Unknown".equals(name))
            return name;

        // Fall back to live call data from LinPhoneHelper
        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper != null) {
            String liveName = helper.getCurrentCallRemoteName();
            if (!TextUtils.isEmpty(liveName))
                return liveName;
        }
        return name != null ? name : "Unknown";
    }

    // ==================================================================
    // Sync chronometer to actual call duration
    // ==================================================================

    private void syncChronometerToCall() {
        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper != null && chronometer != null) {
            int durationSec = helper.getCallDuration();
            chronometer.setBase(SystemClock.elapsedRealtime() - (durationSec * 1000L));
            chronometer.start();
        }
    }

    // ==================================================================
    // Sync mute / speaker state from LinPhoneHelper
    // ==================================================================

    private void syncToggleStates() {
        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper == null)
            return;

        boolean actualMuted = helper.isMuted();
        boolean actualSpeaker = helper.isSpeakerEnabled();

        if (actualMuted != isMuted) {
            isMuted = actualMuted;
            updateMuteUI();
        }
        if (actualSpeaker != isSpeaker) {
            isSpeaker = actualSpeaker;
            updateSpeakerUI();
        }
    }

    private void updateMuteUI() {
        if (muteCircle == null)
            return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(isMuted ? 0xFFFFFFFF : 0x33FFFFFF);
        muteCircle.setBackground(bg);
        if (muteCircle.getChildAt(0) instanceof ImageView) {
            ((ImageView) muteCircle.getChildAt(0))
                    .setColorFilter(isMuted ? 0xFF1a1a2e : Color.WHITE);
        }
    }

    private void updateSpeakerUI() {
        if (speakerCircle == null)
            return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(isSpeaker ? 0xFFFFFFFF : 0x33FFFFFF);
        speakerCircle.setBackground(bg);
        if (speakerCircle.getChildAt(0) instanceof ImageView) {
            ((ImageView) speakerCircle.getChildAt(0))
                    .setColorFilter(isSpeaker ? 0xFF1a1a2e : Color.WHITE);
        }
    }

    // ==================================================================
    // Window flags
    // ==================================================================

    private void setupWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

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
    // UI Layout
    // ==================================================================

    private View buildLayout(final String callerName, final String callerUri) {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF1a1a2e, 0xFF16213e, 0xFF0f3460, 0xFF1a1a2e });
        root.setBackground(bg);

        // Main content
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), 0, dp(24), 0);
        root.addView(content, matchWrap());

        addSpacer(content, 70);

        // Avatar
        int avatarSize = dp(90);
        FrameLayout avatar = new FrameLayout(this);
        GradientDrawable avatarBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF4facfe, 0xFF00f2fe });
        avatarBg.setShape(GradientDrawable.OVAL);
        avatar.setBackground(avatarBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            avatar.setElevation(dp(6));

        TextView initials = new TextView(this);
        initials.setText(getInitials(callerName));
        initials.setTextColor(Color.WHITE);
        initials.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        initials.setTypeface(Typeface.DEFAULT_BOLD);
        initials.setGravity(Gravity.CENTER);
        avatar.addView(initials, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams avatarP = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarP.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(avatar, avatarP);

        addSpacer(content, 20);

        // Caller name
        nameView = new TextView(this);
        nameView.setText(callerName);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        nameView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nameView.setGravity(Gravity.CENTER);
        content.addView(nameView, wrapCenter());

        // Caller URI
        if (!callerUri.isEmpty()) {
            addSpacer(content, 4);
            TextView uriView = new TextView(this);
            uriView.setText(callerUri);
            uriView.setTextColor(0x80FFFFFF);
            uriView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            uriView.setGravity(Gravity.CENTER);
            content.addView(uriView, wrapCenter());
        }

        addSpacer(content, 8);

        // Timer (base set later in syncChronometerToCall)
        chronometer = new Chronometer(this);
        chronometer.setTextColor(0xB3FFFFFF);
        chronometer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        chronometer.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        chronometer.setGravity(Gravity.CENTER);
        chronometer.setFormat(null);
        content.addView(chronometer, wrapCenter());

        // Flex spacer
        View flex = new View(this);
        LinearLayout.LayoutParams flexP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        flexP.weight = 1;
        content.addView(flex, flexP);

        // Action grid (2 rows x 3 cols)
        content.addView(buildActionGrid(), wrapCenter());

        addSpacer(content, 30);

        // Hang up button
        FrameLayout hangUp = new FrameLayout(this);
        GradientDrawable hangBg = new GradientDrawable();
        hangBg.setShape(GradientDrawable.OVAL);
        hangBg.setColor(0xFFe53935);
        hangUp.setBackground(hangBg);

        ImageView hangIcon = new ImageView(this);
        hangIcon.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        hangIcon.setColorFilter(Color.WHITE);
        hangIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int hPad = dp(18);
        hangIcon.setPadding(hPad, hPad, hPad, hPad);
        hangUp.addView(hangIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hangUp.setOnClickListener(v -> doHangUp());
        LinearLayout.LayoutParams hangP = new LinearLayout.LayoutParams(dp(72), dp(72));
        hangP.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(hangUp, hangP);

        addSpacer(content, 10);
        TextView hangLabel = new TextView(this);
        hangLabel.setText("Hang Up");
        hangLabel.setTextColor(0xB3FFFFFF);
        hangLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hangLabel.setGravity(Gravity.CENTER);
        content.addView(hangLabel, wrapCenter());

        addSpacer(content, 50);

        // DTMF pad overlay (initially hidden)
        dtmfPad = buildDtmfPad();
        dtmfPad.setVisibility(View.GONE);
        root.addView(dtmfPad, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return root;
    }

    // ==================================================================
    // Action grid
    // ==================================================================

    private LinearLayout buildActionGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER);

        // Row 1: Mute, Keypad, Speaker
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);

        View[] muteViews = buildActionButton(
                android.R.drawable.ic_lock_silent_mode,
                "Mute", () -> toggleMute());
        muteCircle = (FrameLayout) muteViews[0];
        muteLabel = ((TextView) ((LinearLayout) muteViews[1]).getChildAt(2));
        row1.addView((View) muteViews[1],
                new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));

        View[] kpViews = buildActionButton(
                android.R.drawable.ic_input_get,
                "Keypad", this::toggleDtmfPad);
        keypadCircle = (FrameLayout) kpViews[0];
        row1.addView((View) kpViews[1],
                new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));

        View[] spkViews = buildActionButton(
                android.R.drawable.ic_lock_silent_mode_off,
                "Speaker", this::toggleSpeaker);
        speakerCircle = (FrameLayout) spkViews[0];
        speakerLabel = ((TextView) ((LinearLayout) spkViews[1]).getChildAt(2));
        row1.addView((View) spkViews[1],
                new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));

        grid.addView(row1, wrapCenter());
        addSpacer(grid, 16);

        // Row 2: Add call, Video, Contacts (all disabled)
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);

        row2.addView(buildDisabledButton("Add call"),
                new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));
        row2.addView(buildDisabledButton("Video"),
                new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));
        row2.addView(buildDisabledButton("Contacts"),
                new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));

        grid.addView(row2, wrapCenter());
        return grid;
    }

    /**
     * Returns [0] = FrameLayout circle, [1] = LinearLayout column
     */
    private View[] buildActionButton(int iconRes, String label, Runnable action) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout circle = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x33FFFFFF);
        circle.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = dp(14);
        icon.setPadding(p, p, p, p);
        circle.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        circle.setOnClickListener(v -> action.run());
        col.addView(circle, new LinearLayout.LayoutParams(dp(58), dp(58)));

        addSpacer(col, 6);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xB3FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setGravity(Gravity.CENTER);
        col.addView(tv, wrapCenter());

        return new View[] { circle, col };
    }

    private View buildDisabledButton(String label) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout circle = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x1AFFFFFF);
        circle.setBackground(bg);
        col.addView(circle, new LinearLayout.LayoutParams(dp(58), dp(58)));

        addSpacer(col, 6);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0x4DFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setGravity(Gravity.CENTER);
        col.addView(tv, wrapCenter());

        return col;
    }

    // ==================================================================
    // DTMF Pad
    // ==================================================================

    private LinearLayout buildDtmfPad() {
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xEE1a1a2e);
        overlay.setClickable(true); // consume touches

        String[][] keys = {
                { "1", "2", "3" },
                { "4", "5", "6" },
                { "7", "8", "9" },
                { "*", "0", "#" }
        };

        for (String[] row : keys) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER);

            for (String key : row) {
                TextView btn = new TextView(this);
                btn.setText(key);
                btn.setTextColor(Color.WHITE);
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
                btn.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                btn.setGravity(Gravity.CENTER);

                GradientDrawable btnBg = new GradientDrawable();
                btnBg.setShape(GradientDrawable.OVAL);
                btnBg.setColor(0x33FFFFFF);
                btn.setBackground(btnBg);

                btn.setOnClickListener(v -> sendDtmf(key));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(72), dp(72));
                bp.setMargins(dp(12), dp(8), dp(12), dp(8));
                rowLayout.addView(btn, bp);
            }
            overlay.addView(rowLayout, wrapCenter());
        }

        addSpacer(overlay, 20);

        // Close button
        TextView close = new TextView(this);
        close.setText("Close");
        close.setTextColor(0xFF4facfe);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> toggleDtmfPad());
        overlay.addView(close, wrapCenter());

        return overlay;
    }

    private void sendDtmf(String key) {
        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper != null && key != null && !key.isEmpty())
            helper.sendDtmf(key.charAt(0));
    }

    private void toggleDtmfPad() {
        dtmfVisible = !dtmfVisible;
        dtmfPad.setVisibility(dtmfVisible ? View.VISIBLE : View.GONE);
    }

    // ==================================================================
    // Toggle actions
    // ==================================================================

    private void toggleMute() {
        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper == null)
            return;
        helper.toggleMute();
        isMuted = helper.isMuted();
        updateMuteUI();
    }

    private void toggleSpeaker() {
        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper == null)
            return;
        helper.toggleSpeaker();
        // Optimistic UI: flip immediately
        isSpeaker = !isSpeaker;
        updateSpeakerUI();
        // Then verify actual state after the audio route change settles
        if (handler != null) {
            handler.postDelayed(() -> {
                LinPhoneHelper h = LinPhoneHelper.getInstance();
                if (h != null) {
                    boolean actual = h.isSpeakerEnabled();
                    if (actual != isSpeaker) {
                        isSpeaker = actual;
                        updateSpeakerUI();
                    }
                }
            }, 300);
        }
    }

    // ==================================================================
    // Hang up
    // ==================================================================

    private void doHangUp() {
        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper != null)
            helper.hangUp();
        finish();
    }

    // ==================================================================
    // Timer & call state polling
    // ==================================================================

    private void startTimerAndPoll() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing())
                    return;
                LinPhoneHelper helper = LinPhoneHelper.getInstance();
                if (helper == null || !helper.hasActiveCall()) {
                    finish();
                    return;
                }
                // Sync mute/speaker state from the source of truth (LinPhoneHelper)
                syncToggleStates();
                handler.postDelayed(this, 1500);
            }
        }, 1500);
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

    private FrameLayout.LayoutParams matchWrap() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
