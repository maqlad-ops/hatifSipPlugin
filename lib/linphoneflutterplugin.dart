import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:linphone_flutter_plugin/CallLog.dart';
import 'package:linphone_flutter_plugin/call_state.dart';
import 'package:linphone_flutter_plugin/login_state.dart';

/// Transport type for SIP registration
enum SipTransport { udp, tcp, tls }

/// Linphone Flutter Plugin - Production-grade SIP softphone
///
/// Configured to match Sipnetic softphone behavior with:
/// - Proper NAT/ICE/STUN configuration
/// - Audio codec priority (Opus > G.722 > G.711 A-law)
/// - RTP/AVP profile with symmetric RTP
/// - Session timers and SIP keep-alive
/// - Proper audio routing and Bluetooth support
class LinphoneFlutterPlugin {
  static const MethodChannel _channel = MethodChannel("linphonesdk");
  static const EventChannel _loginEventListener =
      EventChannel("linphonesdk/login_listener");
  static const EventChannel _callEventListener =
      EventChannel("linphonesdk/call_event_listener");
  static const EventChannel _callDataListener =
      EventChannel("linphonesdk/call_data_listener");

  void _log(String message, {Object? data}) {
    final ts = DateTime.now().toIso8601String();
    final suffix = data != null ? " | data=$data" : "";
    debugPrint("[LinphoneFlutterPlugin][$ts] $message$suffix");
  }

  String _maskSecret(String secret) {
    if (secret.isEmpty) return "<empty>";
    if (secret.length <= 2) return "*" * secret.length;
    return secret[0] + "*" * (secret.length - 2) + secret[secret.length - 1];
  }

  /// Request all required permissions for VoIP functionality
  /// Includes: RECORD_AUDIO, CAMERA, USE_SIP, BLUETOOTH_CONNECT, etc.
  Future<void> requestPermissions() async {
    try {
      _log("requestPermissions invoked");
      return await _channel.invokeMethod('request_permissions');
    } catch (e) {
      _log("requestPermissions failed", data: e.toString());
      throw FormatException("Error on request permission. $e");
    }
  }

  /// Login to SIP server with default UDP transport
  ///
  /// [userName] - SIP username
  /// [domain] - SIP server domain (e.g., "sip.example.com")
  /// [password] - SIP password
  Future<void> login({
    required String userName,
    required String domain,
    required String password,
  }) async {
    _log("login (default UDP)", data: {
      "user": userName,
      "domain": domain,
      "pwd": _maskSecret(password),
    });
    var data = {"userName": userName, "domain": domain, "password": password};
    final res = await _channel.invokeMethod("login", data);
    _log("login invoke done", data: res);
    return res;
  }

  /// Login to SIP server with specified transport type
  ///
  /// [userName] - SIP username
  /// [domain] - SIP server domain
  /// [password] - SIP password
  /// [transport] - Transport type: TLS (default, most secure), TCP, or UDP
  Future<void> loginWithTransport({
    required String userName,
    required String domain,
    required String password,
    SipTransport transport = SipTransport.udp,
  }) async {
    _log("loginWithTransport", data: {
      "user": userName,
      "domain": domain,
      "transport": transport.name,
      "pwd": _maskSecret(password),
    });
    var data = {
      "userName": userName,
      "domain": domain,
      "password": password,
      "transport": transport.name,
    };
    final res = await _channel.invokeMethod("loginWithTransport", data);
    _log("loginWithTransport invoke done", data: res);
    return res;
  }

  /// Logout and cleanup all resources
  Future<void> logout() async {
    _log("logout invoked");
    final res = await _channel.invokeMethod("logout");
    _log("logout done", data: res);
    return res;
  }

  /// Start the background SIP service (persistent foreground notification)
  Future<void> startService() async {
    _log("startService invoked");
    await _channel.invokeMethod("start_service");
    _log("startService done");
  }

  /// Stop the background SIP service completely
  Future<void> stopService() async {
    _log("stopService invoked");
    await _channel.invokeMethod("stop_service");
    _log("stopService done");
  }

  /// Toggle speaker on/off
  Future<void> toggleSpeaker() async {
    _log("toggleSpeaker");
    return await _channel.invokeMethod("toggle_speaker");
  }

  /// Route audio to speaker
  Future<void> routeToSpeaker() async {
    _log("routeToSpeaker");
    return await _channel.invokeMethod("route_to_speaker");
  }

  /// Route audio to earpiece
  Future<void> routeToEarpiece() async {
    _log("routeToEarpiece");
    return await _channel.invokeMethod("route_to_earpiece");
  }

  /// Route audio to Bluetooth device (if available)
  /// Returns true if Bluetooth device is available and connected
  Future<bool> routeToBluetooth() async {
    _log("routeToBluetooth");
    final res = await _channel.invokeMethod("route_to_bluetooth");
    _log("routeToBluetooth result", data: res);
    return res;
  }

  /// Check if speaker is currently enabled
  Future<bool> isSpeakerEnabled() async {
    final res = await _channel.invokeMethod("is_speaker_enabled");
    _log("isSpeakerEnabled", data: res);
    return res;
  }

  /// Get list of available audio devices
  /// Returns JSON list of device types and names
  Future<String> getAudioDevices() async {
    final res = await _channel.invokeMethod("get_audio_devices");
    _log("getAudioDevices", data: res);
    return res;
  }

  /// Toggle microphone mute
  /// Returns true if muted, false if unmuted
  Future<bool> toggleMute() async {
    _log("toggleMute");
    final res = await _channel.invokeMethod("mute");
    _log("toggleMute result", data: res);
    return res;
  }

  /// Check if microphone is currently muted
  Future<bool> isMuted() async {
    final res = await _channel.invokeMethod("isMuted");
    _log("isMuted", data: res);
    return res;
  }

  /// Make an outgoing call
  /// [number] - SIP URI or phone number to call
  Future<void> call({required String number}) async {
    _log("call", data: {"number": number});
    var data = {"number": number};
    final res = await _channel.invokeMethod("call", data);
    _log("call invoke done", data: res);
    return res;
  }

  /// Hang up current call
  Future<void> hangUp() async {
    _log("hangUp");
    return await _channel.invokeMethod("hangUp");
  }

  /// Remove login listener and cleanup
  Future<void> removeLoginListener() async {
    _log("removeLoginListener");
    return _channel.invokeMethod("remove_listener");
  }

  /// Remove call listener and cleanup
  Future<void> removeCallListener() async {
    _log("removeCallListener");
    return _channel.invokeMethod("remove_call_listener");
  }

  /// Transfer current call to another destination
  /// [destination] - SIP URI to transfer to
  Future<bool> callTransfer({required String destination}) async {
    _log("callTransfer", data: {"destination": destination});
    var data = {"destination": destination};
    final res = await _channel.invokeMethod("transfer", data);
    _log("callTransfer result", data: res);
    return res;
  }

  /// Get call history/logs
  Future<CallLogs> callLogs() async {
    _log("callLogs request");
    var list = await _channel.invokeMethod("call_logs");
    _log("callLogs response", data: list);
    return CallLogs.fromJson(jsonDecode(list));
  }

  /// Answer incoming call
  Future<void> answercall() async {
    _log("answercall invoked");
    return await _channel.invokeMethod("answerCall");
  }

  /// Reject incoming call
  Future<void> rejectCall() async {
    _log("rejectCall invoked");
    return await _channel.invokeMethod("rejectCall");
  }

  /// Send DTMF tone (RFC2833)
  /// [digit] - DTMF digit (0-9, *, #)
  Future<void> sendDtmf({required String digit}) async {
    _log("sendDtmf", data: digit);
    var data = {"digit": digit};
    return await _channel.invokeMethod("send_dtmf", data);
  }

  /// Send DTMF string
  /// [digits] - String of DTMF digits
  Future<void> sendDtmfs({required String digits}) async {
    _log("sendDtmfs", data: digits);
    var data = {"digits": digits};
    return await _channel.invokeMethod("send_dtmfs", data);
  }

  /// Get current call duration in seconds
  Future<int> getCallDuration() async {
    final res = await _channel.invokeMethod("get_call_duration");
    _log("getCallDuration", data: res);
    return res;
  }

  /// Check if there's an active call
  Future<bool> hasActiveCall() async {
    final res = await _channel.invokeMethod("has_active_call");
    _log("hasActiveCall", data: res);
    return res;
  }

  /// Get current call data as a JSON map
  /// Includes: hasActiveCall, callState, remoteName, remoteUri,
  ///   duration, muted, speaker, onHold, registrationState
  Future<Map<String, dynamic>> getCallData() async {
    final res = await _channel.invokeMethod("get_call_data");
    _log("getCallData", data: res);
    if (res == null || res.toString().isEmpty) return {};
    return Map<String, dynamic>.from(jsonDecode(res.toString()));
  }

  /// Get current registration state
  /// Returns: "Ok", "Progress", "None", "Cleared", "Failed"
  Future<String> getRegistrationState() async {
    final res = await _channel.invokeMethod("get_registration_state");
    _log("getRegistrationState", data: res);
    return res;
  }

  /// Manually refresh SIP registration
  Future<void> refreshRegistration() async {
    _log("refreshRegistration");
    return await _channel.invokeMethod("refresh_registration");
  }

  /// Set network reachability state
  /// Call this when network connectivity changes
  Future<void> setNetworkReachable({required bool reachable}) async {
    _log("setNetworkReachable", data: reachable);
    var data = {"reachable": reachable};
    return await _channel.invokeMethod("set_network_reachable", data);
  }

  /// Update STUN server configuration
  /// [server] - STUN server hostname
  /// [port] - STUN server port (default: 3478)
  Future<void> updateStunServer({
    required String server,
    int port = 3478,
  }) async {
    _log("updateStunServer", data: {"server": server, "port": port});
    var data = {"server": server, "port": port};
    return await _channel.invokeMethod("update_stun_server", data);
  }

  /// Apply runtime configuration from UI (persisted on Android side)
  /// Pass any subset of supported keys (e.g., stunServer, stunPort, enableIce, enabledCodecs...)
  Future<void> applyConfig(Map<String, dynamic> config) async {
    _log("applyConfig", data: config);
    final res = await _channel.invokeMethod("apply_config", config);
    _log("applyConfig done", data: res);
  }

  /// Fetch current persisted configuration from native side
  Future<Map<String, dynamic>> getConfig() async {
    final res = await _channel.invokeMethod("get_config");
    _log("getConfig", data: res);
    if (res == null) return {};
    return Map<String, dynamic>.from(res as Map);
  }

  /// Get comprehensive registration / diagnostic info.
  /// Returns a map with keys like: coreAlive, registrationState,
  /// contactAddress, serverAddress, networkReachable, etc.
  Future<Map<String, dynamic>> getRegistrationInfo() async {
    final res = await _channel.invokeMethod("get_registration_info");
    _log("getRegistrationInfo", data: res);
    if (res == null) return {};
    return Map<String, dynamic>.from(res as Map);
  }

  /// Listen to login/registration state changes
  Stream<LoginState> addLoginListener() {
    _log("addLoginListener attached");
    return _loginEventListener.receiveBroadcastStream().map((event) {
      _log("login event", data: event);
      LoginState loginState = LoginState.none;
      if (event == "Ok") {
        loginState = LoginState.ok;
      } else if (event == "Progress") {
        loginState = LoginState.progress;
      } else if (event == "None") {
        loginState = LoginState.none;
      } else if (event == "Cleared") {
        loginState = LoginState.cleared;
      } else if (event == "Failed") {
        loginState = LoginState.failed;
      }
      return loginState;
    });
  }

  /// Listen to call state changes
  Stream<CallState> addCallStateListener() {
    _log("addCallStateListener attached");
    return _callEventListener.receiveBroadcastStream().map((event) {
      _log("call event", data: event);
      CallState callState = CallState.idle;
      if (event == "IncomingReceived") {
        callState = CallState.IncomingReceived;
      } else if (event == "IncomingEarlyMedia") {
        callState = CallState.incomingEarlyMedia;
      } else if (event == "PushIncomingReceived") {
        callState = CallState.pushIncomingReceived;
      } else if (event == "OutgoingInit") {
        callState = CallState.outgoingInit;
      } else if (event == "OutgoingProgress") {
        callState = CallState.outgoingProgress;
      } else if (event == "OutgoingRinging") {
        callState = CallState.outgoingRinging;
      } else if (event == "Connected") {
        callState = CallState.connected;
      } else if (event == "StreamsRunning") {
        callState = CallState.streamsRunning;
      } else if (event == "Paused") {
        callState = CallState.paused;
      } else if (event == "PausedByRemote") {
        callState = CallState.pausedByRemote;
      } else if (event == "Updating") {
        callState = CallState.updating;
      } else if (event == "UpdatedByRemote") {
        callState = CallState.updatedByRemote;
      } else if (event == "Released") {
        callState = CallState.released;
      } else if (event == "End") {
        callState = CallState.end;
      } else if (event == "EarlyUpdatedByRemote") {
        callState = CallState.earlyUpdatedByRemote;
      } else if (event == "Error") {
        callState = CallState.error;
      }
      return callState;
    });
  }

  /// Listen to comprehensive call data changes (JSON snapshots).
  /// Emits a map on EVERY state change with:
  ///   hasActiveCall, callState, remoteName, remoteUri,
  ///   duration, muted, speaker, onHold, registrationState
  Stream<Map<String, dynamic>> addCallDataListener() {
    _log("addCallDataListener attached");
    return _callDataListener.receiveBroadcastStream().map((event) {
      _log("call data event", data: event);
      if (event is String) {
        return Map<String, dynamic>.from(jsonDecode(event));
      }
      return <String, dynamic>{};
    });
  }
}
