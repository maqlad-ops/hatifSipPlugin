package com.spagreen.linphonesdk;

import android.Manifest;
import android.app.Activity;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Map;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Method Channel Handler for Linphone SDK
 * Handles all Flutter method calls and routes them to LinPhoneHelper
 *
 * NOTE: This class must NOT extend FlutterActivity. It is not an Activity —
 * it is a plain handler object. Extending FlutterActivity previously caused
 * a black screen when answering calls because the Linphone SDK could
 * accidentally use this uninitialised Activity for display operations.
 */
public class MethodChannelHandler implements MethodChannel.MethodCallHandler {
    private final String TAG = MethodChannelHandler.class.getSimpleName();
    private LinPhoneHelper linPhoneHelper;
    private Activity activity;

    public MethodChannelHandler(Activity activity, LinPhoneHelper linPhoneHelper) {
        this.linPhoneHelper = linPhoneHelper;
        this.activity = activity;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        Log.d(TAG, "onMethodCall -> " + call.method + " | args=" + call.arguments);
        switch (call.method) {

            case "login":
                handleLogin(call, result);
                break;

            case "loginWithTransport":
                handleLoginWithTransport(call, result);
                break;

            case "logout":
                Log.d(TAG, "logout requested");
                linPhoneHelper.logout();
                result.success(true);
                break;

            case "remove_listener":
                Log.d(TAG, "remove_listener requested");
                linPhoneHelper.removeLoginListener();
                result.success(true);
                break;

            case "remove_call_listener":
                Log.d(TAG, "remove_call_listener requested");
                linPhoneHelper.removeCallListener();
                result.success(true);
                break;

            case "hangUp":
                Log.d(TAG, "hangUp requested");
                linPhoneHelper.hangUp();
                result.success(true);
                break;

            case "mute":
                Log.d(TAG, "toggle mute requested");
                boolean isMuted = linPhoneHelper.toggleMute();
                result.success(isMuted);
                break;

            case "isMuted":
                Log.d(TAG, "isMuted query");
                result.success(linPhoneHelper.isMuted());
                break;

            case "hold_call":
                Log.d(TAG, "hold_call requested");
                result.success(linPhoneHelper.holdCall());
                break;

            case "resume_call":
                Log.d(TAG, "resume_call requested");
                result.success(linPhoneHelper.resumeCall());
                break;

            case "toggle_hold":
                Log.d(TAG, "toggle_hold requested");
                boolean isOnHold = linPhoneHelper.toggleHold();
                result.success(isOnHold);
                break;

            case "is_on_hold":
                Log.d(TAG, "is_on_hold query");
                result.success(linPhoneHelper.isCallOnHold());
                break;

            case "call":
                Map callData = (Map) call.arguments;
                String number = (String) callData.get("number");
                Log.d(TAG, "call -> number=" + number);
                linPhoneHelper.call(number);
                result.success(true);
                break;

            case "transfer":
                Map destinationMap = (Map) call.arguments;
                String destination = (String) destinationMap.get("destination");
                Log.d(TAG, "transfer -> dest=" + destination);
                boolean isTransferred = linPhoneHelper.callForward(destination);
                result.success(isTransferred);
                break;

            case "blind_transfer":
                Map blindData = (Map) call.arguments;
                String blindDest = (String) blindData.get("destination");
                Log.d(TAG, "blind_transfer -> dest=" + blindDest);
                boolean blindOk = linPhoneHelper.blindTransfer(blindDest);
                result.success(blindOk);
                break;

            case "attended_transfer":
                Map attData = (Map) call.arguments;
                String attDest = (String) attData.get("destination");
                Log.d(TAG, "attended_transfer -> dest=" + attDest);
                boolean attOk = linPhoneHelper.attendedTransfer(attDest);
                result.success(attOk);
                break;

            case "complete_attended_transfer":
                Log.d(TAG, "complete_attended_transfer requested");
                boolean completeOk = linPhoneHelper.completeAttendedTransfer();
                result.success(completeOk);
                break;

            case "cancel_attended_transfer":
                Log.d(TAG, "cancel_attended_transfer requested");
                boolean cancelOk = linPhoneHelper.cancelAttendedTransfer();
                result.success(cancelOk);
                break;

            case "get_call_count":
                Log.d(TAG, "get_call_count query");
                result.success(linPhoneHelper.getCallCount());
                break;

            case "is_consult_connected":
                Log.d(TAG, "is_consult_connected query");
                result.success(linPhoneHelper.isConsultCallConnected());
                break;

            case "toggle_speaker":
                Log.d(TAG, "toggle_speaker requested");
                linPhoneHelper.toggleSpeaker();
                result.success(true);
                break;

            case "route_to_speaker":
                Log.d(TAG, "route_to_speaker requested");
                linPhoneHelper.routeAudioToSpeaker();
                result.success(true);
                break;

            case "route_to_earpiece":
                Log.d(TAG, "route_to_earpiece requested");
                linPhoneHelper.routeAudioToEarpiece();
                result.success(true);
                break;

            case "route_to_bluetooth":
                Log.d(TAG, "route_to_bluetooth requested");
                boolean btSuccess = linPhoneHelper.routeAudioToBluetooth();
                result.success(btSuccess);
                break;

            case "is_speaker_enabled":
                Log.d(TAG, "is_speaker_enabled query");
                result.success(linPhoneHelper.isSpeakerEnabled());
                break;

            case "get_audio_devices":
                Log.d(TAG, "get_audio_devices query");
                result.success(linPhoneHelper.getAudioDevices());
                break;

            case "call_logs":
                Log.d(TAG, "call_logs requested");
                String list = linPhoneHelper.callLogs();
                result.success(list);
                break;

            case "request_permissions":
                Log.d(TAG, "request_permissions invoked");
                handlePermissionRequest(result);
                break;

            case "answerCall":
                Log.d(TAG, "answerCall requested");
                linPhoneHelper.answerCall();
                result.success(true);
                break;

            case "rejectCall":
                Log.d(TAG, "rejectCall requested");
                linPhoneHelper.rejectCall();
                result.success(true);
                break;

            case "send_dtmf":
                Map dtmfData = (Map) call.arguments;
                String digit = (String) dtmfData.get("digit");
                Log.d(TAG, "send_dtmf -> " + digit);
                if (digit != null && !digit.isEmpty()) {
                    linPhoneHelper.sendDtmf(digit.charAt(0));
                }
                result.success(true);
                break;

            case "send_dtmfs":
                Map dtmfsData = (Map) call.arguments;
                String digits = (String) dtmfsData.get("digits");
                Log.d(TAG, "send_dtmfs -> " + digits);
                if (digits != null) {
                    linPhoneHelper.sendDtmfs(digits);
                }
                result.success(true);
                break;

            case "get_call_duration":
                Log.d(TAG, "get_call_duration query");
                result.success(linPhoneHelper.getCallDuration());
                break;

            case "has_active_call":
                Log.d(TAG, "has_active_call query");
                result.success(linPhoneHelper.hasActiveCall());
                break;

            case "get_registration_state":
                Log.d(TAG, "get_registration_state query");
                result.success(linPhoneHelper.getRegistrationState());
                break;

            case "refresh_registration":
                Log.d(TAG, "refresh_registration requested");
                linPhoneHelper.refreshRegistration();
                result.success(true);
                break;

            case "set_network_reachable":
                Map networkData = (Map) call.arguments;
                Boolean reachable = (Boolean) networkData.get("reachable");
                Log.d(TAG, "set_network_reachable -> " + reachable);
                linPhoneHelper.setNetworkReachable(reachable != null && reachable);
                result.success(true);
                break;

            case "update_stun_server":
                Map stunData = (Map) call.arguments;
                String server = (String) stunData.get("server");
                Integer port = (Integer) stunData.get("port");
                Log.d(TAG, "update_stun_server -> server=" + server + ":" + port);
                linPhoneHelper.updateStunServer(server, port != null ? port : 3478);
                result.success(true);
                break;

            case "apply_config":
                Map configData = (Map) call.arguments;
                Log.d(TAG, "apply_config -> " + configData);
                linPhoneHelper.applyConfig(configData);
                result.success(true);
                break;

            case "get_config":
                Log.d(TAG, "get_config requested");
                result.success(linPhoneHelper.exportConfig());
                break;

            case "get_registration_info":
                Log.d(TAG, "get_registration_info requested");
                result.success(linPhoneHelper.getRegistrationInfo());
                break;

            case "get_call_data":
                Log.d(TAG, "get_call_data requested");
                result.success(linPhoneHelper.getCallDataJson());
                break;

            case "start_service":
                Log.d(TAG, "start_service requested");
                SipForegroundService.start(activity);
                result.success(true);
                break;

            case "stop_service":
                Log.d(TAG, "stop_service requested");
                SipForegroundService.stopServiceCompletely(activity);
                result.success(true);
                break;

            default:
                result.notImplemented();
                break;
        }
    }

    /**
     * Handle login with default UDP transport
     */
    private void handleLogin(MethodCall call, MethodChannel.Result result) {
        Map data = (Map) call.arguments;
        String userName = (String) data.get("userName");
        String domain = (String) data.get("domain");
        String password = (String) data.get("password");
        Log.d(TAG, "handleLogin (UDP default) user=" + userName + " domain=" + domain);

        // Store credentials and delegate login to the service so registration
        // survives app termination.
        SipForegroundService.loginViaService(activity, userName, domain, password, "udp");
        result.success("Success");
    }

    /**
     * Handle login with specified transport type
     * Transport options: "udp", "tcp", "tls"
     */
    private void handleLoginWithTransport(MethodCall call, MethodChannel.Result result) {
        Map data = (Map) call.arguments;
        String userName = (String) data.get("userName");
        String domain = (String) data.get("domain");
        String password = (String) data.get("password");
        String transport = (String) data.get("transport");

        Log.d(TAG, "handleLoginWithTransport user=" + userName + " domain=" + domain + " transport=" + transport);

        // Store credentials and delegate login to the service so registration
        // survives app termination.
        SipForegroundService.loginViaService(activity, userName, domain, password,
                transport != null ? transport : "udp");
        result.success("Success");
    }

    /**
     * Handle permission request with all required VoIP permissions
     */
    private void handlePermissionRequest(MethodChannel.Result result) {
        try {
            String[] permissionArrays;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ requires POST_NOTIFICATIONS + BLUETOOTH_CONNECT
                permissionArrays = new String[] {
                        Manifest.permission.CAMERA,
                        Manifest.permission.USE_SIP,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.ACCESS_NETWORK_STATE,
                        Manifest.permission.CHANGE_NETWORK_STATE,
                        Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.CHANGE_WIFI_STATE,
                        Manifest.permission.MODIFY_AUDIO_SETTINGS,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.POST_NOTIFICATIONS,
                };
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12 requires BLUETOOTH_CONNECT
                permissionArrays = new String[] {
                        Manifest.permission.CAMERA,
                        Manifest.permission.USE_SIP,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.ACCESS_NETWORK_STATE,
                        Manifest.permission.CHANGE_NETWORK_STATE,
                        Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.CHANGE_WIFI_STATE,
                        Manifest.permission.MODIFY_AUDIO_SETTINGS,
                        Manifest.permission.BLUETOOTH_CONNECT,
                };
            } else {
                permissionArrays = new String[] {
                        Manifest.permission.CAMERA,
                        Manifest.permission.USE_SIP,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.ACCESS_NETWORK_STATE,
                        Manifest.permission.CHANGE_NETWORK_STATE,
                        Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.CHANGE_WIFI_STATE,
                        Manifest.permission.MODIFY_AUDIO_SETTINGS,
                        Manifest.permission.BLUETOOTH,
                };
            }

            boolean isSuccess = new Utils().checkPermissions(permissionArrays, activity);
            Log.d(TAG, "Permissions check result=" + isSuccess);
            if (isSuccess) {
                result.success(true);
            } else {
                result.error("Permission Error", "Permission is not granted.", "Error");
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "Permission request failed", e);
            result.error(null, e.toString(), null);
        }
    }
}
