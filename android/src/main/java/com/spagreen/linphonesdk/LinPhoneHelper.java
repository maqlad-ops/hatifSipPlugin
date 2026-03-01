package com.spagreen.linphonesdk;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;

import org.linphone.core.Account;
import org.linphone.core.AccountListener;
import org.linphone.core.AccountParams;
import org.linphone.core.Address;
import org.linphone.core.AudioDevice;
import org.linphone.core.AuthInfo;
import org.linphone.core.Call;
import org.linphone.core.CallLog;
import org.linphone.core.CallParams;
import org.linphone.core.Config;
import org.linphone.core.Core;
import org.linphone.core.CoreListener;
import org.linphone.core.CoreListenerStub;
import org.linphone.core.Factory;
import org.linphone.core.MediaDirection;
import org.linphone.core.MediaEncryption;
import org.linphone.core.NatPolicy;
import org.linphone.core.PayloadType;
import org.linphone.core.RegistrationState;
import org.linphone.core.Transports;
import org.linphone.core.TransportType;
import org.linphone.core.VideoActivationPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import io.flutter.plugin.common.MethodChannel;

/**
 * Production-grade LinPhone Helper
 * Configured to match Sipnetic softphone behavior
 * 
 * Addresses:
 * - Incoming call reliability
 * - One-way audio issues
 * - NAT/RTP negotiation
 * - Speaker routing
 * - Audio device management
 */
public class LinPhoneHelper {
    private final String TAG = "LinPhoneSDK";
    private static Core core = null;
    private static LinPhoneHelper instance;
    private Context context;
    private String domain, userName, password;
    private EventChannelHelper loginListener;
    private EventChannelHelper callEventListener;

    // Audio management
    private AudioManager audioManager;
    private int originalAudioMode;
    private boolean originalSpeakerphoneState;

    // Config persistence
    private static final String PREF_NAME = "linphone_plugin_prefs";
    private static final String PREF_KEY_CONFIG = "linphone_user_config";
    private SharedPreferences preferences;
    private UserConfig userConfig;

    // Keep-alive timer
    private Timer keepAliveTimer;
    private static final long KEEP_ALIVE_INTERVAL = 25000; // 25 seconds (slightly less than 30s)

    // Handler for main thread operations
    private Handler mainHandler;

    // =============================
    // STUN/TURN Configuration (defaults, can be overridden via UI config)
    // =============================
    private static final String DEFAULT_STUN_SERVER = "stun.linphone.org";
    private static final int DEFAULT_STUN_PORT = 3478;

    public static synchronized LinPhoneHelper getInstance() {
        return instance;
    }

    public static synchronized LinPhoneHelper getOrCreateInstance(Context context) {
        if (instance == null) {
            instance = new LinPhoneHelper(context.getApplicationContext());
        }
        return instance;
    }

    public LinPhoneHelper(Context context) {
        this.context = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.userConfig = loadUserConfig();
        Log.d(TAG, "Loaded user config -> " + new Gson().toJson(userConfig));
        instance = this;
    }

    public void setLoginListener(EventChannelHelper listener) {
        this.loginListener = listener;
    }

    public void setCallEventListener(EventChannelHelper listener) {
        this.callEventListener = listener;
    }

    private EventChannelHelper callDataListener;

    public void setCallDataListener(EventChannelHelper listener) {
        this.callDataListener = listener;
    }

    /**
     * Broadcast a JSON snapshot of ALL call data to the Flutter EventChannel.
     * Called on every state change so the Flutter app always has fresh data.
     */
    public void broadcastCallData() {
        EventChannelHelper l = callDataListener;
        if (l == null)
            return;
        try {
            org.json.JSONObject json = buildCallDataJson();
            l.success(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting call data", e);
        }
    }

    /** Build call data JSON — used by both broadcastCallData and getCallDataJson */
    private org.json.JSONObject buildCallDataJson() throws org.json.JSONException {
        org.json.JSONObject json = new org.json.JSONObject();
        boolean hasCall = core != null && core.getCallsNb() > 0;
        json.put("hasActiveCall", hasCall);

        if (hasCall) {
            Call call = core.getCurrentCall();
            if (call == null && core.getCalls().length > 0)
                call = core.getCalls()[0];
            if (call != null) {
                json.put("callState", call.getState().name());
                String name = call.getRemoteAddress().getDisplayName();
                if (name == null || name.isEmpty())
                    name = call.getRemoteAddress().getUsername();
                json.put("remoteName", name != null ? name : "");
                json.put("remoteUri", call.getRemoteAddress().asStringUriOnly());
                json.put("duration", call.getDuration());
                json.put("muted", call.getMicrophoneMuted());
                json.put("speaker", isSpeakerEnabled());
                Call.State st = call.getState();
                json.put("onHold", st == Call.State.Paused || st == Call.State.PausedByRemote);
            } else {
                putEmptyCallFields(json);
            }
        } else {
            putEmptyCallFields(json);
        }

        String regState = "None";
        if (core != null && core.getDefaultAccount() != null) {
            regState = core.getDefaultAccount().getState().name();
        }
        json.put("registrationState", regState);
        return json;
    }

    private void putEmptyCallFields(org.json.JSONObject json) throws org.json.JSONException {
        json.put("callState", "Idle");
        json.put("remoteName", "");
        json.put("remoteUri", "");
        json.put("duration", 0);
        json.put("muted", false);
        json.put("speaker", false);
        json.put("onHold", false);
    }

    /** Get current call data as JSON string (for method channel) */
    public String getCallDataJson() {
        try {
            return buildCallDataJson().toString();
        } catch (Exception e) {
            Log.e(TAG, "Error building call data JSON", e);
            return "{}";
        }
    }

    /** Thread-safe event helpers – guard against null event channels */
    private void sendLoginEvent(String event) {
        EventChannelHelper l = loginListener;
        if (l != null)
            l.success(event);
    }

    private void sendLoginError(String code, String msg, Object details) {
        EventChannelHelper l = loginListener;
        if (l != null)
            l.error(code, msg, details);
    }

    private void sendCallEvent(String event) {
        EventChannelHelper c = callEventListener;
        if (c != null)
            c.success(event);
    }

    private void sendCallError(String code, String msg, Object details) {
        EventChannelHelper c = callEventListener;
        if (c != null)
            c.error(code, msg, details);
    }

    /**
     * Push current call details (mute/hold/speaker/state) to the service
     * notification
     */
    private void pushCallDetailsToService(Call call, String stateName) {
        String remoteName = "";
        boolean muted = false;
        boolean onHold = false;
        boolean speaker = false;
        try {
            if (call != null) {
                String displayName = call.getRemoteAddress().getDisplayName();
                if (displayName == null || displayName.isEmpty()) {
                    displayName = call.getRemoteAddress().getUsername();
                }
                remoteName = displayName != null ? displayName : call.getRemoteAddress().asStringUriOnly();
                muted = call.getMicrophoneMuted();
                Call.State st = call.getState();
                onHold = (st == Call.State.Paused || st == Call.State.PausedByRemote);
                speaker = isSpeakerEnabled();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error reading call details for notification", e);
        }
        SipForegroundService.updateCallDetails(stateName, remoteName, muted, onHold, speaker);
        broadcastCallData();
    }

    /**
     * Login with default TLS transport (most secure)
     */
    public void login(String userName, String domain, String password) {
        login(userName, domain, password, TransportType.Tls);
    }

    /**
     * Login with specified transport type
     * 
     * @param transportType TransportType.Udp, TransportType.Tcp, or
     *                      TransportType.Tls
     */
    public void login(String userName, String domain, String password, TransportType transportType) {
        Log.d(TAG, "login requested -> user=" + userName + " domain=" + domain + " transport=" + transportType.name());

        // If already registered with the same credentials, skip re-registration
        if (core != null && userName != null && userName.equals(this.userName)
                && domain != null && domain.equals(this.domain)) {
            Account acct = core.getDefaultAccount();
            if (acct != null && acct.getState() == RegistrationState.Ok) {
                Log.d(TAG, "Already registered with same credentials — skipping login");
                sendLoginEvent("Ok");
                broadcastCallData();
                return;
            }
        }

        this.domain = domain;
        this.userName = userName;
        this.password = password;

        Log.d(TAG, "login using config -> " + new Gson().toJson(userConfig));

        if (password != null) {
            Log.d(TAG, "login password length=" + password.length());
        }

        // Clean up existing core before creating new one
        if (core != null) {
            Log.d(TAG, "Cleaning up existing core before new login");
            stopKeepAliveTimer();
            core.removeListener(coreListener);
            core.stop();
            core = null;
        }

        Factory factory = Factory.instance();
        factory.setDebugMode(true, TAG);

        Log.d(TAG, "Creating new core instance (debug enabled)");

        // Create core with proper initialization
        core = factory.createCore(null, null, context);

        Log.d(TAG, "Core created. Configuring settings...");

        // =============================
        // CORE CONFIGURATION - MUST BE BEFORE START
        // =============================
        configureCoreSettings();
        configureAudioCodecs();
        configureNatAndFirewall();
        configureRtpSettings();
        configureNetworkSettings();
        configureSipExtensions();
        configureAudioProcessing();

        Log.d(TAG, "Configuration complete. Building account params");

        // =============================
        // ACCOUNT SETUP
        // =============================
        AuthInfo authInfo = Factory.instance().createAuthInfo(
                userName,
                null,
                password,
                null,
                null,
                domain,
                null);

        AccountParams params = core.createAccountParams();

        String sipAddress = "sip:" + userName + "@" + domain;
        Address identity = Factory.instance().createAddress(sipAddress);
        params.setIdentityAddress(identity);

        // Server address with transport
        Address serverAddress = Factory.instance().createAddress("sip:" + domain);
        serverAddress.setTransport(transportType);
        params.setServerAddress(serverAddress);
        Log.d(TAG, "Server address set -> " + serverAddress.asStringUriOnly());

        // =============================
        // REGISTRATION SETTINGS
        // =============================
        params.setRegisterEnabled(true);
        params.setPublishEnabled(false); // Disable PUBLISH unless needed

        Log.d(TAG, "Registration enabled. Expires=" + params.getExpires());

        // Registration expiration - matches Sipnetic (10 minutes)
        params.setExpires(600); // 10 minutes, server will negotiate

        // Push notifications DISABLED – no FCM configured.
        // Keeping these true would cause the SDK to add push parameters
        // to the Contact header and potentially skip maintaining the
        // transport connection, leading to missed incoming calls.
        params.setPushNotificationAllowed(false);
        params.setRemotePushNotificationAllowed(false);

        // =============================
        // NAT POLICY FOR ACCOUNT (Match Sipnetic - NO ICE)
        // =============================
        NatPolicy natPolicy = core.createNatPolicy();
        natPolicy.setStunServer(userConfig.stunServer + ":" + userConfig.stunPort);
        natPolicy.enableStun(userConfig.enableStun);
        natPolicy.enableIce(userConfig.enableIce);
        natPolicy.enableTurn(userConfig.enableTurn);
        natPolicy.enableUpnp(userConfig.enableUpnp);
        params.setNatPolicy(natPolicy);
        Log.d(TAG, "NAT policy -> stun=" + natPolicy.getStunServer() + " ice=" + natPolicy.iceEnabled()
                + " turn=" + natPolicy.turnEnabled() + " upnp=" + natPolicy.upnpEnabled());

        // Create and add account
        Account account = core.createAccount(params);
        core.addAuthInfo(authInfo);
        core.addAccount(account);
        core.setDefaultAccount(account);

        Log.d(TAG, "Account added and set as default. Starting listeners and core");

        // Add listeners
        core.addListener(coreListener);
        account.addListener(new AccountListener() {
            @Override
            public void onRegistrationStateChanged(@NonNull Account account, RegistrationState registrationState,
                    @NonNull String message) {
                Log.d(TAG, "Registration state: " + registrationState.name() + " - " + message);

                // Push state to the foreground service notification
                SipForegroundService.updateRegistrationState(registrationState.name());

                if (registrationState == RegistrationState.Ok) {
                    Log.d(TAG, "Successfully registered to SIP server");
                    try {
                        AccountParams currentParams = account.getParams();
                        if (currentParams != null && currentParams.getIdentityAddress() != null) {
                            Log.d(TAG, "Registered identity: " + currentParams.getIdentityAddress().asStringUriOnly());
                        }
                        if (account.getContactAddress() != null) {
                            Log.d(TAG, "Registered Contact (server sees): " + account.getContactAddress().asString());
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not log contact address: " + e.getMessage());
                    }
                    startKeepAliveTimer();
                    startHealthMonitor();
                    SipForegroundService.start(context, userName);
                    sendLoginEvent("Ok");
                    broadcastCallData();
                } else if (registrationState == RegistrationState.Failed) {
                    Log.e(TAG, "Registration failed: " + message);
                    stopKeepAliveTimer();
                    stopHealthMonitor();
                    sendLoginError("400", "Login failed", message);
                    broadcastCallData();
                } else if (registrationState == RegistrationState.Cleared) {
                    Log.d(TAG, "Registration cleared");
                    stopKeepAliveTimer();
                    stopHealthMonitor();
                    broadcastCallData();
                }
            }
        });

        // =============================
        // DISABLE SDK AUTO-ITERATE BEFORE START
        // =============================
        // Must be set BEFORE core.start() to prevent the SDK from launching
        // its built-in CoreService (which shows a persistent foreground
        // notification). We handle iterations ourselves.
        try {
            java.lang.reflect.Method mAutoItPre = core.getClass().getMethod("setAutoIterateEnabled", boolean.class);
            mAutoItPre.invoke(core, false);
            Log.d(TAG, "Pre-start: auto-iterate disabled");
        } catch (Exception e) {
            Log.d(TAG, "Pre-start: setAutoIterateEnabled not available: " + e.getMessage());
        }
        // Also set config flags before start
        try {
            Config preCfg = core.getConfig();
            preCfg.setBool("app", "auto_iterate", false);
            preCfg.setBool("app", "auto_start", false);
            preCfg.setBool("app", "use_core_service", false);
        } catch (Exception e) {
            Log.d(TAG, "Pre-start: config flags not settable: " + e.getMessage());
        }

        // =============================
        // START CORE
        // =============================
        core.start();

        Log.d(TAG, "Core start invoked, setting network reachable");

        // =============================
        // DISABLE SDK's AUTO-ITERATE / CoreService (AFTER start!)
        // =============================
        // The SDK's CoreService is a foreground service that shows its own
        // notification. We handle iterations ourselves (startCoreIteration)
        // and notifications via SipForegroundService, so disable the SDK's
        // automatic behavior to prevent a persistent keep-alive notification.
        try {
            java.lang.reflect.Method mAutoIterate = core.getClass().getMethod("setAutoIterateEnabled", boolean.class);
            mAutoIterate.invoke(core, false);
            Log.d(TAG, "Auto-iterate DISABLED (we handle iterations ourselves)");
        } catch (NoSuchMethodException nsme) {
            Log.d(TAG, "setAutoIterateEnabled not available on this SDK build");
        } catch (Exception e) {
            Log.w(TAG, "Failed to disable auto-iterate: " + e.getMessage());
        }
        // Also try to disable auto-foreground service via config
        try {
            Config cfg = core.getConfig();
            cfg.setBool("app", "auto_iterate", false);
            cfg.setBool("app", "auto_start", false);
            // Prevent the SDK from starting its own CoreService
            cfg.setBool("app", "use_core_service", false);
            Log.d(TAG, "Auto-iterate config flags disabled");
        } catch (Exception e) {
            Log.w(TAG, "Failed to set auto-iterate config: " + e.getMessage());
        }

        // =============================
        // KILL SDK's CoreService & PURGE ITS NOTIFICATIONS
        // =============================
        // Even though CoreService is removed from the manifest via
        // tools:node="remove", the SDK's CoreManager may still try
        // to post notifications or start the service. Belt-and-suspenders:
        // explicitly stop the service and cancel all SDK notifications.
        killSdkCoreServiceNotifications();

        // CRITICAL: setNetworkReachable MUST be called AFTER core.start().
        // Calling it before start() has no effect because start() resets
        // internal state. Without this the core may believe the network
        // is unreachable and silently drop incoming SIP messages.
        core.setNetworkReachable(true);
        Log.d(TAG, "Network reachable set to TRUE after core.start()");

        // =============================
        // ENABLE KEEP-ALIVE VIA API (AFTER start!)
        // =============================
        // The SDK's built-in keep-alive sends CRLF pings over the UDP/TCP
        // socket to maintain the NAT binding. This MUST be enabled AFTER
        // core.start() because start() may reset internal flags.
        try {
            core.enableKeepAlive(true);
            Log.d(TAG, "Keep-alive enabled via API: " + core.keepAliveEnabled());
        } catch (Exception e) {
            Log.w(TAG, "enableKeepAlive via API failed (trying reflection): " + e.getMessage());
            // Fallback: try via reflection for different SDK builds
            try {
                java.lang.reflect.Method m = core.getClass().getMethod("enableKeepAlive", boolean.class);
                m.invoke(core, true);
                Log.d(TAG, "Keep-alive enabled via reflection");
            } catch (Exception ex) {
                Log.w(TAG, "Keep-alive reflection also failed: " + ex.getMessage());
            }
        }

        // Log the actual transports in use after start
        try {
            Transports used = core.getTransportsUsed();
            Log.d(TAG, "Transports USED after start: UDP=" + used.getUdpPort()
                    + " TCP=" + used.getTcpPort() + " TLS=" + used.getTlsPort());
        } catch (Exception e) {
            Log.w(TAG, "Could not read transports used: " + e.getMessage());
        }

        // Ensure iterates are performed for core to function
        startCoreIteration();

        Log.d(TAG, "Linphone Core started with production configuration");
    }

    /**
     * Configure core-level settings
     */
    private void configureCoreSettings() {
        if (core == null)
            return;

        // =============================
        // USER-AGENT (Match Sipnetic exactly)
        // =============================
        core.setUserAgent(userConfig.userAgentName, userConfig.userAgentVersion);

        // =============================
        // TLS CERTIFICATE - Use Bundled Let's Encrypt Root CA
        // =============================
        // Linphone SDK's mbedTLS requires a PEM file, not a directory
        // Copy bundled Let's Encrypt roots to app files directory
        String rootCaPath = copyRootCaToFiles();
        if (rootCaPath != null) {
            core.setRootCa(rootCaPath);
            Log.d(TAG, "Using bundled Let's Encrypt root CA: " + rootCaPath);
        } else {
            // Fallback - try system path (may not work with mbedTLS)
            core.setRootCa("/system/etc/security/cacerts");
            Log.w(TAG, "Fallback to system CA (may not work)");
        }

        // Configure settings
        Config tlsConfig = core.getConfig();

        // =============================
        // TLS VERIFICATION - DISABLED (accept all certs/SNI)
        // =============================
        // User request: allow TLS without certificate validation like Sipnetic.
        // This bypasses chain and hostname verification – only use in trusted networks.
        tlsConfig.setBool("sip", "verify_server_certs", false);
        // Some builds expect the long key name; set both to cover all configs.
        tlsConfig.setBool("sip", "verify_server_certificates", false);
        tlsConfig.setBool("sip", "verify_server_cn", false);
        // Linphone also honors generic tls_verify knobs; set all of them to 0 to force
        // no verification.
        tlsConfig.setInt("sip", "tls_verify", 0);
        tlsConfig.setInt("sip", "tls_verify_incoming", 0);
        tlsConfig.setInt("sip", "tls_verify_outgoing", 0);
        tlsConfig.setString("sip", "tls_auth_mode", "0");
        // Some stacks still enforce cert requirements unless this is flipped
        // explicitly; keep it in sync with the rest of the flags.
        tlsConfig.setBool("sip", "tls_cert_not_required", true);
        // Attempt to also toggle any runtime flags exposed by the SDK (covers cases
        // where config keys are ignored by specific builds). Reflection keeps us
        // compatible even if methods are absent.
        try {
            // Reflective calls are optional; they are absent on some SDK builds.
            java.lang.reflect.Method mCerts = core.getClass().getMethod("setVerifyServerCertificates", boolean.class);
            java.lang.reflect.Method mCn = core.getClass().getMethod("setVerifyServerCN", boolean.class);
            java.lang.reflect.Method mNotRequired = core.getClass().getMethod("setTlsCertNotRequired", boolean.class);

            mCerts.invoke(core, false);
            mCn.invoke(core, false);
            mNotRequired.invoke(core, true);
            Log.d(TAG, "Applied runtime TLS verification bypass via reflection");
        } catch (NoSuchMethodException nsme) {
            Log.d(TAG, "Runtime TLS bypass setters not exposed by this SDK build; config flags only");
        } catch (Exception e) {
            Log.w(TAG, "Runtime TLS bypass reflection failed: " + e.getMessage());
        }
        // Log the final TLS verification state to confirm values at runtime.
        Log.d(TAG, "TLS verify flags -> certs="
                + tlsConfig.getBool("sip", "verify_server_certs", true)
                + "/certificates=" + tlsConfig.getBool("sip", "verify_server_certificates", true)
                + ", cn=" + tlsConfig.getBool("sip", "verify_server_cn", true)
                + ", tls_verify=" + tlsConfig.getInt("sip", "tls_verify", 1)
                + ", tls_verify_in=" + tlsConfig.getInt("sip", "tls_verify_incoming", 1)
                + ", tls_verify_out=" + tlsConfig.getInt("sip", "tls_verify_outgoing", 1)
                + ", tls_cert_not_required=" + tlsConfig.getBool("sip", "tls_cert_not_required", false));
        // =============================
        // NETWORK TRANSPORT CONFIG
        // =============================
        Transports transports = core.getTransports();
        // Use random ports for better NAT traversal
        transports.setUdpPort(-1); // -1 = random port
        transports.setTcpPort(-1);
        transports.setTlsPort(-1);
        core.setTransports(transports);
        Log.d(TAG, "Transports configured -> UDP=" + transports.getUdpPort() + " TCP=" + transports.getTcpPort()
                + " TLS=" + transports.getTlsPort());

        // =============================
        // KEEP-ALIVE SETTINGS (via Config)
        // =============================
        Config keepAliveConfig = core.getConfig();
        // Enable keep-alive with ALL possible config key variants.
        // Different Linphone SDK builds may use different key names.
        // The keep-alive sends CRLF pings ("\r\n\r\n") over UDP to
        // maintain the NAT binding. Without this, the router's NAT
        // table entry expires and incoming SIP INVITEs can't reach us.
        keepAliveConfig.setBool("net", "keep_alive", true);
        keepAliveConfig.setBool("net", "keepalive", true);
        // Period in seconds – aggressive 15s for mobile NAT survival
        keepAliveConfig.setInt("net", "keep_alive_period", 15);
        keepAliveConfig.setInt("net", "keepalive_period", 15);
        keepAliveConfig.setInt("sip", "keepalive_period", 15);
        keepAliveConfig.setInt("sip", "keep_alive_period", 15);
        Log.d(TAG, "Keep-alive config keys set (multiple variants, period=15s)");
        // NOTE: setNetworkReachable AND enableKeepAlive are called AFTER
        // core.start() – not here. start() resets internal state.

        // =============================
        // IPV6 SETTINGS (via Config)
        // =============================
        // IPv6 DISABLED: Linphone's STUN implementation does not support IPv6.
        // When ipv6=true the SDK prints "STUN support is not implemented for
        // ipv6" and falls back to the *local* IP for the SDP c= / m= lines,
        // causing media to target a private address. Disabling IPv6 lets STUN
        // resolve the public NAT address and place it in the SDP correctly.
        keepAliveConfig.setBool("net", "ipv6", false);
        keepAliveConfig.setBool("net", "prefer_ipv6", false);

        // =============================
        // DNS SETTINGS (via Config)
        // =============================
        keepAliveConfig.setBool("net", "dns_srv_enabled", true);

        // =============================
        // MEDIA SETTINGS
        // =============================
        core.setUseInfoForDtmf(userConfig.useInfoDtmf);
        core.setUseRfc2833ForDtmf(userConfig.useRfc2833);

        core.enableVideoCapture(userConfig.enableVideo);
        core.enableVideoDisplay(userConfig.enableVideo);
        VideoActivationPolicy videoPolicy = core.getVideoActivationPolicy();
        if (videoPolicy != null) {
            videoPolicy.setAutomaticallyAccept(userConfig.enableVideo);
            videoPolicy.setAutomaticallyInitiate(userConfig.enableVideo);
            core.setVideoActivationPolicy(videoPolicy);
        }

        // =============================
        // SESSION TIMER SETTINGS (RFC 4028)
        // =============================
        Config config = core.getConfig();
        config.setInt("sip", "session_expires", 300); // Session timer: 300 seconds
        config.setInt("sip", "min_se", 90); // Minimum: 90 seconds
        config.setString("sip", "session_refresher", "uas"); // Let server decide (UAS)

        // =============================
        // SIP EXTENSIONS
        // =============================
        config.setBool("sip", "use_100rel", userConfig.use100rel);
        config.setBool("sip", "use_rport", userConfig.useRport);
        config.setBool("sip", "use_gruu", userConfig.useGruu);
        config.setBool("sip", "use_outbound", userConfig.useOutbound);
        config.setBool("sip", "use_update_method", userConfig.useUpdate);

        // Offer/Answer settings
        config.setBool("sip", "send_initial_offer", userConfig.sendInitialOffer);
        config.setBool("sip", "delay_offer", userConfig.delayOffer);

        // Contact handling (Sipnetic: enabled)
        config.setBool("sip", "forget_old_contacts", userConfig.contactRewrite);

        // =============================
        // PUSH NOTIFICATION CONFIG
        // =============================
        // Push DISABLED: We do not have FCM/Firebase configured.
        // When push is "enabled" without FCM, the SDK may assume that
        // a push gateway will wake the device for incoming calls and
        // therefore NOT maintain the SIP connection aggressively. This
        // causes incoming INVITEs to be silently lost. Disabling push
        // forces the SDK to keep the transport open at all times.
        core.setPushNotificationEnabled(false);

        // =============================
        // P-ASSERTED-IDENTITY (Sipnetic: enabled)
        // =============================
        config.setBool("sip", "use_pai", userConfig.usePai);

        Log.d(TAG, "SIP ext -> 100rel=" + config.getBool("sip", "use_100rel", false)
                + " rport=" + config.getBool("sip", "use_rport", false)
                + " outbound=" + config.getBool("sip", "use_outbound", false)
                + " earlyMedia=" + config.getBool("sip", "incoming_calls_early_media", true)
                + " offerFirst=" + config.getBool("sip", "send_initial_offer", false)
                + " delayOffer=" + config.getBool("sip", "delay_offer", true));

        Log.d(TAG, "Core settings configured");
    }

    /**
     * Configure audio codecs - Match Sipnetic configuration
     * Enabled: Opus, G.722, Speex, GSM, G.711 A-Law, G.711 μ-Law, G.729, Speex
     * Wideband, Speex Ultra-Wideband
     */
    private void configureAudioCodecs() {
        if (core == null)
            return;

        Set<String> enabled = new HashSet<>(userConfig.enabledCodecs);

        // Enable matching codecs
        PayloadType[] audioCodecs = core.getAudioPayloadTypes();
        for (PayloadType codec : audioCodecs) {
            String mimeType = codec.getMimeType().toLowerCase();
            if (enabled.contains(mimeType)) {
                codec.enable(true);
                Log.d(TAG, "Enabled codec: " + codec.getMimeType() + "/" + codec.getClockRate());
            } else {
                codec.enable(false);
                Log.d(TAG, "Disabled codec: " + codec.getMimeType() + "/" + codec.getClockRate());
            }
        }

        // Set updated codec list
        core.setAudioPayloadTypes(audioCodecs);

        Log.d(TAG, "Audio codecs configured - Sipnetic compatible");
    }

    /**
     * Configure NAT and Firewall settings
     * NOTE: Sipnetic does NOT use ICE - it relies on rport/contact_rewrite
     */
    private void configureNatAndFirewall() {
        if (core == null)
            return;

        // =============================
        // NAT POLICY (Match Sipnetic - NO ICE)
        // =============================
        // Sipnetic's SDP has NO ICE candidates - it uses rport + symmetric RTP
        NatPolicy natPolicy = core.createNatPolicy();

        // STUN / TURN / ICE based on user config
        natPolicy.setStunServer(userConfig.stunServer + ":" + userConfig.stunPort);
        natPolicy.enableStun(userConfig.enableStun);

        natPolicy.enableIce(userConfig.enableIce);

        // TURN (disable unless provided)
        natPolicy.enableTurn(userConfig.enableTurn);
        // If you have TURN:
        // natPolicy.setTurnServer("turn.yourserver.com:3478");
        // natPolicy.setTurnUsername("username");
        // natPolicy.setTurnPassword("password");

        // UPnP (usually not needed with ICE)
        natPolicy.enableUpnp(userConfig.enableUpnp);

        // Apply NAT policy
        core.setNatPolicy(natPolicy);

        // =============================
        // MEDIA ENCRYPTION
        // =============================
        MediaEncryption enc = MediaEncryption.None;
        if ("srtp".equals(userConfig.mediaEncryption)) {
            enc = MediaEncryption.SRTP;
        } else if ("zrtp".equals(userConfig.mediaEncryption)) {
            enc = MediaEncryption.ZRTP;
        }
        core.setMediaEncryption(enc);
        core.setMediaEncryptionMandatory(!"none".equals(userConfig.mediaEncryption));

        // =============================
        // FIREWALL POLICY
        // =============================
        // Enable symmetric RTP for better NAT traversal
        Config config = core.getConfig();
        config.setBool("rtp", "symmetric_rtp", userConfig.symmetricRtp);

        // Contact rewrite for NAT
        config.setBool("sip", "contact_rewrite", userConfig.contactRewrite);
        config.setBool("sip", "guess_hostname", userConfig.guessHostname);

        Log.d(TAG, "NAT and firewall settings configured");
    }

    /**
     * Configure RTP settings
     */
    private void configureRtpSettings() {
        if (core == null)
            return;

        Config config = core.getConfig();

        // =============================
        // RTP PORT RANGE (Sipnetic: 16384-65535)
        // =============================
        core.setAudioPort(userConfig.rtpStartPort); // Start port
        core.setAudioPortRange(userConfig.rtpStartPort, userConfig.rtpEndPort); // Port range
        Log.d(TAG, "RTP ports -> start=" + userConfig.rtpStartPort + " range=" + userConfig.rtpStartPort + "-"
                + userConfig.rtpEndPort);

        // =============================
        // RTP/AVP PROFILE (Standard - for FreePBX/Asterisk compatibility)
        // =============================
        // Use standard RTP/AVP profile (NOT AVPF) for maximum compatibility
        core.setAvpfMode(
                userConfig.enableAvpf ? org.linphone.core.AVPFMode.Enabled : org.linphone.core.AVPFMode.Disabled);
        config.setBool("rtp", "avpf", userConfig.enableAvpf);

        // =============================
        // SYMMETRIC RTP
        // =============================
        config.setBool("rtp", "symmetric_rtp", userConfig.symmetricRtp);

        // =============================
        // ADAPTIVE RATE CONTROL (Sipnetic: disabled)
        // =============================
        core.enableAdaptiveRateControl(userConfig.adaptiveRateControl);

        // Jitter buffer settings for better audio quality
        config.setInt("rtp", "jitter_buffer_min_size", userConfig.jitterBufferMin);
        config.setInt("rtp", "audio_jitter_buffer_ms", userConfig.jitterBuffer);

        // =============================
        // RTCP FEEDBACK (rtcp-fb) – DISABLED
        // =============================
        // Linphone adds "a=rtcp-fb:* trr-int 1000" and "a=rtcp-fb:* ccm tmmbr"
        // to every SDP. These ~50 extra bytes contribute to pushing the
        // authenticated INVITE over the UDP MTU. They are not needed by
        // FreePBX/Asterisk and Sipnetic does not include them.
        config.setBool("rtp", "rtcp_fb_generic_nack_enabled", false);
        config.setBool("rtp", "rtcp_fb_tmmbr_enabled", false);
        config.setInt("rtp", "rtcp_fb_trr_interval", 0);

        Log.d(TAG, "RTP settings configured");
    }

    /**
     * Configure network settings
     * NOTE: Transport ports are configured in configureCoreSettings() to avoid
     * duplication.
     * setNetworkReachable is called AFTER core.start() in login().
     */
    private void configureNetworkSettings() {
        if (core == null)
            return;

        Config netConfig = core.getConfig();

        // =============================
        // IPv6 (via Config) – see configureCoreSettings() for rationale
        // =============================
        netConfig.setBool("net", "ipv6", false);
        netConfig.setBool("net", "prefer_ipv6", false);

        // =============================
        // DNS (via Config)
        // =============================
        netConfig.setBool("net", "dns_srv_enabled", true);

        Log.d(TAG, "Network settings configured (transports set in configureCoreSettings)");
    }

    /**
     * Configure SIP extensions
     */
    private void configureSipExtensions() {
        if (core == null)
            return;

        Config config = core.getConfig();

        // =============================
        // 100REL (PRACK)
        // =============================
        config.setBool("sip", "use_100rel", userConfig.use100rel);

        // =============================
        // GRUU
        // =============================
        config.setBool("sip", "use_gruu", userConfig.useGruu);

        // =============================
        // OUTBOUND
        // =============================
        config.setBool("sip", "use_outbound", userConfig.useOutbound);

        // =============================
        // UPDATE METHOD
        // =============================
        config.setBool("sip", "use_update_method", userConfig.useUpdate);

        // =============================
        // SESSION TIMERS
        // =============================
        config.setInt("sip", "session_expires", 300);
        config.setInt("sip", "min_se", 90);
        config.setString("sip", "session_refresher", "uas"); // Server decides

        // =============================
        // RPORT
        // =============================
        config.setBool("sip", "use_rport", userConfig.useRport);

        // =============================
        // DTMF - RFC2833
        // =============================
        core.setUseRfc2833ForDtmf(userConfig.useRfc2833);
        core.setUseInfoForDtmf(userConfig.useInfoDtmf);

        // =============================
        // PROVISIONAL SDP / EARLY MEDIA
        // =============================
        // Disable sending SDP in provisional responses (183/180)
        config.setBool("sip", "send_early_media", userConfig.sendEarlyMedia);
        config.setBool("sip", "incoming_calls_early_media", userConfig.incomingEarlyMedia);

        Log.d(TAG, "SIP extensions configured");
    }

    /**
     * Configure audio processing settings
     */
    private void configureAudioProcessing() {
        if (core == null)
            return;

        // =============================
        // ECHO CANCELLATION
        // =============================
        core.enableEchoCancellation(userConfig.echoCancellation);

        // Echo canceller settings
        Config config = core.getConfig();
        config.setInt("sound", "ec_tail_len", 100);
        config.setInt("sound", "ec_delay", 0);
        config.setInt("sound", "ec_framesize", 128);

        // =============================
        // ECHO LIMITER (backup for echo cancellation)
        // =============================
        core.enableEchoLimiter(userConfig.echoLimiter);

        // =============================
        // AUTOMATIC GAIN CONTROL (AGC)
        // =============================
        core.setMicGainDb(0.0f);
        config.setBool("sound", "agc", userConfig.agc);

        // =============================
        // NOISE SUPPRESSION
        // =============================
        config.setBool("sound", "noise_gate", userConfig.noiseGate);
        config.setFloat("sound", "noise_gate_threshold", userConfig.noiseGateThreshold);

        // =============================
        // AUDIO QUALITY
        // =============================
        config.setInt("sound", "playback_gain", 0);
        config.setInt("sound", "capture_gain", 0);

        Log.d(TAG, "Audio processing configured");
    }

    /**
     * Aggressively kill the Linphone SDK's built-in CoreService and purge
     * any notifications it may have created. Called after core.start() to
     * ensure no keep-alive notification persists.
     */
    private void killSdkCoreServiceNotifications() {
        try {
            // 1. Try to explicitly stop the SDK's CoreService (if it somehow started)
            try {
                android.content.Intent coreServiceIntent = new android.content.Intent();
                coreServiceIntent.setClassName(context.getPackageName(),
                        "org.linphone.core.tools.service.CoreService");
                context.stopService(coreServiceIntent);
                Log.d(TAG, "Stopped SDK CoreService (if running)");
            } catch (Exception e) {
                Log.d(TAG, "CoreService stop attempt: " + e.getMessage());
            }

            // 2. Cancel ALL notifications the SDK might have posted
            android.app.NotificationManager nm = (android.app.NotificationManager) context
                    .getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                // SDK typically uses notification IDs 1, 2, 3
                nm.cancel(1);
                nm.cancel(2);
                nm.cancel(3);
                // Also cancel with tags the SDK might use
                nm.cancel("linphone", 1);
                nm.cancel("linphone_core", 1);

                // 3. Delete ALL known SDK notification channels
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    String[] sdkChannels = {
                            "org.linphone.core.service_notification_channel",
                            "linphone_notification_channel",
                            "Linphone",
                            "linphone_channel",
                            "org_linphone_core_service_channel",
                            "linphone_core_notification_channel",
                            "service_notification_channel",
                            "linphone_service_channel",
                            "linphone_idle_channel"
                    };
                    for (String ch : sdkChannels) {
                        nm.deleteNotificationChannel(ch);
                    }

                    // 4. Nuclear option: cancel ALL active notifications that
                    // don't belong to our NOTIF_ID (10001)
                    for (android.service.notification.StatusBarNotification sbn : nm.getActiveNotifications()) {
                        if (sbn.getId() != 10001 && sbn.getPackageName().equals(context.getPackageName())) {
                            nm.cancel(sbn.getTag(), sbn.getId());
                            Log.d(TAG, "Cancelled rogue notification: id=" + sbn.getId()
                                    + " tag=" + sbn.getTag() + " channel=" + sbn.getNotification().getChannelId());
                        }
                    }
                }
                Log.d(TAG, "SDK notification channels purged, stale notifications cancelled");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error killing SDK notifications: " + e.getMessage());
        }

        // 5. Schedule another purge 3 seconds later (in case SDK creates them
        // post-start)
        mainHandler.postDelayed(() -> {
            try {
                android.app.NotificationManager nm = (android.app.NotificationManager) context
                        .getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(1);
                    nm.cancel(2);
                    nm.cancel(3);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        for (android.service.notification.StatusBarNotification sbn : nm.getActiveNotifications()) {
                            if (sbn.getId() != 10001 && sbn.getPackageName().equals(context.getPackageName())) {
                                nm.cancel(sbn.getTag(), sbn.getId());
                                Log.d(TAG, "Delayed purge: cancelled notification id=" + sbn.getId());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Delayed notification purge error: " + e.getMessage());
            }
        }, 3000);
    }

    /**
     * Start core iteration timer (required for Linphone to work)
     */
    private void startCoreIteration() {
        if (core == null)
            return;

        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable iterate = new Runnable() {
            @Override
            public void run() {
                if (core != null) {
                    core.iterate();
                    handler.postDelayed(this, 20); // 20ms iteration interval
                } else {
                    Log.w(TAG, "Core iteration stopped: core is null");
                }
            }
        };
        Log.d(TAG, "Core iteration loop started (20ms)");
        handler.post(iterate);
    }

    // =============================
    // HEALTH MONITOR – periodic log of core health
    // =============================
    private Timer healthMonitor;
    private static final long HEALTH_INTERVAL = 10000; // 10 seconds

    private void startHealthMonitor() {
        stopHealthMonitor();
        healthMonitor = new Timer("LinphoneHealth");
        healthMonitor.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    boolean coreOk = core != null;
                    boolean netOk = coreOk && core.isNetworkReachable();
                    String regState = "?";
                    int calls = 0;
                    boolean keepAlive = false;
                    String contact = "?";
                    String usedPorts = "?";
                    if (coreOk) {
                        calls = core.getCallsNb();
                        Account a = core.getDefaultAccount();
                        if (a != null) {
                            regState = a.getState().name();
                            try {
                                if (a.getContactAddress() != null)
                                    contact = a.getContactAddress().asString();
                            } catch (Exception ignored) {
                            }
                        }
                        try {
                            keepAlive = core.keepAliveEnabled();
                        } catch (Exception ignored) {
                            keepAlive = core.getConfig().getBool("net", "keep_alive", false);
                        }
                        try {
                            Transports t = core.getTransportsUsed();
                            usedPorts = "UDP=" + t.getUdpPort() + " TCP=" + t.getTcpPort();
                        } catch (Exception ignored) {
                        }
                    }
                    boolean loginSink = loginListener != null && loginListener.isReady();
                    boolean callSink = callEventListener != null && callEventListener.isReady();
                    Log.d(TAG, "[HEALTH] core=" + coreOk
                            + " net=" + netOk
                            + " reg=" + regState
                            + " calls=" + calls
                            + " keepAlive=" + keepAlive
                            + " ports=" + usedPorts
                            + " loginSink=" + loginSink
                            + " callSink=" + callSink);
                    if (coreOk && !"?".equals(contact)) {
                        Log.d(TAG, "[HEALTH] contact=" + contact);
                    }
                    // Auto-recover: if network became unreachable, re-enable it
                    if (coreOk && !netOk) {
                        Log.w(TAG, "[HEALTH] network unreachable! Re-enabling...");
                        core.setNetworkReachable(true);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "[HEALTH] error: " + e.getMessage());
                }
            }
        }, 5000, HEALTH_INTERVAL);
        Log.d(TAG, "Health monitor started (" + HEALTH_INTERVAL + "ms)");
    }

    private void stopHealthMonitor() {
        if (healthMonitor != null) {
            healthMonitor.cancel();
            healthMonitor = null;
        }
    }

    /**
     * Start keep-alive timer for maintaining registration
     */
    private void startKeepAliveTimer() {
        stopKeepAliveTimer();

        keepAliveTimer = new Timer("LinphoneKeepAlive");
        keepAliveTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (core != null && core.getDefaultAccount() != null) {
                    // Ensure network stays reachable (Android may reset it)
                    if (!core.isNetworkReachable()) {
                        Log.w(TAG, "Keep-alive: network was UNREACHABLE, forcing true");
                        core.setNetworkReachable(true);
                    }
                    // Refresh registration (also refreshes NAT binding)
                    core.refreshRegisters();
                    Log.d(TAG, "Keep-alive tick -> refreshRegisters, reg="
                            + core.getDefaultAccount().getState().name());
                }
            }
        }, KEEP_ALIVE_INTERVAL, KEEP_ALIVE_INTERVAL);

        Log.d(TAG, "Keep-alive timer started intervalMs=" + KEEP_ALIVE_INTERVAL);
    }

    /**
     * Stop keep-alive timer
     */
    private void stopKeepAliveTimer() {
        if (keepAliveTimer != null) {
            keepAliveTimer.cancel();
            keepAliveTimer = null;
            Log.d(TAG, "Keep-alive timer stopped");
        }
    }

    public void call(String number) {
        if (core == null) {
            Log.e(TAG, "call: Core is null, not logged in");
            sendCallError("Error", "Not logged in", "Core is null");
            return;
        }

        Log.d(TAG, "call invoked -> number=" + number + " callsNb=" + core.getCallsNb());

        // Check if we have a default account and it's registered
        Account defaultAccount = core.getDefaultAccount();
        if (defaultAccount == null) {
            Log.e(TAG, "call: No default account configured");
            sendCallError("Error", "No account", "No default account configured");
            return;
        }

        RegistrationState regState = defaultAccount.getState();
        Log.d(TAG, "call: Registration state = " + regState.name());

        if (regState != RegistrationState.Ok) {
            Log.e(TAG, "call: Not registered (state=" + regState.name() + "), cannot make call");
            sendCallError("Error", "Not registered", "Registration state: " + regState.name());
            return;
        }

        // Terminate any existing calls before starting a new one
        if (core.getCallsNb() > 0) {
            Log.d(TAG, "Terminating existing calls before starting new call");
            core.terminateAllCalls();
            // Give some time for resources to be released
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Log.e(TAG, "Sleep interrupted", e);
            }
        }

        // NOTE: Do NOT call requestAudioFocus() here!
        // The Linphone SDK's built-in CoreManager/AudioHelper manages audio
        // focus automatically when the call transitions to StreamsRunning.
        // Our competing request conflicts with the SDK's AudioHelper and can
        // cause the call to be auto-paused (held).

        String formattedNumber = String.format("sip:%s@%s", number, domain);
        Log.d(TAG, "call: Calling " + formattedNumber);
        Address remoteAddress = Factory.instance().createAddress(formattedNumber);
        if (remoteAddress == null) {
            Log.e(TAG, "call: Failed to create remote address");
            return;
        }

        CallParams params = core.createCallParams(null);
        if (params == null) {
            Log.e(TAG, "call: Failed to create call params");
            return;
        }

        Log.d(TAG, "call params -> enc=None audioOnly=true direction=sendrecv lowBandwidth=false");

        // =============================
        // CALL PARAMETERS - PRODUCTION CONFIG (Match Sipnetic)
        // =============================

        MediaEncryption enc = MediaEncryption.None;
        if ("srtp".equals(userConfig.mediaEncryption)) {
            enc = MediaEncryption.SRTP;
        } else if ("zrtp".equals(userConfig.mediaEncryption)) {
            enc = MediaEncryption.ZRTP;
        }
        params.setMediaEncryption(enc);

        // Enable audio
        params.enableAudio(true);

        // Disable video for audio-only calls
        params.enableVideo(userConfig.enableVideo);

        // Set audio direction to send and receive
        params.setAudioDirection(MediaDirection.SendRecv);

        // Low bandwidth mode for better NAT traversal
        params.enableLowBandwidth(false);

        // Start the call
        Call call = core.inviteAddressWithParams(remoteAddress, params);

        if (call == null) {
            Log.e(TAG, "call: Failed to initiate call");
            sendCallError("Error", "Call failed", "Failed to initiate call");
        } else {
            Log.d(TAG, "call: Call initiated successfully");
        }
    }

    /**
     * Request Android audio focus for VoIP call
     */
    private void requestAudioFocus() {
        if (audioManager == null)
            return;

        originalAudioMode = audioManager.getMode();
        originalSpeakerphoneState = audioManager.isSpeakerphoneOn();

        // Request audio focus
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.media.AudioFocusRequest focusRequest = new android.media.AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .build();
            result = audioManager.requestAudioFocus(focusRequest);
        } else {
            result = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
        }

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(TAG, "Audio focus granted");
            // Set audio mode for voice communication
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } else {
            Log.w(TAG, "Audio focus request failed");
        }
    }

    /**
     * Abandon audio focus when call ends
     */
    @SuppressWarnings("deprecation")
    private void abandonAudioFocus() {
        if (audioManager == null)
            return;

        // Clear communication device on API 31+ to restore default audio route
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice();
        }

        // Restore original audio state
        audioManager.setMode(originalAudioMode);
        audioManager.setSpeakerphoneOn(originalSpeakerphoneState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(
                    new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .build());
        } else {
            audioManager.abandonAudioFocus(null);
        }

        Log.d(TAG, "Audio focus abandoned");
    }

    public boolean callForward(String destination) {
        if (core == null)
            return false;
        if (core.getCallsNb() == 0)
            return false;
        Call currentCall = null;
        if (core.getCurrentCall() == null)
            return false;
        currentCall = core.getCurrentCall();
        Address address = core.interpretUrl(destination);
        if (address == null)
            return false;
        currentCall.transferTo(address);
        return true;
    }

    public String callLogs() {
        if (core == null)
            return null;
        CallLog[] logs = core.getCallLogs();
        List<CallHistory> callHistoryList = new ArrayList<>();
        callHistoryList.clear();

        for (CallLog log : logs) {
            CallHistory history = new CallHistory();
            history.setNumber(log.getToAddress().getUsername());
            history.setStatus(log.getStatus().name());
            history.setDate(log.getStartDate());
            history.setDuration(log.getDuration());
            callHistoryList.add(history);
        }
        ListCallHistory list = new ListCallHistory();
        list.setCallHistoryList(callHistoryList);
        return new Gson().toJson(list);
    }

    public void hangUp() {
        if (core == null || core.getCallsNb() == 0) {
            Log.d(TAG, "hangUp: No calls to hang up");
            return;
        }

        Call call = null;
        if (core.getCurrentCall() != null) {
            call = core.getCurrentCall();
        } else {
            call = core.getCalls()[0];
        }

        if (call == null) {
            Log.d(TAG, "hangUp: No call found");
            return;
        }

        call.terminate();
        sendCallEvent("Released");
        Log.d(TAG, "Call terminated");
    }

    public boolean toggleMute() {
        if (core == null)
            return false;
        if (core.getCurrentCall() != null) {
            boolean currentlyMuted = core.getCurrentCall().getMicrophoneMuted();
            core.getCurrentCall().setMicrophoneMuted(!currentlyMuted);
            Log.d(TAG, "toggleMute: " + (!currentlyMuted ? "muted" : "unmuted"));
            pushCallDetailsToService(core.getCurrentCall(), "In Call");
            return !currentlyMuted;
        }
        return false;
    }

    /**
     * Check if microphone is muted
     */
    public boolean isMuted() {
        if (core == null || core.getCurrentCall() == null)
            return false;
        return core.getCurrentCall().getMicrophoneMuted();
    }

    /**
     * Send DTMF tone (using RFC2833)
     */
    public void sendDtmf(char digit) {
        if (core == null || core.getCurrentCall() == null)
            return;
        core.getCurrentCall().sendDtmf(digit);
        Log.d(TAG, "DTMF sent: " + digit);
    }

    /**
     * Send DTMF string
     */
    public void sendDtmfs(String digits) {
        if (core == null || core.getCurrentCall() == null)
            return;
        core.getCurrentCall().sendDtmfs(digits);
        Log.d(TAG, "DTMFs sent: " + digits);
    }

    /**
     * Get current call duration in seconds
     */
    public int getCallDuration() {
        if (core == null || core.getCurrentCall() == null)
            return 0;
        return core.getCurrentCall().getDuration();
    }

    /**
     * Check if there's an active call
     */
    public boolean hasActiveCall() {
        return core != null && core.getCallsNb() > 0;
    }

    /**
     * Get registration state
     */
    public String getRegistrationState() {
        if (core == null || core.getDefaultAccount() == null)
            return "None";
        return core.getDefaultAccount().getState().name();
    }

    /**
     * Get comprehensive registration info for diagnostics.
     * Returns a Map suitable for passing back to Flutter as JSON.
     */
    public java.util.HashMap<String, Object> getRegistrationInfo() {
        java.util.HashMap<String, Object> info = new java.util.HashMap<>();
        info.put("coreAlive", core != null);
        if (core == null)
            return info;

        info.put("networkReachable", core.isNetworkReachable());
        info.put("callsCount", core.getCallsNb());

        Account acct = core.getDefaultAccount();
        if (acct == null) {
            info.put("registrationState", "NoAccount");
            return info;
        }
        info.put("registrationState", acct.getState().name());

        try {
            if (acct.getContactAddress() != null) {
                info.put("contactAddress", acct.getContactAddress().asString());
            }
            AccountParams p = acct.getParams();
            if (p != null) {
                if (p.getIdentityAddress() != null)
                    info.put("identity", p.getIdentityAddress().asStringUriOnly());
                if (p.getServerAddress() != null)
                    info.put("serverAddress", p.getServerAddress().asString());
                info.put("expires", p.getExpires());
            }
        } catch (Exception e) {
            info.put("error", e.getMessage());
        }

        Transports t = core.getTransports();
        info.put("udpPort", t.getUdpPort());
        info.put("tcpPort", t.getTcpPort());
        info.put("tlsPort", t.getTlsPort());

        info.put("pushEnabled", core.isPushNotificationEnabled());
        boolean keepAliveApi = false;
        try {
            keepAliveApi = core.keepAliveEnabled();
        } catch (Exception e) {
            keepAliveApi = core.getConfig().getBool("net", "keep_alive", false);
        }
        info.put("keepAliveEnabled", keepAliveApi);
        info.put("keepAlivePeriodNet", core.getConfig().getInt("net", "keepalive_period", -1));
        info.put("keepAlivePeriodSip", core.getConfig().getInt("sip", "keepalive_period", -1));

        // Show actual transports in use
        try {
            Transports used = core.getTransportsUsed();
            info.put("usedUdpPort", used.getUdpPort());
            info.put("usedTcpPort", used.getTcpPort());
            info.put("usedTlsPort", used.getTlsPort());
        } catch (Exception e) {
            info.put("transportsUsedError", e.getMessage());
        }

        // eventSink status (can listener deliver events?)
        info.put("callSinkReady", callEventListener != null && callEventListener.isReady());
        info.put("loginSinkReady", loginListener != null && loginListener.isReady());

        Log.d(TAG, "getRegistrationInfo -> " + info.toString());
        return info;
    }

    /**
     * Refresh registration manually
     */
    public void refreshRegistration() {
        if (core != null) {
            core.refreshRegisters();
            Log.d(TAG, "Registration refreshed manually, current state="
                    + (core.getDefaultAccount() != null ? core.getDefaultAccount().getState() : "no account"));
        }
    }

    /**
     * Set network reachability
     */
    public void setNetworkReachable(boolean reachable) {
        if (core != null) {
            core.setNetworkReachable(reachable);
            Log.d(TAG, "Network reachable set to: " + reachable + " regState="
                    + (core.getDefaultAccount() != null ? core.getDefaultAccount().getState() : "no account"));
        }
    }

    /**
     * Update STUN server
     */
    public void updateStunServer(String server, int port) {
        if (core == null)
            return;

        NatPolicy natPolicy = core.getNatPolicy();
        if (natPolicy != null) {
            natPolicy.setStunServer(server + ":" + port);
            Log.d(TAG, "STUN server updated to: " + server + ":" + port);
        }
    }

    public void toggleSpeaker() {
        if (core == null || core.getCurrentCall() == null) {
            Log.e(TAG, "toggleSpeaker: No active call");
            return;
        }

        Call currentCall = core.getCurrentCall();

        // Refresh audio devices so we see the latest OS-level state
        core.reloadSoundDevices();

        AudioDevice currentAudioDevice = currentCall.getOutputAudioDevice();
        boolean speakerEnabled;

        if (currentAudioDevice != null) {
            speakerEnabled = currentAudioDevice.getType() == AudioDevice.Type.Speaker;
        } else {
            // No Linphone device — infer from AudioManager
            speakerEnabled = audioManager != null && audioManager.isSpeakerphoneOn();
            Log.w(TAG, "toggleSpeaker: No current audio device, inferred speaker=" + speakerEnabled);
        }

        Log.d(TAG, "toggleSpeaker: speaker currently " + speakerEnabled + ", switching to " + (speakerEnabled ? "Earpiece" : "Speaker"));

        if (speakerEnabled) {
            applyAudioRoute(currentCall, AudioDevice.Type.Earpiece);
        } else {
            applyAudioRoute(currentCall, AudioDevice.Type.Speaker);
        }

        // Update the notification with new speaker state
        if (core.getCurrentCall() != null) {
            pushCallDetailsToService(core.getCurrentCall(), "In Call");
        }
    }

    /**
     * Central audio-route helper.
     * Routes via Linphone SDK first (which manages OS routing internally).
     * Falls back to AudioManager only when no matching SDK device exists,
     * using setCommunicationDevice() on API 31+ per Android docs.
     */
    private void applyAudioRoute(Call call, AudioDevice.Type targetType) {
        AudioDevice[] audioDevices = core.getAudioDevices();
        AudioDevice targetDevice = null;

        for (AudioDevice d : audioDevices) {
            if (d.getType() == targetType) {
                targetDevice = d;
                break;
            }
        }

        if (targetDevice != null) {
            // Let Linphone SDK handle the OS-level audio route internally.
            // Do NOT call audioManager.setSpeakerphoneOn() — it races with
            // the SDK's own AudioHelper and causes the "8 presses" bug.
            call.setOutputAudioDevice(targetDevice);
            Log.d(TAG, "applyAudioRoute: Linphone routed to " + targetDevice.getType()
                    + " (" + targetDevice.getDeviceName() + ")");
        } else {
            // Fallback: no matching Linphone device — use AudioManager directly
            Log.w(TAG, "applyAudioRoute: No Linphone device for " + targetType + ", using AudioManager fallback");
            boolean wantSpeaker = (targetType == AudioDevice.Type.Speaker);
            applyAudioManagerFallback(wantSpeaker);
        }
    }

    /**
     * AudioManager fallback: uses setCommunicationDevice() on API 31+ (per
     * https://developer.android.com/reference/android/media/AudioManager),
     * falls back to deprecated setSpeakerphoneOn() on older APIs.
     */
    @SuppressWarnings("deprecation")
    private void applyAudioManagerFallback(boolean speaker) {
        if (audioManager == null) return;

        // Ensure correct audio mode
        if (audioManager.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31+
            // Modern API: setCommunicationDevice
            if (speaker) {
                // Clear any previous communication device to let the platform
                // route to speaker (the default loud route).
                audioManager.clearCommunicationDevice();
                // Find and set the speaker device
                for (android.media.AudioDeviceInfo info : audioManager.getAvailableCommunicationDevices()) {
                    if (info.getType() == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        audioManager.setCommunicationDevice(info);
                        Log.d(TAG, "AudioManager (API31+): set communication device to speaker");
                        return;
                    }
                }
                // If no explicit speaker device, clearing is sufficient
                Log.d(TAG, "AudioManager (API31+): cleared communication device (routes to speaker)");
            } else {
                // Route to earpiece
                for (android.media.AudioDeviceInfo info : audioManager.getAvailableCommunicationDevices()) {
                    if (info.getType() == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                        audioManager.setCommunicationDevice(info);
                        Log.d(TAG, "AudioManager (API31+): set communication device to earpiece");
                        return;
                    }
                }
            }
        } else {
            // Legacy API
            audioManager.setSpeakerphoneOn(speaker);
            Log.d(TAG, "AudioManager (legacy): setSpeakerphoneOn=" + speaker);
        }
    }

    /**
     * Route audio to speaker
     */
    public void routeAudioToSpeaker() {
        if (core == null || core.getCurrentCall() == null)
            return;

        core.reloadSoundDevices();
        applyAudioRoute(core.getCurrentCall(), AudioDevice.Type.Speaker);
    }

    /**
     * Route audio to earpiece
     */
    public void routeAudioToEarpiece() {
        if (core == null || core.getCurrentCall() == null)
            return;

        core.reloadSoundDevices();
        applyAudioRoute(core.getCurrentCall(), AudioDevice.Type.Earpiece);
    }

    /**
     * Route audio to Bluetooth device if available
     */
    public boolean routeAudioToBluetooth() {
        if (core == null || core.getCurrentCall() == null)
            return false;

        Call currentCall = core.getCurrentCall();
        AudioDevice[] audioDevices = core.getAudioDevices();

        for (AudioDevice audioDevice : audioDevices) {
            if (audioDevice.getType() == AudioDevice.Type.Bluetooth) {
                currentCall.setOutputAudioDevice(audioDevice);
                currentCall.setInputAudioDevice(audioDevice);
                Log.d(TAG, "Routed audio to Bluetooth: " + audioDevice.getDeviceName());
                return true;
            }
        }

        Log.d(TAG, "No Bluetooth device available");
        return false;
    }

    /**
     * Get list of available audio devices
     */
    public String getAudioDevices() {
        if (core == null)
            return "[]";

        AudioDevice[] audioDevices = core.getAudioDevices();
        List<String> devices = new ArrayList<>();

        for (AudioDevice device : audioDevices) {
            devices.add(device.getType().name() + ":" + device.getDeviceName());
        }

        return new Gson().toJson(devices);
    }

    /**
     * Check if speaker is currently active.
     * Uses both Linphone SDK device type AND AudioManager as cross-check.
     */
    public boolean isSpeakerEnabled() {
        if (core == null || core.getCurrentCall() == null) {
            return audioManager != null && audioManager.isSpeakerphoneOn();
        }

        // Refresh so we report the actual OS state
        core.reloadSoundDevices();

        AudioDevice currentDevice = core.getCurrentCall().getOutputAudioDevice();
        if (currentDevice != null) {
            return currentDevice.getType() == AudioDevice.Type.Speaker;
        }

        // Device is null — fall back to AudioManager
        return audioManager != null && audioManager.isSpeakerphoneOn();
    }

    public void answerCall() {
        if (core == null || core.getCallsNb() == 0) {
            Log.e(TAG, "answerCall: No calls to answer");
            return;
        }

        Call call = core.getCurrentCall();
        if (call == null) {
            call = core.getCalls()[0];
        }
        if (call == null) {
            Log.e(TAG, "answerCall: Could not find call to answer");
            return;
        }

        // NOTE: Do NOT call requestAudioFocus() here!
        // The Linphone SDK's built-in CoreManager/AudioHelper already manages
        // audio focus for calls. Our competing requestAudioFocus() with
        // AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE steals focus from the SDK's
        // AudioHelper (which held it for ringing). The SDK's AudioHelper then
        // receives AUDIOFOCUS_LOSS_TRANSIENT asynchronously and tells
        // CoreManager to pause all calls — putting the call on hold immediately
        // after answering.

        CallParams params = core.createCallParams(call);
        if (params == null) {
            Log.e(TAG, "answerCall: Failed to create call params");
            return;
        }

        // =============================
        // ANSWER CALL PARAMETERS (Match Sipnetic)
        // =============================
        params.enableAudio(true);
        params.enableVideo(userConfig.enableVideo);
        params.setAudioDirection(MediaDirection.SendRecv);

        MediaEncryption enc = MediaEncryption.None;
        if ("srtp".equals(userConfig.mediaEncryption)) {
            enc = MediaEncryption.SRTP;
        } else if ("zrtp".equals(userConfig.mediaEncryption)) {
            enc = MediaEncryption.ZRTP;
        }
        params.setMediaEncryption(enc);

        call.acceptWithParams(params);

        // Ensure audio routing is correct after answering
        mainHandler.postDelayed(() -> {
            ensureAudioRouting();
        }, 500);

        // NOTE: Do NOT send a synthetic "CallAnswered" event here.
        // The Linphone core will fire onCallStateChanged with Connected
        // and then StreamsRunning, which the Flutter side already handles.
        // Sending an unrecognised event caused the Dart listener to emit
        // CallState.idle, which made the UI show a blank/black screen.
        Log.d(TAG, "Call answered successfully (waiting for Connected state from core)");
    }

    /**
     * Ensure proper audio routing after call is established
     * This fixes one-way audio issues
     */
    private void ensureAudioRouting() {
        if (core == null || core.getCurrentCall() == null)
            return;

        Call currentCall = core.getCurrentCall();

        // NOTE: Do NOT set MODE_IN_COMMUNICATION here.
        // The SDK's AudioHelper already sets this mode when audio focus is
        // granted for the call. Setting it again here is redundant and
        // can interfere with the SDK's audio routing.

        // Get current output device
        AudioDevice outputDevice = currentCall.getOutputAudioDevice();
        AudioDevice inputDevice = currentCall.getInputAudioDevice();

        Log.d(TAG, "Current output device: " + (outputDevice != null ? outputDevice.getType() : "null"));
        Log.d(TAG, "Current input device: " + (inputDevice != null ? inputDevice.getType() : "null"));

        // If no output device set, try to set one
        if (outputDevice == null) {
            AudioDevice[] devices = core.getAudioDevices();
            for (AudioDevice device : devices) {
                if (device.getType() == AudioDevice.Type.Earpiece) {
                    currentCall.setOutputAudioDevice(device);
                    currentCall.setInputAudioDevice(device);
                    Log.d(TAG, "Set default audio device to Earpiece");
                    break;
                }
            }
        }

        // Reload sound devices to ensure proper initialization
        core.reloadSoundDevices();
    }

    public void rejectCall() {
        if (core.getCallsNb() == 0)
            return;
        Call call = core.getCurrentCall();
        if (call == null)
            call = core.getCalls()[0];
        if (call == null)
            return;
        call.terminate();
        sendCallEvent("CallRejected");
    }

    public void removeLoginListener() {
        loginListener = null;
        Log.d(TAG, "Login listener disconnected (core continues running in service)");
    }

    public void removeCallListener() {
        callEventListener = null;
        Log.d(TAG, "Call listener disconnected (core continues running in service)");
    }

    /**
     * Logout and cleanup
     */
    public void logout() {
        if (core == null)
            return;

        stopKeepAliveTimer();
        SipForegroundService.stop(context);

        // Terminate all calls
        core.terminateAllCalls();

        // Clear accounts
        for (Account account : core.getAccountList()) {
            core.removeAccount(account);
        }

        // Clear auth info
        core.clearAllAuthInfo();

        // Remove listener and stop core
        core.removeListener(coreListener);
        core.stop();
        core = null;

        Log.d(TAG, "Logged out and cleaned up");
    }

    /**
     * Get current call state as a string for native Activities.
     * Returns "none" if no active call.
     */
    public String getCurrentCallStatus() {
        if (core == null || core.getCallsNb() == 0)
            return "none";
        Call call = core.getCurrentCall();
        if (call == null && core.getCalls().length > 0)
            call = core.getCalls()[0];
        if (call == null)
            return "none";
        return call.getState().name();
    }

    /**
     * Get the remote party's display name for the current call.
     */
    public String getCurrentCallRemoteName() {
        if (core == null || core.getCallsNb() == 0)
            return "";
        Call call = core.getCurrentCall();
        if (call == null && core.getCalls().length > 0)
            call = core.getCalls()[0];
        if (call == null)
            return "";
        String name = call.getRemoteAddress().getDisplayName();
        if (name == null || name.isEmpty())
            name = call.getRemoteAddress().getUsername();
        return name != null ? name : "";
    }

    /**
     * Get Core instance (for advanced operations)
     */
    public static Core getCore() {
        return core;
    }

    CoreListener coreListener = new CoreListenerStub() {
        @Override
        public void onAccountRegistrationStateChanged(@NonNull Core core, @NonNull Account account,
                RegistrationState state, @NonNull String message) {
            Log.d(TAG, "Registration state changed: " + state.name() + " - " + message);
            SipForegroundService.updateRegistrationState(state.name());
            sendLoginEvent(state.name());
            broadcastCallData();
        }

        @Override
        public void onCallStateChanged(@NonNull Core core, @NonNull Call call, Call.State state,
                @NonNull String message) {

            Log.d(TAG, "Call state changed: " + state.name() + " - " + message);

            switch (state) {
                case IncomingReceived:
                    Log.d(TAG, ">>> INCOMING CALL DETECTED <<<");
                    Log.d(TAG, ">>> From: " + call.getRemoteAddress().asStringUriOnly());
                    Log.d(TAG, ">>> CallId: " + (call.getCallLog() != null ? call.getCallLog().getCallId() : "?"));
                    Log.d(TAG, ">>> Direction: " + call.getDir().name());
                // Show incoming call via native Activity (full-screen intent)
                {
                    String callerUri = call.getRemoteAddress().asStringUriOnly();
                    String callerDisplay = call.getRemoteAddress().getDisplayName();
                    if (callerDisplay == null || callerDisplay.isEmpty()) {
                        callerDisplay = call.getRemoteAddress().getUsername();
                    }
                    SipForegroundService.showIncomingCall(context, callerUri,
                            callerDisplay != null ? callerDisplay : callerUri);
                }
                    sendCallEvent(state.name());
                    broadcastCallData();
                    Log.d(TAG, ">>> IncomingReceived event sent");
                    break;

                case PushIncomingReceived:
                    Log.d(TAG, ">>> PUSH INCOMING CALL DETECTED <<<");
                    Log.d(TAG, ">>> From: " + call.getRemoteAddress().asStringUriOnly()); {
                    String pushCallerUri = call.getRemoteAddress().asStringUriOnly();
                    String pushCallerDisplay = call.getRemoteAddress().getDisplayName();
                    if (pushCallerDisplay == null || pushCallerDisplay.isEmpty()) {
                        pushCallerDisplay = call.getRemoteAddress().getUsername();
                    }
                    SipForegroundService.showIncomingCall(context, pushCallerUri,
                            pushCallerDisplay != null ? pushCallerDisplay : pushCallerUri);
                }
                    sendCallEvent("IncomingReceived");
                    broadcastCallData();
                    Log.d(TAG, ">>> PushIncomingReceived event sent");
                    break;

                case IncomingEarlyMedia:
                    Log.d(TAG, "Incoming early media");
                    sendCallEvent(state.name());
                    broadcastCallData();
                    break;

                case OutgoingInit:
                    Log.d(TAG, "Outgoing call initiated"); {
                    String outUri = call.getRemoteAddress().asStringUriOnly();
                    String outName = call.getRemoteAddress().getDisplayName();
                    if (outName == null || outName.isEmpty()) {
                        outName = call.getRemoteAddress().getUsername();
                    }
                    SipForegroundService.launchActiveCallScreen(context,
                            outName != null ? outName : outUri, outUri);
                }
                    pushCallDetailsToService(call, "Dialing");
                    sendCallEvent(state.name());
                    break;

                case OutgoingProgress:
                    Log.d(TAG, "Outgoing call progress");
                    pushCallDetailsToService(call, "Calling…");
                    sendCallEvent(state.name());
                    break;

                case OutgoingRinging:
                    Log.d(TAG, "Remote party ringing");
                    pushCallDetailsToService(call, "Ringing");
                    sendCallEvent(state.name());
                    break;

                case Connected:
                    Log.d(TAG, "Call connected");
                    IncomingCallActivity.finishIfRunning();
                    SipForegroundService.startCallMode(context);
                    killSdkCoreServiceNotifications();
                    pushCallDetailsToService(call, "Connected");
                    sendCallEvent(state.name());
                    break;

                case StreamsRunning:
                    Log.d(TAG, "Media streams running");
                    mainHandler.post(() -> {
                        ensureAudioRouting();
                        logAudioStreamInfo(call);
                    });
                    // Purge any SDK-created keep-alive notifications that
                    // appear when streams become active
                    killSdkCoreServiceNotifications();
                    pushCallDetailsToService(call, "In Call");
                    sendCallEvent(state.name());
                    break;

                case Paused:
                    Log.d(TAG, "Call paused");
                    pushCallDetailsToService(call, "On Hold");
                    sendCallEvent(state.name());
                    break;

                case PausedByRemote:
                    Log.d(TAG, "Call paused by remote");
                    pushCallDetailsToService(call, "Held by Remote");
                    sendCallEvent(state.name());
                    break;

                case Updating:
                    Log.d(TAG, "Call updating");
                    sendCallEvent(state.name());
                    broadcastCallData();
                    break;

                case UpdatedByRemote:
                    Log.d(TAG, "Call updated by remote");
                    sendCallEvent(state.name());
                    broadcastCallData();
                    break;

                case Released:
                    Log.d(TAG, "Call released");
                    SipForegroundService.stopCallMode(context);
                    IncomingCallActivity.finishIfRunning();
                    ActiveCallActivity.finishIfRunning();
                    sendCallEvent(state.name());
                    broadcastCallData();
                    break;

                case End:
                    Log.d(TAG, "Call ended - Reason: " + call.getReason().name());
                    SipForegroundService.stopCallMode(context);
                    IncomingCallActivity.finishIfRunning();
                    ActiveCallActivity.finishIfRunning();
                    sendCallEvent(state.name());
                    broadcastCallData();
                    break;

                case EarlyUpdatedByRemote:
                    Log.d(TAG, "Early update by remote");
                    sendCallEvent(state.name());
                    broadcastCallData();
                    break;

                case Error:
                    Log.e(TAG, "Call error: " + message + " - Reason: " + call.getReason().name());
                    SipForegroundService.stopCallMode(context);
                    IncomingCallActivity.finishIfRunning();
                    ActiveCallActivity.finishIfRunning();
                    sendCallEvent(state.name());
                    broadcastCallData();
                    break;

                default:
                    Log.d(TAG, "Other call state: " + state.name() + " - " + message);
                    break;
            }
        }

        @Override
        public void onAudioDevicesListUpdated(@NonNull Core core) {
            Log.d(TAG, "Audio devices list updated");
            AudioDevice[] devices = core.getAudioDevices();
            for (AudioDevice device : devices) {
                Log.d(TAG, "Available audio device: " + device.getType().name() + " - " + device.getDeviceName());
            }
        }

        @Override
        public void onNetworkReachable(@NonNull Core core, boolean reachable) {
            Log.d(TAG, "Network reachable: " + reachable);
            if (reachable && core.getDefaultAccount() != null) {
                // Refresh registration when network becomes reachable
                core.refreshRegisters();
            }
        }

        @Override
        public void onGlobalStateChanged(@NonNull Core core,
                org.linphone.core.GlobalState state, @NonNull String message) {
            Log.d(TAG, "[GLOBAL] state=" + state.name() + " msg=" + message);
        }
    };

    /**
     * Log audio stream information for debugging
     */
    private void logAudioStreamInfo(Call call) {
        if (call == null)
            return;

        try {
            Log.d(TAG, "=== Audio Stream Info ===");
            Log.d(TAG, "Call direction: " + call.getDir().name());
            Log.d(TAG,
                    "Audio codec: " + (call.getCurrentParams().getUsedAudioPayloadType() != null
                            ? call.getCurrentParams().getUsedAudioPayloadType().getMimeType()
                            : "unknown"));
            Log.d(TAG, "Audio stats - Upload: " + call.getAudioStats().getUploadBandwidth() +
                    " kbps, Download: " + call.getAudioStats().getDownloadBandwidth() + " kbps");

            AudioDevice outputDevice = call.getOutputAudioDevice();
            AudioDevice inputDevice = call.getInputAudioDevice();
            Log.d(TAG, "Output device: " + (outputDevice != null ? outputDevice.getDeviceName() : "none"));
            Log.d(TAG, "Input device: " + (inputDevice != null ? inputDevice.getDeviceName() : "none"));
            Log.d(TAG, "========================");
        } catch (Exception e) {
            Log.e(TAG, "Error logging audio stream info", e);
        }
    }

    /**
     * Copy bundled Let's Encrypt root CA certificates to app files directory
     * 
     * @return Path to the CA file, or null if failed
     */
    private String copyRootCaToFiles() {
        try {
            // Get resource ID for the PEM file
            int resId = context.getResources().getIdentifier("letsencrypt_roots", "raw", context.getPackageName());
            if (resId == 0) {
                Log.w(TAG, "Let's Encrypt root CA resource not found");
                return null;
            }

            // Copy to files directory
            java.io.File outputFile = new java.io.File(context.getFilesDir(), "letsencrypt_roots.pem");

            // Only copy if file doesn't exist or is outdated
            if (!outputFile.exists()) {
                java.io.InputStream inputStream = context.getResources().openRawResource(resId);
                java.io.FileOutputStream outputStream = new java.io.FileOutputStream(outputFile);

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }

                outputStream.close();
                inputStream.close();
                Log.d(TAG, "Copied Let's Encrypt root CA to: " + outputFile.getAbsolutePath());
            }

            return outputFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy root CA file", e);
            return null;
        }
    }

    /**
     * Apply runtime configuration coming from Flutter UI and persist it.
     */
    public void applyConfig(Map<String, Object> configMap) {
        if (configMap == null) {
            Log.w(TAG, "applyConfig: received null config map");
            return;
        }

        UserConfig updated = userConfig.copy();

        if (configMap.containsKey("userAgentName")) {
            updated.userAgentName = (String) configMap.get("userAgentName");
        }
        if (configMap.containsKey("userAgentVersion")) {
            updated.userAgentVersion = (String) configMap.get("userAgentVersion");
        }

        if (configMap.containsKey("stunServer")) {
            updated.stunServer = (String) configMap.get("stunServer");
        }
        if (configMap.containsKey("stunPort")) {
            updated.stunPort = asInt(configMap.get("stunPort"), updated.stunPort);
        }
        if (configMap.containsKey("enableStun")) {
            updated.enableStun = asBool(configMap.get("enableStun"), updated.enableStun);
        }
        if (configMap.containsKey("enableIce")) {
            updated.enableIce = asBool(configMap.get("enableIce"), updated.enableIce);
        }
        if (configMap.containsKey("enableTurn")) {
            updated.enableTurn = asBool(configMap.get("enableTurn"), updated.enableTurn);
        }
        if (configMap.containsKey("enableUpnp")) {
            updated.enableUpnp = asBool(configMap.get("enableUpnp"), updated.enableUpnp);
        }

        if (configMap.containsKey("mediaEncryption")) {
            updated.mediaEncryption = ((String) configMap.get("mediaEncryption")).toLowerCase();
        }

        if (configMap.containsKey("rtpStartPort")) {
            updated.rtpStartPort = asInt(configMap.get("rtpStartPort"), updated.rtpStartPort);
        }
        if (configMap.containsKey("rtpEndPort")) {
            updated.rtpEndPort = asInt(configMap.get("rtpEndPort"), updated.rtpEndPort);
        }
        if (configMap.containsKey("enableAvpf")) {
            updated.enableAvpf = asBool(configMap.get("enableAvpf"), updated.enableAvpf);
        }
        if (configMap.containsKey("symmetricRtp")) {
            updated.symmetricRtp = asBool(configMap.get("symmetricRtp"), updated.symmetricRtp);
        }
        if (configMap.containsKey("adaptiveRateControl")) {
            updated.adaptiveRateControl = asBool(configMap.get("adaptiveRateControl"), updated.adaptiveRateControl);
        }
        if (configMap.containsKey("jitterBufferMin")) {
            updated.jitterBufferMin = asInt(configMap.get("jitterBufferMin"), updated.jitterBufferMin);
        }
        if (configMap.containsKey("jitterBuffer")) {
            updated.jitterBuffer = asInt(configMap.get("jitterBuffer"), updated.jitterBuffer);
        }

        if (configMap.containsKey("echoCancellation")) {
            updated.echoCancellation = asBool(configMap.get("echoCancellation"), updated.echoCancellation);
        }
        if (configMap.containsKey("echoLimiter")) {
            updated.echoLimiter = asBool(configMap.get("echoLimiter"), updated.echoLimiter);
        }
        if (configMap.containsKey("agc")) {
            updated.agc = asBool(configMap.get("agc"), updated.agc);
        }
        if (configMap.containsKey("noiseGate")) {
            updated.noiseGate = asBool(configMap.get("noiseGate"), updated.noiseGate);
        }
        if (configMap.containsKey("noiseGateThreshold")) {
            updated.noiseGateThreshold = asFloat(configMap.get("noiseGateThreshold"), updated.noiseGateThreshold);
        }

        if (configMap.containsKey("enableVideo")) {
            updated.enableVideo = asBool(configMap.get("enableVideo"), updated.enableVideo);
        }
        if (configMap.containsKey("useRfc2833")) {
            updated.useRfc2833 = asBool(configMap.get("useRfc2833"), updated.useRfc2833);
        }
        if (configMap.containsKey("useInfoDtmf")) {
            updated.useInfoDtmf = asBool(configMap.get("useInfoDtmf"), updated.useInfoDtmf);
        }

        if (configMap.containsKey("sendEarlyMedia")) {
            updated.sendEarlyMedia = asBool(configMap.get("sendEarlyMedia"), updated.sendEarlyMedia);
        }
        if (configMap.containsKey("incomingEarlyMedia")) {
            updated.incomingEarlyMedia = asBool(configMap.get("incomingEarlyMedia"), updated.incomingEarlyMedia);
        }
        if (configMap.containsKey("use100rel")) {
            updated.use100rel = asBool(configMap.get("use100rel"), updated.use100rel);
        }
        if (configMap.containsKey("useOutbound")) {
            updated.useOutbound = asBool(configMap.get("useOutbound"), updated.useOutbound);
        }
        if (configMap.containsKey("useGruu")) {
            updated.useGruu = asBool(configMap.get("useGruu"), updated.useGruu);
        }
        if (configMap.containsKey("useUpdate")) {
            updated.useUpdate = asBool(configMap.get("useUpdate"), updated.useUpdate);
        }
        if (configMap.containsKey("useRport")) {
            updated.useRport = asBool(configMap.get("useRport"), updated.useRport);
        }
        if (configMap.containsKey("sendInitialOffer")) {
            updated.sendInitialOffer = asBool(configMap.get("sendInitialOffer"), updated.sendInitialOffer);
        }
        if (configMap.containsKey("delayOffer")) {
            updated.delayOffer = asBool(configMap.get("delayOffer"), updated.delayOffer);
        }
        if (configMap.containsKey("usePai")) {
            updated.usePai = asBool(configMap.get("usePai"), updated.usePai);
        }
        if (configMap.containsKey("contactRewrite")) {
            updated.contactRewrite = asBool(configMap.get("contactRewrite"), updated.contactRewrite);
        }
        if (configMap.containsKey("guessHostname")) {
            updated.guessHostname = asBool(configMap.get("guessHostname"), updated.guessHostname);
        }

        if (configMap.containsKey("enabledCodecs")) {
            Object codecsObj = configMap.get("enabledCodecs");
            if (codecsObj instanceof List) {
                updated.enabledCodecs = new ArrayList<>();
                for (Object c : ((List<?>) codecsObj)) {
                    if (c != null) {
                        updated.enabledCodecs.add(c.toString().toLowerCase());
                    }
                }
            }
        }

        userConfig = updated;
        saveUserConfig();
        Log.d(TAG, "applyConfig: updated config -> " + new Gson().toJson(userConfig));
    }

    /**
     * Export current configuration as a serializable map for Flutter UI.
     */
    public Map<String, Object> exportConfig() {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("configVersion", userConfig.configVersion);
        map.put("userAgentName", userConfig.userAgentName);
        map.put("userAgentVersion", userConfig.userAgentVersion);
        map.put("stunServer", userConfig.stunServer);
        map.put("stunPort", userConfig.stunPort);
        map.put("enableStun", userConfig.enableStun);
        map.put("enableIce", userConfig.enableIce);
        map.put("enableTurn", userConfig.enableTurn);
        map.put("enableUpnp", userConfig.enableUpnp);
        map.put("mediaEncryption", userConfig.mediaEncryption);
        map.put("rtpStartPort", userConfig.rtpStartPort);
        map.put("rtpEndPort", userConfig.rtpEndPort);
        map.put("enableAvpf", userConfig.enableAvpf);
        map.put("symmetricRtp", userConfig.symmetricRtp);
        map.put("adaptiveRateControl", userConfig.adaptiveRateControl);
        map.put("jitterBufferMin", userConfig.jitterBufferMin);
        map.put("jitterBuffer", userConfig.jitterBuffer);
        map.put("echoCancellation", userConfig.echoCancellation);
        map.put("echoLimiter", userConfig.echoLimiter);
        map.put("agc", userConfig.agc);
        map.put("noiseGate", userConfig.noiseGate);
        map.put("noiseGateThreshold", userConfig.noiseGateThreshold);
        map.put("enableVideo", userConfig.enableVideo);
        map.put("useRfc2833", userConfig.useRfc2833);
        map.put("useInfoDtmf", userConfig.useInfoDtmf);
        map.put("sendEarlyMedia", userConfig.sendEarlyMedia);
        map.put("incomingEarlyMedia", userConfig.incomingEarlyMedia);
        map.put("use100rel", userConfig.use100rel);
        map.put("useOutbound", userConfig.useOutbound);
        map.put("useGruu", userConfig.useGruu);
        map.put("useUpdate", userConfig.useUpdate);
        map.put("useRport", userConfig.useRport);
        map.put("sendInitialOffer", userConfig.sendInitialOffer);
        map.put("delayOffer", userConfig.delayOffer);
        map.put("usePai", userConfig.usePai);
        map.put("contactRewrite", userConfig.contactRewrite);
        map.put("guessHostname", userConfig.guessHostname);
        map.put("enabledCodecs", new ArrayList<>(userConfig.enabledCodecs));
        return map;
    }

    private UserConfig loadUserConfig() {
        try {
            String raw = preferences.getString(PREF_KEY_CONFIG, null);
            if (raw != null) {
                UserConfig loaded = new Gson().fromJson(raw, UserConfig.class);
                if (loaded.configVersion < UserConfig.CURRENT_CONFIG_VERSION) {
                    Log.w(TAG, "loadUserConfig: stored version " + loaded.configVersion
                            + " < current " + UserConfig.CURRENT_CONFIG_VERSION
                            + ", resetting to defaults (codec/MTU fix)");
                    UserConfig fresh = UserConfig.defaultConfig();
                    saveUserConfig();
                    return fresh;
                }
                return loaded;
            }
        } catch (Exception e) {
            Log.w(TAG, "loadUserConfig: failed to parse stored config", e);
        }
        return UserConfig.defaultConfig();
    }

    private void saveUserConfig() {
        try {
            preferences.edit().putString(PREF_KEY_CONFIG, new Gson().toJson(userConfig)).apply();
            Log.d(TAG, "saveUserConfig: persisted");
        } catch (Exception e) {
            Log.e(TAG, "saveUserConfig: failed", e);
        }
    }

    private boolean asBool(Object value, boolean fallback) {
        if (value instanceof Boolean)
            return (Boolean) value;
        return fallback;
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number)
            return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private float asFloat(Object value, float fallback) {
        if (value instanceof Number)
            return ((Number) value).floatValue();
        try {
            return Float.parseFloat(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Holds user-configurable settings that can be edited from Flutter UI.
     */
    static class UserConfig {
        // Config version for migration – bump when defaults change
        static final int CURRENT_CONFIG_VERSION = 2;
        int configVersion = CURRENT_CONFIG_VERSION;

        String userAgentName = "Hatif";
        String userAgentVersion = "Android";
        String stunServer = DEFAULT_STUN_SERVER;
        int stunPort = DEFAULT_STUN_PORT;
        boolean enableStun = true;
        boolean enableIce = false;
        boolean enableTurn = false;
        boolean enableUpnp = false;
        String mediaEncryption = "none"; // none | srtp | zrtp
        int rtpStartPort = 16384;
        int rtpEndPort = 65535;
        boolean enableAvpf = false;
        boolean symmetricRtp = true;
        boolean adaptiveRateControl = false;
        int jitterBufferMin = 40;
        int jitterBuffer = 60;
        boolean echoCancellation = true;
        boolean echoLimiter = true;
        boolean agc = true;
        boolean noiseGate = true;
        float noiseGateThreshold = -50.0f;
        boolean enableVideo = false;
        boolean useRfc2833 = true;
        boolean useInfoDtmf = false;
        boolean sendEarlyMedia = false;
        boolean incomingEarlyMedia = false;
        boolean use100rel = true;
        boolean useOutbound = true;
        boolean useGruu = true;
        boolean useUpdate = true;
        boolean useRport = true;
        boolean sendInitialOffer = true;
        boolean delayOffer = false;
        boolean usePai = true;
        boolean contactRewrite = true;
        boolean guessHostname = true;
        // Speex removed from defaults: its 3 sample-rate variants + 3 matching
        // telephone-event entries bloated the SDP by ~250 bytes, pushing the
        // authenticated INVITE over the UDP MTU (1500) and causing timeout.
        List<String> enabledCodecs = new ArrayList<>(Arrays.asList(
                "opus",
                "g722",
                "pcma",
                "pcmu",
                "gsm",
                "g729"));

        static UserConfig defaultConfig() {
            return new UserConfig();
        }

        UserConfig copy() {
            UserConfig c = new UserConfig();
            c.configVersion = this.configVersion;
            c.userAgentName = this.userAgentName;
            c.userAgentVersion = this.userAgentVersion;
            c.stunServer = this.stunServer;
            c.stunPort = this.stunPort;
            c.enableStun = this.enableStun;
            c.enableIce = this.enableIce;
            c.enableTurn = this.enableTurn;
            c.enableUpnp = this.enableUpnp;
            c.mediaEncryption = this.mediaEncryption;
            c.rtpStartPort = this.rtpStartPort;
            c.rtpEndPort = this.rtpEndPort;
            c.enableAvpf = this.enableAvpf;
            c.symmetricRtp = this.symmetricRtp;
            c.adaptiveRateControl = this.adaptiveRateControl;
            c.jitterBufferMin = this.jitterBufferMin;
            c.jitterBuffer = this.jitterBuffer;
            c.echoCancellation = this.echoCancellation;
            c.echoLimiter = this.echoLimiter;
            c.agc = this.agc;
            c.noiseGate = this.noiseGate;
            c.noiseGateThreshold = this.noiseGateThreshold;
            c.enableVideo = this.enableVideo;
            c.useRfc2833 = this.useRfc2833;
            c.useInfoDtmf = this.useInfoDtmf;
            c.sendEarlyMedia = this.sendEarlyMedia;
            c.incomingEarlyMedia = this.incomingEarlyMedia;
            c.use100rel = this.use100rel;
            c.useOutbound = this.useOutbound;
            c.useGruu = this.useGruu;
            c.useUpdate = this.useUpdate;
            c.useRport = this.useRport;
            c.sendInitialOffer = this.sendInitialOffer;
            c.delayOffer = this.delayOffer;
            c.usePai = this.usePai;
            c.contactRewrite = this.contactRewrite;
            c.guessHostname = this.guessHostname;
            c.enabledCodecs = new ArrayList<>(this.enabledCodecs);
            return c;
        }
    }
}
