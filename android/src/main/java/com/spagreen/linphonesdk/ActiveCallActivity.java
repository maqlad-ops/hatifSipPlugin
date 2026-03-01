package com.spagreen.linphonesdk;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.linphone.core.Call;

import java.util.List;

/**
 * Native Android active-call screen — creative, modern design.
 *
 * Launched when a call is connected (or for outgoing calls).
 * Works even when the Flutter app is terminated. All UI is built
 * programmatically — no XML layouts required.
 *
 * Features:
 * - Deep dark gradient background
 * - Caller avatar with animated glow ring (blue → orange when on hold)
 * - Chronometer (persists across re-creation)
 * - Action grid: Mute, Speaker, Keypad, Hold — with proper Material icons
 * - Toggle-able DTMF keypad overlay
 * - Wide pill-shaped red hang-up button with phone-end icon
 * - Real-time state sync (mute / speaker / hold)
 * - "On Hold" pulsing banner
 */
public class ActiveCallActivity extends Activity {
    private static final String TAG = "ActiveCallActivity";

    public static final String EXTRA_CALLER_NAME = "caller_name";
    public static final String EXTRA_CALLER_URI = "caller_uri";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Chronometer chronometer;
    private boolean isMuted = false;
    private boolean isSpeaker = false;
    private boolean isOnHold = false;
    private boolean callConnected = false;
    private LinearLayout dtmfPad;
    private boolean dtmfVisible = false;

    // Action-button references
    private FrameLayout muteCircle, speakerCircle, keypadCircle, holdCircle, transferCircle, conferenceCircle;
    private ImageView muteIcon, speakerIcon, holdIcon;
    private TextView muteLabel, speakerLabel, holdLabel;
    private TextView nameView, statusView;
    private View holdBanner;
    private FrameLayout avatarGlowRing;

    // Transfer dialog overlay
    private FrameLayout transferOverlay;

    // Conference dialog overlay
    private FrameLayout conferenceOverlay;
    private boolean conferenceVisible = false;
    private boolean conferenceActive = false;
    private LinearLayout confParticipantList;
    private TextView confStatusText;
    private LinearLayout confActionContainer;

    private static ActiveCallActivity currentInstance;

    /** Dismiss the active call screen if it is showing. */
    public static void finishIfRunning() {
        if (currentInstance != null && !currentInstance.isFinishing()) {
            currentInstance.finish();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────

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

        String callerName = resolveCallerName(getIntent());
        String callerUri = getIntent().getStringExtra(EXTRA_CALLER_URI);
        if (callerUri == null)
            callerUri = "";

        setContentView(buildLayout(callerName, callerUri));
        syncChronometerToCall();
        syncToggleStates();
        startTimerAndPoll();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String callerName = resolveCallerName(intent);
        if (nameView != null && callerName != null)
            nameView.setText(callerName);
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
        if (conferenceVisible)
            hideConferenceDialog();
        else if (transferVisible)
            hideTransferDialog();
        else if (dtmfVisible)
            toggleDtmfPad();
    }

    // ──────────────────────────────────────────────────────────────────
    // Resolve caller name
    // ──────────────────────────────────────────────────────────────────

    private String resolveCallerName(Intent intent) {
        String name = intent != null ? intent.getStringExtra(EXTRA_CALLER_NAME) : null;
        if (!TextUtils.isEmpty(name) && !"Unknown".equals(name))
            return name;
        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper != null) {
            String live = helper.getCurrentCallRemoteName();
            if (!TextUtils.isEmpty(live))
                return live;
        }
        return name != null ? name : "Unknown";
    }

    // ──────────────────────────────────────────────────────────────────
    // Sync chronometer to real call duration
    // Only starts once the call is actually connected/streaming.
    // ──────────────────────────────────────────────────────────────────

    private void syncChronometerToCall() {
        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper == null || chronometer == null)
            return;

        if (!callConnected) {
            // Check if call has moved past ringing into connected state
            if (helper.isCallConnected() || helper.getCallDuration() > 0) {
                callConnected = true;
                int sec = helper.getCallDuration();
                chronometer.setBase(SystemClock.elapsedRealtime() - (sec * 1000L));
                chronometer.start();
                chronometer.setVisibility(View.VISIBLE);
                // Hide the pre-connection status once connected
                if (statusView != null && !isOnHold) {
                    statusView.setVisibility(View.GONE);
                }
            } else {
                // Still ringing/dialing — show state label, don't tick
                chronometer.stop();
                chronometer.setText("00:00");
                String label = helper.getCallStateLabel();
                if (statusView != null && !label.isEmpty()) {
                    statusView.setText(label);
                    statusView.setTextColor(0xFF4facfe);
                    statusView.setVisibility(View.VISIBLE);
                }
            }
        } else {
            // Already connected — keep syncing the base for accuracy
            int sec = helper.getCallDuration();
            chronometer.setBase(SystemClock.elapsedRealtime() - (sec * 1000L));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Sync mute / speaker / hold
    // ──────────────────────────────────────────────────────────────────

    private void syncToggleStates() {
        LinPhoneHelper helper = LinPhoneHelper.getInstance();
        if (helper == null)
            return;

        boolean m = helper.isMuted();
        boolean s = helper.isSpeakerEnabled();
        boolean h = helper.isCallOnHold();

        if (m != isMuted) {
            isMuted = m;
            applyCircleToggle(muteCircle, muteIcon, isMuted);
            if (muteIcon != null)
                muteIcon.setImageResource(res(isMuted ? "ic_mic_off" : "ic_mic_on"));
            if (muteLabel != null)
                muteLabel.setText(isMuted ? "Unmute" : "Mute");
        }
        if (s != isSpeaker) {
            isSpeaker = s;
            applyCircleToggle(speakerCircle, speakerIcon, isSpeaker);
            if (speakerIcon != null)
                speakerIcon.setImageResource(res(isSpeaker ? "ic_hearing" : "ic_volume_up"));
            if (speakerLabel != null)
                speakerLabel.setText(isSpeaker ? "Earpiece" : "Speaker");
        }
        if (h != isOnHold) {
            isOnHold = h;
            applyCircleToggle(holdCircle, holdIcon, isOnHold);
            refreshHoldVisuals();
        }
    }

    /** White circle = active, translucent circle = inactive. */
    private void applyCircleToggle(FrameLayout circle, ImageView icon, boolean active) {
        if (circle == null)
            return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(active ? 0xFFFFFFFF : 0x33FFFFFF);
        circle.setBackground(bg);
        if (icon != null)
            icon.setColorFilter(active ? 0xFF1a1a2e : Color.WHITE);
    }

    /** Update hold icon/label/banner/glow colour. */
    private void refreshHoldVisuals() {
        if (holdIcon != null)
            holdIcon.setImageResource(res(isOnHold ? "ic_play_arrow" : "ic_pause"));
        if (holdLabel != null)
            holdLabel.setText(isOnHold ? "Resume" : "Hold");
        if (holdBanner != null)
            holdBanner.setVisibility(isOnHold ? View.VISIBLE : View.GONE);
        if (statusView != null) {
            statusView.setText(isOnHold ? "On Hold" : "");
            statusView.setVisibility(isOnHold ? View.VISIBLE : View.GONE);
        }
        if (avatarGlowRing != null) {
            GradientDrawable glow = new GradientDrawable();
            glow.setShape(GradientDrawable.OVAL);
            glow.setStroke(dp(3), isOnHold ? 0xFFFF9800 : 0xFF4facfe);
            glow.setColor(Color.TRANSPARENT);
            avatarGlowRing.setBackground(glow);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Window flags
    // ──────────────────────────────────────────────────────────────────

    private void setupWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // BUILD LAYOUT
    // ══════════════════════════════════════════════════════════════════

    private View buildLayout(String callerName, String callerUri) {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0xFF0d0d1a, 0xFF141428, 0xFF1a1a3e, 0xFF0f1f3a }));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), 0, dp(24), 0);
        root.addView(col, matchWrap());

        spacer(col, 60);

        // ── Avatar + glow ring ───────────────────────────────────────
        col.addView(buildAvatarArea(callerName), centeredLP(dp(115), dp(115)));

        spacer(col, 20);

        // ── Caller name ──────────────────────────────────────────────
        nameView = txt(callerName, Color.WHITE, 28, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(nameView, wrapCenter());

        // ── URI ──────────────────────────────────────────────────────
        if (!callerUri.isEmpty()) {
            spacer(col, 4);
            col.addView(txt(callerUri, 0x66FFFFFF, 14, null), wrapCenter());
        }

        spacer(col, 6);

        // ── "On Hold" status ─────────────────────────────────────────
        statusView = txt("", 0xFFFF9800, 15, Typeface.DEFAULT_BOLD);
        statusView.setVisibility(View.GONE);
        col.addView(statusView, wrapCenter());

        spacer(col, 4);

        // ── Timer ────────────────────────────────────────────────────
        chronometer = new Chronometer(this);
        chronometer.setTextColor(0x99FFFFFF);
        chronometer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        chronometer.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        chronometer.setGravity(Gravity.CENTER);
        col.addView(chronometer, wrapCenter());

        // ── Flex ─────────────────────────────────────────────────────
        View flex = new View(this);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
        fp.weight = 1;
        col.addView(flex, fp);

        // ── Hold banner ──────────────────────────────────────────────
        holdBanner = buildHoldBanner();
        col.addView(holdBanner, wrapCenter());
        spacer(col, 14);

        // ── Action grid ──────────────────────────────────────────────
        col.addView(buildActionGrid(), wrapCenter());

        spacer(col, 34);

        // ── Hang-up pill ─────────────────────────────────────────────
        col.addView(buildHangUpPill(), wrapCenter());

        spacer(col, 50);

        // ── DTMF overlay ─────────────────────────────────────────────
        dtmfPad = buildDtmfPad();
        dtmfPad.setVisibility(View.GONE);
        root.addView(dtmfPad, matchWrap());

        // ── Transfer dialog overlay ──────────────────────────────────
        transferOverlay = buildTransferOverlay();
        transferOverlay.setVisibility(View.GONE);
        root.addView(transferOverlay, matchWrap());

        // ── Conference dialog overlay ────────────────────────────────
        conferenceOverlay = buildConferenceOverlay();
        conferenceOverlay.setVisibility(View.GONE);
        root.addView(conferenceOverlay, matchWrap());

        return root;
    }

    // ──────────────────────────────────────────────────────────────────
    // Avatar area
    // ──────────────────────────────────────────────────────────────────

    private FrameLayout buildAvatarArea(String callerName) {
        FrameLayout area = new FrameLayout(this);

        // Glow ring
        avatarGlowRing = new FrameLayout(this);
        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.OVAL);
        glow.setStroke(dp(3), 0xFF4facfe);
        glow.setColor(Color.TRANSPARENT);
        avatarGlowRing.setBackground(glow);
        area.addView(avatarGlowRing, centered(dp(115)));

        // Pulse animation on the glow ring
        ObjectAnimator pulse = ObjectAnimator.ofFloat(avatarGlowRing, "alpha", 1f, 0.35f, 1f);
        pulse.setDuration(2400);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.start();

        // Inner avatar
        int sz = dp(92);
        FrameLayout av = new FrameLayout(this);
        GradientDrawable avBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF4facfe, 0xFF00f2fe });
        avBg.setShape(GradientDrawable.OVAL);
        av.setBackground(avBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            av.setElevation(dp(8));

        TextView init = txt(getInitials(callerName), Color.WHITE, 36, Typeface.DEFAULT_BOLD);
        init.setGravity(Gravity.CENTER);
        av.addView(init, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        area.addView(av, centered(sz));
        return area;
    }

    // ──────────────────────────────────────────────────────────────────
    // Hold banner
    // ──────────────────────────────────────────────────────────────────

    private View buildHoldBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(0x33FF9800);
        bg.setStroke(dp(1), 0x66FF9800);
        b.setBackground(bg);

        ImageView ic = new ImageView(this);
        ic.setImageResource(res("ic_pause"));
        ic.setColorFilter(0xFFFF9800);
        b.addView(ic, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView t = txt("  Call On Hold", 0xFFFF9800, 14, Typeface.DEFAULT_BOLD);
        b.addView(t, wrapCenter());

        ObjectAnimator a = ObjectAnimator.ofFloat(b, "alpha", 1f, 0.45f, 1f);
        a.setDuration(1800);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.start();

        b.setVisibility(View.GONE);
        return b;
    }

    // ──────────────────────────────────────────────────────────────────
    // Action grid (2 × 2)
    // ──────────────────────────────────────────────────────────────────

    private LinearLayout buildActionGrid() {
        LinearLayout grid = vBox(Gravity.CENTER);

        // Row 1: Mute · Speaker · Keypad
        LinearLayout r1 = hBox(Gravity.CENTER);

        View[] mv = actionBtn(res("ic_mic_on"), "Mute", this::toggleMute);
        muteCircle = (FrameLayout) mv[0];
        muteIcon = (ImageView) muteCircle.getChildAt(0);
        muteLabel = (TextView) ((LinearLayout) mv[1]).getChildAt(2);
        r1.addView(mv[1], new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));

        hSpacer(r1, 12);

        View[] sv = actionBtn(res("ic_volume_up"), "Speaker", this::toggleSpeaker);
        speakerCircle = (FrameLayout) sv[0];
        speakerIcon = (ImageView) speakerCircle.getChildAt(0);
        speakerLabel = (TextView) ((LinearLayout) sv[1]).getChildAt(2);
        r1.addView(sv[1], new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));

        hSpacer(r1, 12);

        View[] kv = actionBtn(res("ic_dialpad"), "Keypad", this::toggleDtmfPad);
        keypadCircle = (FrameLayout) kv[0];
        r1.addView(kv[1], new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));

        grid.addView(r1, wrapCenter());
        spacer(grid, 16);

        // Row 2: Hold · Transfer · Conference
        LinearLayout r2 = hBox(Gravity.CENTER);

        View[] hv = actionBtn(res("ic_pause"), "Hold", this::toggleHold);
        holdCircle = (FrameLayout) hv[0];
        holdIcon = (ImageView) holdCircle.getChildAt(0);
        holdLabel = (TextView) ((LinearLayout) hv[1]).getChildAt(2);
        r2.addView(hv[1], new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));

        hSpacer(r2, 12);

        View[] tv = actionBtn(res("ic_call_transfer"), "Transfer", this::showTransferDialog);
        transferCircle = (FrameLayout) tv[0];
        r2.addView(tv[1], new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));

        hSpacer(r2, 12);

        View[] cv = actionBtn(res("ic_group_call"), "Merge", this::showConferenceDialog);
        conferenceCircle = (FrameLayout) cv[0];
        r2.addView(cv[1], new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));

        grid.addView(r2, wrapCenter());
        return grid;
    }

    /** Builds a circular action button; returns [0]=circle, [1]=column. */
    private View[] actionBtn(int iconRes, String label, Runnable action) {
        LinearLayout col = vBox(Gravity.CENTER_HORIZONTAL);

        int size = dp(62);
        FrameLayout circle = new FrameLayout(this);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setShape(GradientDrawable.OVAL);
        cbg.setColor(0x33FFFFFF);
        circle.setBackground(cbg);

        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setColorFilter(Color.WHITE);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = dp(16);
        iv.setPadding(p, p, p, p);
        circle.addView(iv, matchWrap());

        circle.setOnClickListener(v -> {
            v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()).start();
            action.run();
        });

        col.addView(circle, new LinearLayout.LayoutParams(size, size));
        spacer(col, 6);
        col.addView(txt(label, 0xB3FFFFFF, 12, null), wrapCenter());

        return new View[] { circle, col };
    }

    // ──────────────────────────────────────────────────────────────────
    // Hang-up circular button
    // ──────────────────────────────────────────────────────────────────

    private View buildHangUpPill() {
        int size = dp(72);
        FrameLayout circle = new FrameLayout(this);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColors(new int[] { 0xFFe53935, 0xFFc62828 });
        bg.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        bg.setOrientation(GradientDrawable.Orientation.TL_BR);
        circle.setBackground(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            circle.setElevation(dp(8));

        ImageView ic = new ImageView(this);
        ic.setImageResource(res("ic_call_end"));
        ic.setColorFilter(Color.WHITE);
        ic.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = dp(20);
        ic.setPadding(p, p, p, p);
        circle.addView(ic, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        circle.setOnClickListener(v -> v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100)
                .withEndAction(() -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                    doHangUp();
                }).start());

        // Wrap in a container so centeredLP works with LinearLayout
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.CENTER);
        wrap.addView(circle, new LinearLayout.LayoutParams(size, size));
        return wrap;
    }

    // ──────────────────────────────────────────────────────────────────
    // DTMF pad
    // ──────────────────────────────────────────────────────────────────

    private LinearLayout buildDtmfPad() {
        LinearLayout ov = vBox(Gravity.CENTER);
        ov.setBackgroundColor(0xF01a1a2e);
        ov.setClickable(true);

        String[][] keys = { { "1", "2", "3" }, { "4", "5", "6" }, { "7", "8", "9" }, { "*", "0", "#" } };
        for (String[] row : keys) {
            LinearLayout rl = hBox(Gravity.CENTER);
            for (String k : row) {
                TextView btn = txt(k, Color.WHITE, 30, Typeface.create("sans-serif-light", Typeface.NORMAL));
                btn.setGravity(Gravity.CENTER);
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(0x2AFFFFFF);
                btn.setBackground(d);
                btn.setOnClickListener(v -> {
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(60)
                            .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(60).start()).start();
                    sendDtmf(k);
                });
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(72), dp(72));
                bp.setMargins(dp(10), dp(7), dp(10), dp(7));
                rl.addView(btn, bp);
            }
            ov.addView(rl, wrapCenter());
        }

        spacer(ov, 24);

        // Close button
        TextView close = txt("Close Keypad", 0xFF4facfe, 17, Typeface.DEFAULT_BOLD);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(24), dp(10), dp(24), dp(10));
        GradientDrawable cb = new GradientDrawable();
        cb.setCornerRadius(dp(20));
        cb.setStroke(dp(1), 0xFF4facfe);
        cb.setColor(0x1A4facfe);
        close.setBackground(cb);
        close.setOnClickListener(v -> toggleDtmfPad());
        ov.addView(close, wrapCenter());

        return ov;
    }

    private void sendDtmf(String key) {
        LinPhoneHelper h = LinPhoneHelper.getInstance();
        if (h != null && key != null && !key.isEmpty())
            h.sendDtmf(key.charAt(0));
    }

    private void toggleDtmfPad() {
        dtmfVisible = !dtmfVisible;
        dtmfPad.setVisibility(dtmfVisible ? View.VISIBLE : View.GONE);
    }

    // ──────────────────────────────────────────────────────────────────
    // Transfer Dialog — Creative Tabbed UI
    // ──────────────────────────────────────────────────────────────────

    private boolean transferVisible = false;
    private int selectedTransferTab = 0; // 0 = Blind, 1 = Attended
    private boolean consultationInProgress = false;
    private LinearLayout completeBtnRef; // held reference to enable/disable

    private void showTransferDialog() {
        transferVisible = true;
        if (transferOverlay != null) {
            transferOverlay.setVisibility(View.VISIBLE);
            transferOverlay.setAlpha(0f);
            transferOverlay.animate().alpha(1f).setDuration(200).start();
        }
    }

    private void hideTransferDialog() {
        transferVisible = false;
        consultationInProgress = false;
        if (transferOverlay != null) {
            transferOverlay.animate().alpha(0f).setDuration(150)
                    .withEndAction(() -> transferOverlay.setVisibility(View.GONE)).start();
        }
    }

    private FrameLayout buildTransferOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xF01a1a2e);
        overlay.setClickable(true); // consume touch

        LinearLayout container = vBox(Gravity.CENTER);
        container.setPadding(dp(28), dp(60), dp(28), dp(40));

        // ── Header ───────────────────────────────────────────────────
        LinearLayout header = hBox(Gravity.CENTER_VERTICAL);

        ImageView transferIcon = new ImageView(this);
        transferIcon.setImageResource(res("ic_call_transfer"));
        transferIcon.setColorFilter(0xFF4facfe);
        header.addView(transferIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));

        hSpacer(header, 10);

        TextView title = txt("Transfer Call", Color.WHITE, 24, Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.addView(title, wrapCenter());

        container.addView(header, wrapCenter());
        spacer(container, 8);

        TextView subtitle = txt("Choose transfer type", 0x99FFFFFF, 14, null);
        container.addView(subtitle, wrapCenter());
        spacer(container, 24);

        // ── Tab bar ──────────────────────────────────────────────────
        LinearLayout tabBar = hBox(Gravity.CENTER);
        tabBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable tabBarBg = new GradientDrawable();
        tabBarBg.setCornerRadius(dp(16));
        tabBarBg.setColor(0x1AFFFFFF);
        tabBar.setBackground(tabBarBg);

        // Content panels (we build both, show one at a time)
        LinearLayout blindPanel = buildBlindTransferPanel();
        LinearLayout attendedPanel = buildAttendedTransferPanel();
        attendedPanel.setVisibility(View.GONE);

        // Tab buttons
        TextView blindTab = buildTabButton("Blind", true);
        TextView attendedTab = buildTabButton("Attended", false);

        blindTab.setOnClickListener(v -> {
            if (selectedTransferTab == 0)
                return;
            selectedTransferTab = 0;
            applyTabStyle(blindTab, true);
            applyTabStyle(attendedTab, false);
            blindPanel.setVisibility(View.VISIBLE);
            attendedPanel.setVisibility(View.GONE);
        });
        attendedTab.setOnClickListener(v -> {
            if (selectedTransferTab == 1)
                return;
            selectedTransferTab = 1;
            applyTabStyle(attendedTab, true);
            applyTabStyle(blindTab, false);
            attendedPanel.setVisibility(View.VISIBLE);
            blindPanel.setVisibility(View.GONE);
        });

        LinearLayout.LayoutParams tabLP = new LinearLayout.LayoutParams(0, dp(42));
        tabLP.weight = 1;
        tabLP.setMargins(dp(2), 0, dp(2), 0);
        tabBar.addView(blindTab, tabLP);
        tabBar.addView(attendedTab, new LinearLayout.LayoutParams(tabLP));

        container.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spacer(container, 24);

        // ── Panels ───────────────────────────────────────────────────
        container.addView(blindPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(attendedPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── Flex spacer ──────────────────────────────────────────────
        View flex = new View(this);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        fp.weight = 1;
        container.addView(flex, fp);

        // ── Close button ─────────────────────────────────────────────
        TextView close = txt("Cancel", 0xFF4facfe, 16, Typeface.DEFAULT_BOLD);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(32), dp(12), dp(32), dp(12));
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setCornerRadius(dp(24));
        closeBg.setStroke(dp(1), 0xFF4facfe);
        closeBg.setColor(0x1A4facfe);
        close.setBackground(closeBg);
        close.setOnClickListener(v -> hideTransferDialog());
        container.addView(close, wrapCenter());

        overlay.addView(container, matchWrap());
        return overlay;
    }

    private TextView buildTabButton(String label, boolean active) {
        TextView tab = txt(label, Color.WHITE, 15, Typeface.DEFAULT_BOLD);
        tab.setGravity(Gravity.CENTER);
        applyTabStyle(tab, active);
        return tab;
    }

    private void applyTabStyle(TextView tab, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (active) {
            bg.setColor(0xFF4facfe);
            tab.setTextColor(0xFF0d0d1a);
        } else {
            bg.setColor(Color.TRANSPARENT);
            tab.setTextColor(0x99FFFFFF);
        }
        tab.setBackground(bg);
    }

    // ── Blind Transfer Panel ─────────────────────────────────────────

    private LinearLayout buildBlindTransferPanel() {
        LinearLayout panel = vBox(Gravity.CENTER_HORIZONTAL);

        // Info card
        LinearLayout info = buildInfoCard(
                "Blind Transfer",
                "Transfer the call immediately without speaking to the recipient first. " +
                        "The caller will be connected directly to the destination.",
                0xFF4facfe);
        panel.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spacer(panel, 20);

        // Phone input
        EditText input = buildPhoneInput("Enter destination number");
        panel.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spacer(panel, 20);

        // Transfer button
        LinearLayout btn = buildActionButton("Transfer Now", 0xFF4facfe, 0xFF00c6ff, () -> {
            String dest = input.getText().toString().trim();
            if (dest.isEmpty()) {
                input.setHintTextColor(0xFFe53935);
                input.setHint("Please enter a number!");
                handler.postDelayed(() -> {
                    input.setHintTextColor(0x66FFFFFF);
                    input.setHint("Enter destination number");
                }, 2000);
                return;
            }
            LinPhoneHelper h = LinPhoneHelper.getInstance();
            if (h != null) {
                boolean ok = h.blindTransfer(dest);
                if (ok) {
                    hideTransferDialog();
                }
            }
        });
        panel.addView(btn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        return panel;
    }

    // ── Attended Transfer Panel ──────────────────────────────────────

    private LinearLayout buildAttendedTransferPanel() {
        LinearLayout panel = vBox(Gravity.CENTER_HORIZONTAL);

        // Info card
        LinearLayout info = buildInfoCard(
                "Attended Transfer",
                "First speak with the recipient, then transfer the call. " +
                        "The original caller is placed on hold while you consult.",
                0xFFFF9800);
        panel.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spacer(panel, 20);

        // Phone input
        EditText input = buildPhoneInput("Enter consult number");
        panel.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spacer(panel, 16);

        // Status label (hidden until consultation starts)
        TextView consultStatus = txt("", 0xFFFF9800, 14, Typeface.DEFAULT_BOLD);
        consultStatus.setVisibility(View.GONE);
        panel.addView(consultStatus, wrapCenter());
        spacer(panel, 16);

        // Action buttons container — swaps between "Start Consult" and "Complete /
        // Cancel"
        LinearLayout btnContainer = vBox(Gravity.CENTER_HORIZONTAL);

        // Start consultation button
        LinearLayout startBtn = buildActionButton("Start Consultation", 0xFFFF9800, 0xFFffc107, () -> {
            String dest = input.getText().toString().trim();
            if (dest.isEmpty()) {
                input.setHintTextColor(0xFFe53935);
                input.setHint("Please enter a number!");
                handler.postDelayed(() -> {
                    input.setHintTextColor(0x66FFFFFF);
                    input.setHint("Enter consult number");
                }, 2000);
                return;
            }
            LinPhoneHelper h = LinPhoneHelper.getInstance();
            if (h != null) {
                boolean ok = h.attendedTransfer(dest);
                if (ok) {
                    consultationInProgress = true;
                    consultStatus.setText("Consulting " + dest + "…");
                    consultStatus.setVisibility(View.VISIBLE);
                    input.setEnabled(false);
                    // Rebuild button area
                    rebuildConsultButtons(btnContainer, consultStatus, input);
                }
            }
        });
        btnContainer.addView(startBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        panel.addView(btnContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return panel;
    }

    private void rebuildConsultButtons(LinearLayout container, TextView status, EditText input) {
        container.removeAllViews();

        // Complete Transfer button (green) — DISABLED until consult call connects
        LinearLayout completeBtn = buildActionButton("Waiting for answer…", 0xFF555555, 0xFF444444, () -> {
            // Only allow if consult is connected
            LinPhoneHelper h = LinPhoneHelper.getInstance();
            if (h != null && h.isConsultCallConnected()) {
                boolean ok = h.completeAttendedTransfer();
                if (ok) {
                    hideTransferDialog();
                }
            }
        });
        completeBtn.setAlpha(0.4f);
        completeBtn.setEnabled(false);
        completeBtnRef = completeBtn;
        container.addView(completeBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        spacer(container, 10);

        // Cancel & Resume button (red) — always enabled
        LinearLayout cancelBtn = buildActionButton("Cancel & Resume", 0xFFe53935, 0xFFb71c1c, () -> {
            LinPhoneHelper h = LinPhoneHelper.getInstance();
            if (h != null) {
                h.cancelAttendedTransfer();
            }
            consultationInProgress = false;
            completeBtnRef = null;
            hideTransferDialog();
        });
        container.addView(cancelBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    }

    // ══════════════════════════════════════════════════════════════════
    //  Conference dialog overlay
    // ══════════════════════════════════════════════════════════════════

    private void showConferenceDialog() {
        conferenceVisible = true;
        refreshConferenceUI();
        if (conferenceOverlay != null) {
            conferenceOverlay.setAlpha(0f);
            conferenceOverlay.setVisibility(View.VISIBLE);
            conferenceOverlay.animate().alpha(1f).setDuration(200).start();
        }
    }

    private void hideConferenceDialog() {
        conferenceVisible = false;
        if (conferenceOverlay != null) {
            conferenceOverlay.animate().alpha(0f).setDuration(150)
                    .withEndAction(() -> conferenceOverlay.setVisibility(View.GONE)).start();
        }
    }

    private FrameLayout buildConferenceOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xF01a1a2e);
        overlay.setClickable(true);

        LinearLayout container = vBox(Gravity.CENTER);
        container.setPadding(dp(28), dp(40), dp(28), dp(28));

        // ── Header ──
        LinearLayout header = hBox(Gravity.CENTER_VERTICAL);

        // Icon
        ImageView confIcon = new ImageView(this);
        confIcon.setImageResource(res("ic_group_call"));
        confIcon.setColorFilter(0xFF4facfe);
        header.addView(confIcon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        hSpacer(header, 12);

        // Title
        TextView title = txt("Conference Call", Color.WHITE, 24, Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Close
        TextView close = txt("✕", 0xFF4facfe, 22, Typeface.DEFAULT_BOLD);
        close.setPadding(dp(12), dp(4), dp(4), dp(4));
        close.setOnClickListener(v -> hideConferenceDialog());
        header.addView(close, wrapCenter());

        container.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spacer(container, 8);

        // Subtitle
        TextView subtitle = txt("Merge calls into a group conversation", 0x99FFFFFF, 14, null);
        container.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spacer(container, 20);

        // ── Status text ──
        confStatusText = txt("", 0xFFffc107, 14, Typeface.DEFAULT_BOLD);
        confStatusText.setGravity(Gravity.CENTER);
        confStatusText.setVisibility(View.GONE);
        container.addView(confStatusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spacer(container, 12);

        // ── Info card ──
        container.addView(buildInfoCard(
                "How it works",
                "Merge your current calls into one conference. " +
                        "You can also add new participants by dialing them.",
                0xFF4facfe), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spacer(container, 16);

        // ── Participant list ──
        confParticipantList = vBox(Gravity.START);
        container.addView(confParticipantList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spacer(container, 16);

        // ── Add participant input ──
        EditText addInput = buildPhoneInput("Add participant (SIP / number)");
        container.addView(addInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        spacer(container, 12);

        // ── Action buttons container ──
        confActionContainer = vBox(Gravity.CENTER);
        container.addView(confActionContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Build the initial buttons
        buildConferenceActions(addInput);

        // Scroll wrapper
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        overlay.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return overlay;
    }

    private void buildConferenceActions(EditText addInput) {
        confActionContainer.removeAllViews();

        LinPhoneHelper h = LinPhoneHelper.getInstance();
        boolean inConf = h != null && h.isInConference();
        int callCount = h != null ? h.getCallCount() : 0;

        if (inConf) {
            // ── Conference is active ──

            // Add participant button
            LinearLayout addBtn = buildActionButton("Add Participant", 0xFF4facfe, 0xFF00f2fe, () -> {
                String dest = addInput.getText().toString().trim();
                if (dest.isEmpty()) return;
                LinPhoneHelper h2 = LinPhoneHelper.getInstance();
                if (h2 != null) {
                    boolean ok = h2.addToConference(dest);
                    if (ok) {
                        addInput.setText("");
                        // After the new call connects, merge it
                        handler.postDelayed(() -> {
                            LinPhoneHelper h3 = LinPhoneHelper.getInstance();
                            if (h3 != null) h3.mergeCallsToConference();
                            refreshConferenceUI();
                        }, 3000);
                    }
                }
                refreshConferenceUI();
            });
            confActionContainer.addView(addBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
            spacer(confActionContainer, 10);

            // End conference button
            LinearLayout endBtn = buildActionButton("End Conference", 0xFFe53935, 0xFFb71c1c, () -> {
                LinPhoneHelper h2 = LinPhoneHelper.getInstance();
                if (h2 != null) h2.endConference();
                conferenceActive = false;
                hideConferenceDialog();
            });
            confActionContainer.addView(endBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        } else if (callCount >= 2) {
            // ── 2+ calls exist, offer merge ──

            LinearLayout mergeBtn = buildActionButton("Merge All Calls", 0xFF4caf50, 0xFF2e7d32, () -> {
                LinPhoneHelper h2 = LinPhoneHelper.getInstance();
                if (h2 != null) {
                    boolean ok = h2.startConference();
                    if (ok) {
                        conferenceActive = true;
                        confStatusText.setText("Conference active — " + h2.getConferenceParticipantCount() + " participants");
                        confStatusText.setVisibility(View.VISIBLE);
                    }
                }
                refreshConferenceUI();
            });
            confActionContainer.addView(mergeBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
            spacer(confActionContainer, 10);

            // Also allow adding another participant
            LinearLayout addBtn = buildActionButton("Add Participant", 0xFF4facfe, 0xFF00f2fe, () -> {
                String dest = addInput.getText().toString().trim();
                if (dest.isEmpty()) return;
                LinPhoneHelper h2 = LinPhoneHelper.getInstance();
                if (h2 != null) {
                    h2.addToConference(dest);
                    addInput.setText("");
                }
                refreshConferenceUI();
            });
            confActionContainer.addView(addBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        } else {
            // ── Single call — need to add someone first ──

            LinearLayout callBtn = buildActionButton("Call & Add to Conference", 0xFFFF9800, 0xFFffc107, () -> {
                String dest = addInput.getText().toString().trim();
                if (dest.isEmpty()) return;
                LinPhoneHelper h2 = LinPhoneHelper.getInstance();
                if (h2 != null) {
                    boolean ok = h2.addToConference(dest);
                    if (ok) {
                        addInput.setText("");
                        confStatusText.setText("Calling " + dest + "…");
                        confStatusText.setVisibility(View.VISIBLE);
                    }
                }
                // Refresh after a delay to pick up the new call
                handler.postDelayed(this::refreshConferenceUI, 2000);
            });
            confActionContainer.addView(callBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        }
    }

    private void refreshConferenceUI() {
        if (confParticipantList == null) return;
        confParticipantList.removeAllViews();

        LinPhoneHelper h = LinPhoneHelper.getInstance();
        if (h == null) return;

        boolean inConf = h.isInConference();
        int callCount = h.getCallCount();

        if (inConf) {
            conferenceActive = true;
            List<String> participants = h.getConferenceParticipants();
            int total = h.getConferenceParticipantCount();

            confStatusText.setText("Conference active — " + total + " participants");
            confStatusText.setTextColor(0xFF4caf50);
            confStatusText.setVisibility(View.VISIBLE);

            // "You" entry
            confParticipantList.addView(buildParticipantRow("You (local)", true), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            spacer(confParticipantList, 6);

            for (String p : participants) {
                confParticipantList.addView(buildParticipantRow(p, false), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                spacer(confParticipantList, 6);
            }
        } else if (callCount >= 2) {
            confStatusText.setText("Multiple calls detected — ready to merge");
            confStatusText.setTextColor(0xFFffc107);
            confStatusText.setVisibility(View.VISIBLE);

            // Show current calls
            for (Call c : h.getCalls()) {
                String name = "";
                if (c.getRemoteAddress() != null) {
                    name = c.getRemoteAddress().getDisplayName();
                    if (name == null || name.isEmpty()) name = c.getRemoteAddress().getUsername();
                }
                String stateLabel = c.getState().name();
                confParticipantList.addView(buildParticipantRow(name + " (" + stateLabel + ")", false),
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                spacer(confParticipantList, 6);
            }
        } else {
            conferenceActive = false;
            confStatusText.setText("Add a participant to start a conference");
            confStatusText.setTextColor(0x99FFFFFF);
            confStatusText.setVisibility(View.VISIBLE);
        }

        // Rebuild action buttons
        // Find the addInput — it's the EditText 2 views before confActionContainer in parent
        ViewGroup parent = (ViewGroup) confActionContainer.getParent();
        EditText addInput = null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof EditText) {
                addInput = (EditText) parent.getChildAt(i);
            }
        }
        if (addInput != null) buildConferenceActions(addInput);
    }

    private LinearLayout buildParticipantRow(String name, boolean isLocal) {
        LinearLayout row = hBox(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(isLocal ? 0x1A4facfe : 0x0DFFFFFF);
        bg.setStroke(dp(1), isLocal ? 0x334facfe : 0x1AFFFFFF);
        row.setBackground(bg);

        // Avatar circle
        FrameLayout avCircle = new FrameLayout(this);
        GradientDrawable avBg = new GradientDrawable();
        avBg.setShape(GradientDrawable.OVAL);
        avBg.setColor(isLocal ? 0xFF4facfe : 0xFF7c4dff);
        avCircle.setBackground(avBg);

        String initials = "";
        if (name != null && !name.isEmpty()) {
            initials = name.substring(0, 1).toUpperCase();
        }
        TextView initView = txt(initials, Color.WHITE, 14, Typeface.DEFAULT_BOLD);
        initView.setGravity(Gravity.CENTER);
        avCircle.addView(initView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(avCircle, new LinearLayout.LayoutParams(dp(36), dp(36)));

        hSpacer(row, 12);

        // Name
        TextView nameText = txt(name != null ? name : "Unknown", Color.WHITE, 15,
                isLocal ? Typeface.DEFAULT_BOLD : null);
        row.addView(nameText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Connected indicator
        if (isLocal) {
            TextView badge = txt("HOST", 0xFF4facfe, 10, Typeface.DEFAULT_BOLD);
            badge.setPadding(dp(8), dp(2), dp(8), dp(2));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dp(8));
            badgeBg.setColor(0x1A4facfe);
            badge.setBackground(badgeBg);
            row.addView(badge, wrapCenter());
        }

        return row;
    }

    // ── Shared dialog helpers ────────────────────────────────────────

    private LinearLayout buildInfoCard(String titleText, String descText, int accentColor) {
        LinearLayout card = vBox(Gravity.START);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0x0DFFFFFF);
        bg.setStroke(dp(1), accentColor & 0x33FFFFFF | (accentColor & 0x00FFFFFF));
        card.setBackground(bg);

        // Accent bar + title row
        LinearLayout row = hBox(Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setCornerRadius(dp(2));
        barBg.setColor(accentColor);
        bar.setBackground(barBg);
        row.addView(bar, new LinearLayout.LayoutParams(dp(3), dp(18)));
        hSpacer(row, 8);
        row.addView(txt(titleText, accentColor, 16, Typeface.DEFAULT_BOLD), wrapCenter());
        card.addView(row, wrapCenter());

        spacer(card, 8);

        TextView desc = txt(descText, 0x99FFFFFF, 13, null);
        desc.setGravity(Gravity.START);
        desc.setLineSpacing(dp(2), 1f);
        card.addView(desc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return card;
    }

    private EditText buildPhoneInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(0x66FFFFFF);
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        input.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        input.setGravity(Gravity.CENTER);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        input.setPadding(dp(20), dp(14), dp(20), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0x12FFFFFF);
        bg.setStroke(dp(1), 0x33FFFFFF);
        input.setBackground(bg);

        // Focus highlight
        input.setOnFocusChangeListener((v, hasFocus) -> {
            GradientDrawable focusBg = new GradientDrawable();
            focusBg.setCornerRadius(dp(16));
            focusBg.setColor(0x12FFFFFF);
            focusBg.setStroke(dp(1), hasFocus ? 0xFF4facfe : 0x33FFFFFF);
            input.setBackground(focusBg);
        });

        return input;
    }

    private LinearLayout buildActionButton(String label, int colorStart, int colorEnd, Runnable action) {
        LinearLayout btn = hBox(Gravity.CENTER);
        btn.setPadding(dp(20), dp(12), dp(20), dp(12));

        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { colorStart, colorEnd });
        bg.setCornerRadius(dp(16));
        btn.setBackground(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            btn.setElevation(dp(4));

        ImageView ic = new ImageView(this);
        ic.setImageResource(res("ic_call_transfer"));
        ic.setColorFilter(Color.WHITE);
        btn.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));

        hSpacer(btn, 10);

        TextView labelView = txt(label, Color.WHITE, 16, Typeface.DEFAULT_BOLD);
        btn.addView(labelView, wrapCenter());

        btn.setOnClickListener(v -> {
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()).start();
            action.run();
        });

        return btn;
    }

    // ──────────────────────────────────────────────────────────────────
    // Toggle actions
    // ──────────────────────────────────────────────────────────────────

    private void toggleMute() {
        LinPhoneHelper h = LinPhoneHelper.getInstance();
        if (h == null)
            return;
        h.toggleMute();
        isMuted = h.isMuted();
        applyCircleToggle(muteCircle, muteIcon, isMuted);
        if (muteIcon != null)
            muteIcon.setImageResource(res(isMuted ? "ic_mic_off" : "ic_mic_on"));
        if (muteLabel != null)
            muteLabel.setText(isMuted ? "Unmute" : "Mute");
    }

    private void toggleSpeaker() {
        LinPhoneHelper h = LinPhoneHelper.getInstance();
        if (h == null)
            return;
        h.toggleSpeaker();
        isSpeaker = !isSpeaker;
        applyCircleToggle(speakerCircle, speakerIcon, isSpeaker);
        if (speakerIcon != null)
            speakerIcon.setImageResource(res(isSpeaker ? "ic_hearing" : "ic_volume_up"));
        if (speakerLabel != null)
            speakerLabel.setText(isSpeaker ? "Earpiece" : "Speaker");
        handler.postDelayed(() -> {
            LinPhoneHelper hp = LinPhoneHelper.getInstance();
            if (hp != null) {
                boolean actual = hp.isSpeakerEnabled();
                if (actual != isSpeaker) {
                    isSpeaker = actual;
                    applyCircleToggle(speakerCircle, speakerIcon, isSpeaker);
                    if (speakerIcon != null)
                        speakerIcon.setImageResource(res(isSpeaker ? "ic_hearing" : "ic_volume_up"));
                    if (speakerLabel != null)
                        speakerLabel.setText(isSpeaker ? "Earpiece" : "Speaker");
                }
            }
        }, 400);
    }

    private void toggleHold() {
        LinPhoneHelper h = LinPhoneHelper.getInstance();
        if (h == null)
            return;
        h.toggleHold();
        handler.postDelayed(() -> {
            LinPhoneHelper hp = LinPhoneHelper.getInstance();
            if (hp != null) {
                isOnHold = hp.isCallOnHold();
                applyCircleToggle(holdCircle, holdIcon, isOnHold);
                refreshHoldVisuals();
            }
        }, 500);
    }

    // ──────────────────────────────────────────────────────────────────
    // Hang up
    // ──────────────────────────────────────────────────────────────────

    private void doHangUp() {
        LinPhoneHelper h = LinPhoneHelper.getInstance();
        if (h != null)
            h.hangUp();
        finish();
    }

    // ──────────────────────────────────────────────────────────────────
    // Polling
    // ──────────────────────────────────────────────────────────────────

    private void startTimerAndPoll() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing())
                    return;
                LinPhoneHelper h = LinPhoneHelper.getInstance();
                if (h == null || !h.hasActiveCall()) {
                    finish();
                    return;
                }
                syncChronometerToCall();
                syncToggleStates();
                syncConsultButton(h);
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    /**
     * Enable the "Complete Transfer" button once the consultation call connects.
     */
    private void syncConsultButton(LinPhoneHelper h) {
        if (!consultationInProgress || completeBtnRef == null)
            return;
        boolean connected = h.isConsultCallConnected();
        if (connected && !completeBtnRef.isEnabled()) {
            completeBtnRef.setEnabled(true);
            completeBtnRef.setAlpha(1f);
            // Re-skin to green gradient
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[] { 0xFF4caf50, 0xFF2e7d32 });
            bg.setCornerRadius(dp(16));
            completeBtnRef.setBackground(bg);
            // Update label text
            for (int i = 0; i < completeBtnRef.getChildCount(); i++) {
                View child = completeBtnRef.getChildAt(i);
                if (child instanceof TextView) {
                    ((TextView) child).setText("Complete Transfer");
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Tiny helpers
    // ══════════════════════════════════════════════════════════════════

    private int res(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    private String getInitials(String name) {
        if (name == null || name.isEmpty())
            return "?";
        String[] p = name.trim().split("\\s+");
        if (p.length >= 2)
            return (p[0].substring(0, 1) + p[1].substring(0, 1)).toUpperCase();
        return name.substring(0, 1).toUpperCase();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView txt(String text, int color, int sp, Typeface tf) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (tf != null)
            tv.setTypeface(tf);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private LinearLayout vBox(int gravity) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(gravity);
        return l;
    }

    private LinearLayout hBox(int gravity) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(gravity);
        return l;
    }

    private void spacer(LinearLayout p, int hDp) {
        p.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(hDp)));
    }

    private void hSpacer(LinearLayout p, int wDp) {
        p.addView(new View(this), new LinearLayout.LayoutParams(dp(wDp), dp(1)));
    }

    private LinearLayout.LayoutParams wrapCenter() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    private LinearLayout.LayoutParams centeredLP(int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    private FrameLayout.LayoutParams centered(int size) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    private FrameLayout.LayoutParams matchWrap() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
