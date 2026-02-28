import 'package:flutter/material.dart';
import 'package:linphone_flutter_plugin/linphoneflutterplugin.dart';
import 'package:linphone_flutter_plugin/CallLog.dart';
import 'package:linphone_flutter_plugin/call_state.dart';
import 'dart:async';
import 'package:linphone_flutter_plugin/login_state.dart';

void main() {
  runApp(const MyApp());
}

// Main application widget
class MyApp extends StatefulWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  // Instance of the Linphone Flutter Plugin
  final _linphoneSdkPlugin = LinphoneFlutterPlugin();

  // TextEditingControllers for handling user input in text fields
  late TextEditingController _userController;
  late TextEditingController _passController;
  late TextEditingController _domainController;
  final _textEditingController = TextEditingController();

  // Selected transport type for SIP connection
  // TCP recommended: avoids UDP MTU fragmentation issues with large SIP INVITE
  SipTransport _selectedTransport = SipTransport.tcp;

  // Navigator key – needed so showDialog uses a context BELOW MaterialApp
  final _navigatorKey = GlobalKey<NavigatorState>();

  // Track whether the incoming-call dialog is currently showing
  bool _incomingDialogShowing = false;

  // Cached event streams — MUST be created once, not inside build().
  // Creating a new stream on every rebuild triggers onCancel+onListen on the
  // native EventChannel, briefly setting eventSink to null and dropping events.
  late final Stream<LoginState> _loginStream;
  late final Stream<CallState> _callStream;

  @override
  void initState() {
    super.initState();

    // Cache event streams once
    _loginStream = _linphoneSdkPlugin.addLoginListener();
    _callStream = _linphoneSdkPlugin.addCallStateListener();

    // Initialize TextEditingControllers with default values
    _userController = TextEditingController(text: "1039");
    _passController =
        TextEditingController(text: "61c1df965857b9b9d2df320ee163a268");
    _domainController = TextEditingController(text: "main.egytelecoms.com");

    // Request necessary permissions for using Linphone features
    requestPermissions();
  }

  /// Show the incoming-call dialog (only once).
  void _showIncomingCallDialog() {
    if (_incomingDialogShowing) return;
    final ctx = _navigatorKey.currentContext;
    if (ctx == null) return;

    _incomingDialogShowing = true;
    showDialog(
      context: ctx,
      barrierDismissible: false,
      builder: (_) => AlertDialog(
        title: const Text('Incoming Call'),
        content: const Text('You have an incoming call.'),
        actions: [
          TextButton(
            onPressed: () async {
              await reject();
              _dismissIncomingCallDialog();
            },
            child: const Text('Reject'),
          ),
          TextButton(
            onPressed: () async {
              await answer();
              _dismissIncomingCallDialog();
            },
            child: const Text('Answer'),
          ),
        ],
      ),
    ).then((_) {
      // Dialog was dismissed (e.g. back button)
      _incomingDialogShowing = false;
    });
  }

  /// Dismiss the incoming-call dialog if it's showing.
  void _dismissIncomingCallDialog() {
    if (!_incomingDialogShowing) return;
    final ctx = _navigatorKey.currentContext;
    if (ctx != null) {
      Navigator.of(ctx).pop();
    }
    _incomingDialogShowing = false;
  }

  // Request permissions needed by the Linphone SDK
  Future<void> requestPermissions() async {
    try {
      await _linphoneSdkPlugin.requestPermissions();
    } catch (e) {
      print("Error on request permission. ${e.toString()}");
    }
  }

  // Login method to authenticate the user using Linphone
  Future<void> login({
    required String username,
    required String pass,
    required String domain,
    required SipTransport transport,
  }) async {
    try {
      await _linphoneSdkPlugin.loginWithTransport(
          userName: username,
          domain: domain,
          password: pass,
          transport: transport);
    } catch (e) {
      // Show error message if login fails
      print("Error on login. ${e.toString()}");
    }
  }

  // Method to initiate a call using the Linphone SDK
  Future<void> call() async {
    if (_textEditingController.text.isNotEmpty) {
      String number = _textEditingController.text;
      try {
        await _linphoneSdkPlugin.call(number: number);
      } catch (e) {
        // Show error message if the call fails
        print("Error on call. ${e.toString()}");
      }
    }
  }

  // Method to transfer an ongoing call to another number
  Future<void> forward() async {
    try {
      await _linphoneSdkPlugin.callTransfer(destination: "1000");
    } catch (e) {
      // Show error message if call transfer fails
      print("Error on call transfer. ${e.toString()}");
    }
  }

  // Method to hang up an ongoing call
  Future<void> hangUp() async {
    try {
      await _linphoneSdkPlugin.hangUp();
    } catch (e) {
      // Show error message if hang up fails
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text("Hang up failed: ${e.toString()}")),
      );
    }
  }

  // Method to toggle the speaker on/off
  Future<void> toggleSpeaker() async {
    try {
      await _linphoneSdkPlugin.toggleSpeaker();
    } catch (e) {
      // Show error message if toggling the speaker fails
      print("Error on toggle speaker. ${e.toString()}");
    }
  }

  // Method to toggle mute on/off
  Future<void> toggleMute() async {
    try {
      bool isMuted = await _linphoneSdkPlugin.toggleMute();
      // Show feedback to the user about the mute status
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(isMuted ? "Muted" : "Unmuted")),
      );
    } catch (e) {
      // Show error message if toggling mute fails
      print("Error on toggle mute. ${e.toString()}");
    }
  }

  // Method to answer an incoming call
  Future<void> answer() async {
    try {
      await _linphoneSdkPlugin.answercall();
    } catch (e) {
      // Show error message if answering the call fails
      print("Error on answer call. ${e.toString()}");
    }
  }

  // Method to reject an incoming call
  Future<void> reject() async {
    try {
      await _linphoneSdkPlugin.rejectCall();
    } catch (e) {
      // Show error message if rejecting the call fails
      print("Error on reject call. ${e.toString()}");
    }
  }

  // Method to retrieve and print the call logs
  Future<void> callLogs() async {
    try {
      CallLogs callLogs = await _linphoneSdkPlugin.callLogs();
      print("---------call logs length: ${callLogs.callHistory.length}");
    } catch (e) {
      // Show error message if fetching call logs fails
      print("Error on call logs. ${e.toString()}");
    }
  }

  // Show diagnostic registration info
  Future<void> showDiagnostics() async {
    try {
      final info = await _linphoneSdkPlugin.getRegistrationInfo();
      final buffer = StringBuffer();
      info.forEach((k, v) => buffer.writeln('$k: $v'));
      final ctx = _navigatorKey.currentContext;
      if (ctx == null) return;
      showDialog(
        context: ctx,
        builder: (_) => AlertDialog(
          title: const Text('SIP Diagnostics'),
          content: SingleChildScrollView(
            child:
                Text(buffer.toString(), style: const TextStyle(fontSize: 12)),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('OK'),
            ),
          ],
        ),
      );
    } catch (e) {
      print("Diagnostics error: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: _navigatorKey,
      theme: ThemeData(
        primarySwatch: Colors.blue,
        visualDensity: VisualDensity.adaptivePlatformDensity,
      ),
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Linphone Flutter Plugin Example'),
        ),
        body: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            // Username input field
            TextFormField(
              controller: _userController,
              decoration: const InputDecoration(
                icon: Icon(Icons.person),
                hintText: "Input username",
                labelText: "Username",
              ),
            ),
            // Password input field
            TextFormField(
              controller: _passController,
              obscureText: true,
              decoration: const InputDecoration(
                icon: Icon(Icons.lock),
                hintText: "Input password",
                labelText: "Password",
              ),
            ),
            // Domain input field
            TextFormField(
              controller: _domainController,
              decoration: const InputDecoration(
                icon: Icon(Icons.domain),
                hintText: "Input domain",
                labelText: "Domain",
              ),
            ),
            const SizedBox(height: 16),
            // Transport type dropdown
            Row(
              children: [
                const Icon(Icons.swap_vert, color: Colors.grey),
                const SizedBox(width: 16),
                Expanded(
                  child: DropdownButtonFormField<SipTransport>(
                    value: _selectedTransport,
                    decoration: const InputDecoration(
                      labelText: "Transport",
                    ),
                    items: SipTransport.values.map((transport) {
                      return DropdownMenuItem<SipTransport>(
                        value: transport,
                        child: Text(transport.name.toUpperCase()),
                      );
                    }).toList(),
                    onChanged: (SipTransport? newValue) {
                      if (newValue != null) {
                        setState(() {
                          _selectedTransport = newValue;
                        });
                      }
                    },
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            // Login button
            ElevatedButton(
              onPressed: () {
                login(
                  username: _userController.text,
                  pass: _passController.text,
                  domain: _domainController.text,
                  transport: _selectedTransport,
                );
              },
              child: const Text("Login"),
            ),
            const SizedBox(height: 20),
            // Display login status
            StreamBuilder<LoginState>(
              stream: _loginStream,
              builder: (context, snapshot) {
                LoginState status = snapshot.data ?? LoginState.none;
                return Text("Login status: ${status.name}");
              },
            ),
            const SizedBox(height: 20),
            // Display call status
            StreamBuilder<CallState>(
              stream: _callStream,
              builder: (context, snapshot) {
                CallState? status = snapshot.data;

                // Show incoming-call dialog via showDialog (not inline)
                if (status == CallState.IncomingReceived) {
                  // Schedule dialog after this build frame
                  WidgetsBinding.instance.addPostFrameCallback((_) {
                    _showIncomingCallDialog();
                  });
                }

                // Dismiss the incoming call dialog when the call is answered,
                // rejected, ended, or released.
                if (status == CallState.connected ||
                    status == CallState.streamsRunning ||
                    status == CallState.end ||
                    status == CallState.released ||
                    status == CallState.error) {
                  _dismissIncomingCallDialog();
                }

                // Active call UI (connected / streams running)
                if (status == CallState.connected ||
                    status == CallState.streamsRunning) {
                  return Card(
                    color: Colors.green.shade50,
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        children: [
                          const Icon(Icons.call, color: Colors.green, size: 48),
                          const SizedBox(height: 8),
                          Text("Call Active (${status?.name})",
                              style: const TextStyle(
                                  fontSize: 18, fontWeight: FontWeight.bold)),
                          const SizedBox(height: 16),
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                            children: [
                              ElevatedButton.icon(
                                onPressed: toggleMute,
                                icon: const Icon(Icons.mic_off),
                                label: const Text("Mute"),
                              ),
                              ElevatedButton.icon(
                                onPressed: toggleSpeaker,
                                icon: const Icon(Icons.volume_up),
                                label: const Text("Speaker"),
                              ),
                            ],
                          ),
                          const SizedBox(height: 12),
                          ElevatedButton.icon(
                            style: ElevatedButton.styleFrom(
                                backgroundColor: Colors.red),
                            onPressed: hangUp,
                            icon: const Icon(Icons.call_end),
                            label: const Text("Hang Up"),
                          ),
                        ],
                      ),
                    ),
                  );
                }

                return Column(
                  children: [
                    Text("Call status: ${status?.name ?? 'none'}"),
                    if (status == CallState.outgoingInit ||
                        status == CallState.outgoingProgress ||
                        status == CallState.outgoingRinging)
                      ElevatedButton.icon(
                        style: ElevatedButton.styleFrom(
                            backgroundColor: Colors.red),
                        onPressed: hangUp,
                        icon: const Icon(Icons.call_end),
                        label: const Text("Hang Up"),
                      ),
                  ],
                );
              },
            ),
            const SizedBox(height: 20),
            // Phone number input field
            TextFormField(
              controller: _textEditingController,
              keyboardType: TextInputType.phone,
              decoration: const InputDecoration(
                icon: Icon(Icons.phone),
                hintText: "Input number",
                labelText: "Number",
              ),
            ),
            const SizedBox(height: 20),
            // Call button
            ElevatedButton(onPressed: call, child: const Text("Call")),
            const SizedBox(height: 20),
            // Answer button
            ElevatedButton(
              onPressed: () {
                answer();
              },
              child: const Text("Answer"),
            ),
            const SizedBox(height: 20),
            // Reject button
            ElevatedButton(
              onPressed: () {
                reject();
              },
              child: const Text("Reject"),
            ),
            // Hang up button
            ElevatedButton(
              onPressed: () {
                hangUp();
              },
              child: const Text("Hang Up"),
            ),
            const SizedBox(height: 20),
            // Toggle speaker button
            ElevatedButton(
              onPressed: () {
                toggleSpeaker();
              },
              child: const Text("Speaker"),
            ),
            const SizedBox(height: 20),
            // Toggle mute button
            ElevatedButton(
              onPressed: () {
                toggleMute();
              },
              child: const Text("Mute"),
            ),
            const SizedBox(height: 20),
            // Forward call button
            ElevatedButton(
              onPressed: () {
                forward();
              },
              child: const Text("Forward"),
            ),
            const SizedBox(height: 20),
            // Call log button
            ElevatedButton(
              onPressed: () {
                callLogs();
              },
              child: const Text("Call Log"),
            ),
            const SizedBox(height: 20),
            // Diagnostics button
            ElevatedButton(
              style: ElevatedButton.styleFrom(backgroundColor: Colors.orange),
              onPressed: showDiagnostics,
              child: const Text("SIP Diagnostics"),
            ),
          ],
        ),
      ),
    );
  }

  @override
  void dispose() {
    // Remove listeners and dispose of controllers to prevent memory leaks
    _linphoneSdkPlugin.removeLoginListener();
    _linphoneSdkPlugin.removeCallListener();
    _userController.dispose();
    _passController.dispose();
    _domainController.dispose();
    _textEditingController.dispose();
    super.dispose();
  }
}
