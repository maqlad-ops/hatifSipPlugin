import 'dart:async';
import 'package:flutter/material.dart';
import 'package:linphone_flutter_plugin/linphoneflutterplugin.dart';
import 'package:linphone_flutter_plugin/login_state.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final _linphoneSdkPlugin = LinphoneFlutterPlugin();

  late TextEditingController _userController;
  late TextEditingController _passController;
  late TextEditingController _domainController;
  final _textEditingController = TextEditingController();

  SipTransport _selectedTransport = SipTransport.udp;

  // Cached streams
  late final Stream<LoginState> _loginStream;
  Stream<Map<String, dynamic>>? _callDataStream;
  StreamSubscription<Map<String, dynamic>>? _callDataSub;
  Timer? _callTimer;

  // Call data state
  Map<String, dynamic> _callData = {};

  bool _permissionsGranted = false;
  bool _serviceStarted = false;

  @override
  void initState() {
    super.initState();
    _loginStream = _linphoneSdkPlugin.addLoginListener();

    _userController = TextEditingController(text: "1039");
    _passController =
        TextEditingController(text: "61c1df965857b9b9d2df320ee163a268");
    _domainController = TextEditingController(text: "main.egytelecoms.com");

    // Request permissions on startup
    _requestPermissionsAndStart();
  }

  // ---------------------------------------------------------------
  // Permission & service startup
  // ---------------------------------------------------------------

  Future<void> _requestPermissionsAndStart() async {
    try {
      await _linphoneSdkPlugin.requestPermissions();
      setState(() => _permissionsGranted = true);

      // Start listening to call data after permissions are granted
      _startCallDataListener();
    } catch (e) {
      print("Permissions not granted: $e");
      // Still allow the UI to show — user can retry
    }
  }

  void _startCallDataListener() {
    _callDataStream = _linphoneSdkPlugin.addCallDataListener();
    _callDataSub = _callDataStream?.listen((data) {
      if (mounted) {
        final hasActiveCall = data['hasActiveCall'] == true;
        setState(() => _callData = data);
        // Start/stop a 1-second periodic refresh for the ticking timer
        if (hasActiveCall && _callTimer == null) {
          _startCallTimer();
        } else if (!hasActiveCall && _callTimer != null) {
          _stopCallTimer();
        }
      }
    }, onError: (e) {
      print("Call data stream error: $e");
    });
  }

  void _startCallTimer() {
    _callTimer?.cancel();
    _callTimer = Timer.periodic(const Duration(seconds: 1), (_) async {
      try {
        final data = await _linphoneSdkPlugin.getCallData();
        if (mounted && data.isNotEmpty) {
          setState(() => _callData = data);
          if (data['hasActiveCall'] != true) _stopCallTimer();
        }
      } catch (_) {}
    });
  }

  void _stopCallTimer() {
    _callTimer?.cancel();
    _callTimer = null;
  }

  // ---------------------------------------------------------------
  // Actions
  // ---------------------------------------------------------------

  Future<void> login({
    required String username,
    required String pass,
    required String domain,
    required SipTransport transport,
  }) async {
    if (!_permissionsGranted) {
      await _requestPermissionsAndStart();
      if (!_permissionsGranted) return;
    }
    try {
      await _linphoneSdkPlugin.loginWithTransport(
          userName: username,
          domain: domain,
          password: pass,
          transport: transport);
      setState(() => _serviceStarted = true);
    } catch (e) {
      print("Error on login: $e");
    }
  }

  Future<void> call() async {
    if (_textEditingController.text.isNotEmpty) {
      String number = _textEditingController.text;
      try {
        await _linphoneSdkPlugin.call(number: number);
      } catch (e) {
        print("Error on call: $e");
      }
    }
  }

  Future<void> hangUp() async {
    try {
      await _linphoneSdkPlugin.hangUp();
    } catch (e) {
      print("Error on hang up: $e");
    }
  }

  Future<void> toggleMute() async {
    try {
      await _linphoneSdkPlugin.toggleMute();
    } catch (e) {
      print("Error on mute: $e");
    }
  }

  Future<void> toggleSpeaker() async {
    try {
      await _linphoneSdkPlugin.toggleSpeaker();
    } catch (e) {
      print("Error on speaker: $e");
    }
  }

  Future<void> toggleHold() async {
    try {
      await _linphoneSdkPlugin.toggleHold();
    } catch (e) {
      print("Error on hold: $e");
    }
  }

  // ---------------------------------------------------------------
  // Transfer
  // ---------------------------------------------------------------

  void _showTransferDialog() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (ctx) => _TransferSheet(plugin: _linphoneSdkPlugin),
    );
  }

  // ---------------------------------------------------------------
  // Conference
  // ---------------------------------------------------------------

  void _showConferenceDialog() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (ctx) => _ConferenceSheet(plugin: _linphoneSdkPlugin),
    );
  }

  // ---------------------------------------------------------------
  // Build
  // ---------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark().copyWith(
        scaffoldBackgroundColor: const Color(0xFF1c1c1e),
        colorScheme: ColorScheme.dark(
          primary: const Color(0xFF4facfe),
          secondary: const Color(0xFF00f2fe),
        ),
      ),
      home: _buildHomePage(),
    );
  }

  Widget _buildHomePage() {
    final bool hasActiveCall = _callData['hasActiveCall'] == true;

    return Scaffold(
      body: Container(
        width: double.infinity,
        height: double.infinity,
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [
              Color(0xFF1a1a2e),
              Color(0xFF16213e),
              Color(0xFF0f3460),
            ],
          ),
        ),
        child: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // Header
                const SizedBox(height: 10),
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        color: const Color(0xFF4facfe).withOpacity(0.15),
                        borderRadius: BorderRadius.circular(14),
                      ),
                      child: const Icon(Icons.phone_in_talk_rounded,
                          color: Color(0xFF4facfe), size: 28),
                    ),
                    const SizedBox(width: 14),
                    const Text(
                      'Hatif',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 26,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),

                const SizedBox(height: 20),

                // Active call card (shown when there's a running call)
                if (hasActiveCall) _buildActiveCallCard(),

                if (hasActiveCall) const SizedBox(height: 20),

                // Account & Service card
                _buildGlassCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const Text(
                        'Account',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 18,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 16),

                      _buildTextField(
                        controller: _userController,
                        icon: Icons.person_outline,
                        hint: 'Username',
                      ),
                      const SizedBox(height: 12),
                      _buildTextField(
                        controller: _passController,
                        icon: Icons.lock_outline,
                        hint: 'Password',
                        obscure: true,
                      ),
                      const SizedBox(height: 12),
                      _buildTextField(
                        controller: _domainController,
                        icon: Icons.dns_outlined,
                        hint: 'Domain',
                      ),
                      const SizedBox(height: 12),

                      // Transport dropdown
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 14, vertical: 4),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.07),
                          borderRadius: BorderRadius.circular(12),
                          border:
                              Border.all(color: Colors.white.withOpacity(0.1)),
                        ),
                        child: DropdownButtonHideUnderline(
                          child: DropdownButton<SipTransport>(
                            value: _selectedTransport,
                            dropdownColor: const Color(0xFF2c2c3e),
                            style: const TextStyle(
                                color: Colors.white, fontSize: 15),
                            icon: const Icon(Icons.arrow_drop_down,
                                color: Colors.white54),
                            isExpanded: true,
                            items: SipTransport.values.map((t) {
                              return DropdownMenuItem(
                                value: t,
                                child: Text(t.name.toUpperCase()),
                              );
                            }).toList(),
                            onChanged: _serviceStarted
                                ? null
                                : (v) {
                                    if (v != null) {
                                      setState(() => _selectedTransport = v);
                                    }
                                  },
                          ),
                        ),
                      ),

                      const SizedBox(height: 18),

                      // Start / Stop Service button (= login / logout SIP)
                      _buildGradientButton(
                        label:
                            _serviceStarted ? 'Stop Service' : 'Start Service',
                        icon: _serviceStarted
                            ? Icons.stop_circle_outlined
                            : Icons.play_circle_outline,
                        colors: _serviceStarted
                            ? const [Color(0xFFe53935), Color(0xFFb71c1c)]
                            : const [Color(0xFF4caf50), Color(0xFF2e7d32)],
                        onPressed: () async {
                          if (_serviceStarted) {
                            await _linphoneSdkPlugin.stopService();
                            setState(() => _serviceStarted = false);
                          } else {
                            // Start service = login SIP
                            if (!_permissionsGranted) {
                              await _requestPermissionsAndStart();
                              if (!_permissionsGranted) return;
                            }
                            try {
                              await _linphoneSdkPlugin.loginWithTransport(
                                userName: _userController.text,
                                domain: _domainController.text,
                                password: _passController.text,
                                transport: _selectedTransport,
                              );
                              setState(() => _serviceStarted = true);
                            } catch (e) {
                              print('Error starting service: $e');
                            }
                          }
                        },
                      ),

                      const SizedBox(height: 14),

                      // Registration status
                      StreamBuilder<LoginState>(
                        stream: _loginStream,
                        builder: (context, snap) {
                          final status = snap.data ?? LoginState.none;
                          Color dotColor;
                          String label;
                          switch (status) {
                            case LoginState.ok:
                              dotColor = const Color(0xFF4caf50);
                              label = 'REGISTERED';
                              break;
                            case LoginState.progress:
                              dotColor = const Color(0xFFff9800);
                              label = 'CONNECTING...';
                              break;
                            case LoginState.failed:
                              dotColor = const Color(0xFFe53935);
                              label = 'FAILED';
                              break;
                            default:
                              dotColor = Colors.grey;
                              label = 'OFFLINE';
                          }
                          return Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Container(
                                width: 10,
                                height: 10,
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  color: dotColor,
                                  boxShadow: [
                                    BoxShadow(
                                        color: dotColor.withOpacity(0.5),
                                        blurRadius: 6)
                                  ],
                                ),
                              ),
                              const SizedBox(width: 8),
                              Text(
                                label,
                                style: TextStyle(
                                  color: Colors.white.withOpacity(0.7),
                                  fontSize: 13,
                                  fontWeight: FontWeight.w500,
                                  letterSpacing: 1.2,
                                ),
                              ),
                            ],
                          );
                        },
                      ),
                    ],
                  ),
                ),

                const SizedBox(height: 20),

                // Dialer card
                _buildGlassCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const Text(
                        'Dialer',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 18,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 16),

                      _buildTextField(
                        controller: _textEditingController,
                        icon: Icons.dialpad_rounded,
                        hint: 'Enter number',
                        keyboardType: TextInputType.phone,
                      ),

                      const SizedBox(height: 16),

                      // Call button
                      _buildGradientButton(
                        label: 'Call',
                        icon: Icons.call_rounded,
                        colors: const [Color(0xFF4caf50), Color(0xFF2e7d32)],
                        onPressed: call,
                      ),
                    ],
                  ),
                ),

                const SizedBox(height: 30),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // ---------------------------------------------------------------
  // Active Call Card
  // ---------------------------------------------------------------

  Widget _buildActiveCallCard() {
    final callState = _callData['callState'] ?? 'Unknown';
    final remoteName = _callData['remoteName'] ?? '';
    final duration = _callData['duration'] ?? 0;
    final muted = _callData['muted'] == true;
    final speaker = _callData['speaker'] == true;
    final onHold = _callData['onHold'] == true;

    // Only show ticking timer once call is actually connected/streaming
    final bool isConnected = _isCallConnected(callState);
    final durationStr = isConnected
        ? _formatDuration(duration)
        : _preConnectionLabel(callState);

    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [Color(0xFF1b5e20), Color(0xFF2e7d32)],
        ),
        borderRadius: BorderRadius.circular(20),
        boxShadow: [
          BoxShadow(
            color: const Color(0xFF4caf50).withOpacity(0.3),
            blurRadius: 16,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.call, color: Colors.greenAccent, size: 22),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  remoteName.isNotEmpty ? remoteName : 'Call',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 20,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                  color: Colors.white.withOpacity(0.15),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  callState,
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            durationStr,
            style: const TextStyle(
              color: Colors.white70,
              fontSize: 28,
              fontWeight: FontWeight.w300,
              fontFamily: 'monospace',
            ),
          ),
          const SizedBox(height: 12),
          // Status indicators
          Wrap(
            spacing: 8,
            children: [
              if (muted) _buildStatusChip('Muted', Icons.mic_off),
              if (speaker) _buildStatusChip('Speaker', Icons.volume_up),
              if (onHold) _buildStatusChip('On Hold', Icons.pause),
            ],
          ),
          const SizedBox(height: 16),
          // Action buttons – row 1: Mute, Speaker, Hold
          Row(
            children: [
              Expanded(
                child: ElevatedButton.icon(
                  onPressed: toggleMute,
                  icon: Icon(muted ? Icons.mic : Icons.mic_off, size: 18),
                  label: Text(muted ? 'Unmute' : 'Mute'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.white.withOpacity(0.15),
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: ElevatedButton.icon(
                  onPressed: toggleSpeaker,
                  icon:
                      Icon(speaker ? Icons.hearing : Icons.volume_up, size: 18),
                  label: Text(speaker ? 'Earpiece' : 'Speaker'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.white.withOpacity(0.15),
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: ElevatedButton.icon(
                  onPressed: toggleHold,
                  icon: Icon(
                    onHold ? Icons.play_arrow : Icons.pause,
                    size: 18,
                  ),
                  label: Text(onHold ? 'Resume' : 'Hold'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: onHold
                        ? const Color(0xFFff9800).withOpacity(0.4)
                        : Colors.white.withOpacity(0.15),
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          // Action buttons – row 2: Transfer + Conference
          Row(
            children: [
              Expanded(
                child: ElevatedButton.icon(
                  onPressed: _showTransferDialog,
                  icon: const Icon(Icons.phone_forwarded_rounded, size: 18),
                  label: const Text('Transfer'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: const Color(0xFF4facfe).withOpacity(0.25),
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: ElevatedButton.icon(
                  onPressed: _showConferenceDialog,
                  icon: const Icon(Icons.groups_rounded, size: 18),
                  label: const Text('Conference'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: const Color(0xFF7c4dff).withOpacity(0.25),
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          // Action buttons – row 3: End Call (full width)
          SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              onPressed: hangUp,
              icon: const Icon(Icons.call_end, size: 18),
              label: const Text('End Call'),
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFFe53935),
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStatusChip(String label, IconData icon) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14, color: Colors.white70),
          const SizedBox(width: 4),
          Text(label,
              style: const TextStyle(color: Colors.white70, fontSize: 12)),
        ],
      ),
    );
  }

  String _formatDuration(dynamic seconds) {
    int s = (seconds is int) ? seconds : 0;
    int h = s ~/ 3600;
    int m = (s % 3600) ~/ 60;
    int sec = s % 60;
    if (h > 0) {
      return '${h.toString().padLeft(2, '0')}:${m.toString().padLeft(2, '0')}:${sec.toString().padLeft(2, '0')}';
    }
    return '${m.toString().padLeft(2, '0')}:${sec.toString().padLeft(2, '0')}';
  }

  /// Returns true when the call has moved past the ringing/dialing phase.
  bool _isCallConnected(String state) {
    const connectedStates = {
      'StreamsRunning',
      'Connected',
      'Paused',
      'PausedByRemote',
      'Resuming',
      'Updating',
      'UpdatedByRemote',
    };
    return connectedStates.contains(state);
  }

  /// Human-readable label for pre-connection call states.
  String _preConnectionLabel(String state) {
    switch (state) {
      case 'OutgoingInit':
        return 'Initiating…';
      case 'OutgoingProgress':
        return 'Calling…';
      case 'OutgoingRinging':
        return 'Ringing…';
      case 'OutgoingEarlyMedia':
        return 'Connecting…';
      case 'IncomingReceived':
        return 'Incoming…';
      case 'IncomingEarlyMedia':
        return 'Connecting…';
      default:
        return '00:00';
    }
  }

  // ---------------------------------------------------------------
  // UI Helpers
  // ---------------------------------------------------------------

  Widget _buildGlassCard({required Widget child}) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.06),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white.withOpacity(0.08)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.2),
            blurRadius: 20,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: child,
    );
  }

  Widget _buildTextField({
    required TextEditingController controller,
    required IconData icon,
    required String hint,
    bool obscure = false,
    TextInputType keyboardType = TextInputType.text,
  }) {
    return TextField(
      controller: controller,
      obscureText: obscure,
      keyboardType: keyboardType,
      style: const TextStyle(color: Colors.white, fontSize: 15),
      decoration: InputDecoration(
        prefixIcon: Icon(icon, color: Colors.white38, size: 22),
        hintText: hint,
        hintStyle: TextStyle(color: Colors.white.withOpacity(0.3)),
        filled: true,
        fillColor: Colors.white.withOpacity(0.07),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: Colors.white.withOpacity(0.1)),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: Colors.white.withOpacity(0.1)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: Color(0xFF4facfe)),
        ),
      ),
    );
  }

  Widget _buildGradientButton({
    required String label,
    required IconData icon,
    required VoidCallback onPressed,
    List<Color> colors = const [Color(0xFF4facfe), Color(0xFF00f2fe)],
  }) {
    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(14),
        gradient: LinearGradient(colors: colors),
        boxShadow: [
          BoxShadow(
            color: colors.first.withOpacity(0.35),
            blurRadius: 12,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: ElevatedButton.icon(
        onPressed: onPressed,
        icon: Icon(icon, size: 20),
        label: Text(label,
            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
        style: ElevatedButton.styleFrom(
          backgroundColor: Colors.transparent,
          shadowColor: Colors.transparent,
          padding: const EdgeInsets.symmetric(vertical: 14),
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        ),
      ),
    );
  }

  @override
  void dispose() {
    _stopCallTimer();
    _callDataSub?.cancel();
    _linphoneSdkPlugin.removeLoginListener();
    _userController.dispose();
    _passController.dispose();
    _domainController.dispose();
    _textEditingController.dispose();
    super.dispose();
  }
}

// ═══════════════════════════════════════════════════════════════════════
//  Transfer Bottom Sheet — Creative Tabbed Dialog
// ═══════════════════════════════════════════════════════════════════════

class _TransferSheet extends StatefulWidget {
  final LinphoneFlutterPlugin plugin;
  const _TransferSheet({required this.plugin});

  @override
  State<_TransferSheet> createState() => _TransferSheetState();
}

class _TransferSheetState extends State<_TransferSheet>
    with SingleTickerProviderStateMixin {
  late TabController _tabCtrl;
  final _blindController = TextEditingController();
  final _attendedController = TextEditingController();
  bool _consultationInProgress = false;
  bool _consultCallConnected = false;
  String _consultStatus = '';
  Timer? _consultPollTimer;

  @override
  void initState() {
    super.initState();
    _tabCtrl = TabController(length: 2, vsync: this);
  }

  void _startConsultPoll() {
    _consultPollTimer?.cancel();
    _consultPollTimer = Timer.periodic(
      const Duration(milliseconds: 800),
      (_) async {
        if (!mounted || !_consultationInProgress) {
          _consultPollTimer?.cancel();
          return;
        }
        final connected = await widget.plugin.isConsultCallConnected();
        if (mounted && connected && !_consultCallConnected) {
          setState(() {
            _consultCallConnected = true;
            _consultStatus = 'Connected \u2714';
          });
        }
      },
    );
  }

  void _stopConsultPoll() {
    _consultPollTimer?.cancel();
    _consultPollTimer = null;
  }

  @override
  void dispose() {
    _stopConsultPoll();
    _tabCtrl.dispose();
    _blindController.dispose();
    _attendedController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(top: 60),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFF1a1a2e), Color(0xFF16213e), Color(0xFF0f3460)],
        ),
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Drag handle
          const SizedBox(height: 12),
          Container(
            width: 40,
            height: 4,
            decoration: BoxDecoration(
              color: Colors.white24,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(height: 20),

          // Header
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: const Color(0xFF4facfe).withOpacity(0.15),
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.phone_forwarded_rounded,
                    color: Color(0xFF4facfe), size: 22),
              ),
              const SizedBox(width: 12),
              const Text(
                'Transfer Call',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            'Choose transfer type',
            style:
                TextStyle(color: Colors.white.withOpacity(0.5), fontSize: 14),
          ),
          const SizedBox(height: 20),

          // Tab bar
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 28),
            padding: const EdgeInsets.all(4),
            decoration: BoxDecoration(
              color: Colors.white.withOpacity(0.08),
              borderRadius: BorderRadius.circular(16),
            ),
            child: TabBar(
              controller: _tabCtrl,
              indicator: BoxDecoration(
                color: const Color(0xFF4facfe),
                borderRadius: BorderRadius.circular(12),
              ),
              indicatorSize: TabBarIndicatorSize.tab,
              labelColor: const Color(0xFF0d0d1a),
              unselectedLabelColor: Colors.white54,
              labelStyle:
                  const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
              unselectedLabelStyle:
                  const TextStyle(fontWeight: FontWeight.w500, fontSize: 15),
              dividerColor: Colors.transparent,
              tabs: const [
                Tab(text: 'Blind'),
                Tab(text: 'Attended'),
              ],
            ),
          ),
          const SizedBox(height: 24),

          // Tab content
          Flexible(
            child: TabBarView(
              controller: _tabCtrl,
              children: [
                _buildBlindTab(),
                _buildAttendedTab(),
              ],
            ),
          ),

          // Cancel button
          Padding(
            padding: const EdgeInsets.fromLTRB(28, 8, 28, 24),
            child: SizedBox(
              width: double.infinity,
              child: OutlinedButton(
                onPressed: () => Navigator.pop(context),
                style: OutlinedButton.styleFrom(
                  foregroundColor: const Color(0xFF4facfe),
                  side: const BorderSide(color: Color(0xFF4facfe)),
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
                child: const Text('Cancel', style: TextStyle(fontSize: 16)),
              ),
            ),
          ),
        ],
      ),
    );
  }

  // ── Blind Transfer Tab ─────────────────────────────────────────────

  Widget _buildBlindTab() {
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 28),
      child: Column(
        children: [
          // Info card
          _buildInfoCard(
            title: 'Blind Transfer',
            description:
                'Transfer the call immediately without speaking to the '
                'recipient first. The caller will be connected directly '
                'to the destination.',
            accentColor: const Color(0xFF4facfe),
            icon: Icons.flash_on_rounded,
          ),
          const SizedBox(height: 20),

          // Input
          _buildPhoneField(
            controller: _blindController,
            hint: 'Enter destination number',
          ),
          const SizedBox(height: 20),

          // Transfer button
          _buildGradientActionButton(
            label: 'Transfer Now',
            icon: Icons.phone_forwarded_rounded,
            colors: const [Color(0xFF4facfe), Color(0xFF00c6ff)],
            onPressed: () async {
              final dest = _blindController.text.trim();
              if (dest.isEmpty) return;
              final ok = await widget.plugin.blindTransfer(destination: dest);
              if (ok && mounted) Navigator.pop(context);
            },
          ),
          const SizedBox(height: 16),
        ],
      ),
    );
  }

  // ── Attended Transfer Tab ──────────────────────────────────────────

  Widget _buildAttendedTab() {
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 28),
      child: Column(
        children: [
          // Info card
          _buildInfoCard(
            title: 'Attended Transfer',
            description:
                'First speak with the recipient, then transfer the call. '
                'The original caller is placed on hold while you consult.',
            accentColor: const Color(0xFFFF9800),
            icon: Icons.people_alt_rounded,
          ),
          const SizedBox(height: 20),

          // Input
          _buildPhoneField(
            controller: _attendedController,
            hint: 'Enter consult number',
            enabled: !_consultationInProgress,
          ),
          const SizedBox(height: 16),

          // Consultation status
          if (_consultationInProgress) ...[
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              decoration: BoxDecoration(
                color: const Color(0xFFFF9800).withOpacity(0.12),
                borderRadius: BorderRadius.circular(12),
                border:
                    Border.all(color: const Color(0xFFFF9800).withOpacity(0.3)),
              ),
              child: Row(
                children: [
                  const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      valueColor:
                          AlwaysStoppedAnimation<Color>(Color(0xFFFF9800)),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      _consultStatus,
                      style: const TextStyle(
                          color: Color(0xFFFF9800), fontSize: 14),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
          ],

          // Action buttons
          if (!_consultationInProgress)
            _buildGradientActionButton(
              label: 'Start Consultation',
              icon: Icons.call_rounded,
              colors: const [Color(0xFFFF9800), Color(0xFFffc107)],
              onPressed: () async {
                final dest = _attendedController.text.trim();
                if (dest.isEmpty) return;
                final ok =
                    await widget.plugin.attendedTransfer(destination: dest);
                if (ok && mounted) {
                  setState(() {
                    _consultationInProgress = true;
                    _consultCallConnected = false;
                    _consultStatus = 'Consulting $dest…';
                  });
                  _startConsultPoll();
                }
              },
            ),
          if (_consultationInProgress) ...[
            _buildGradientActionButton(
              label: _consultCallConnected
                  ? 'Complete Transfer'
                  : 'Waiting for answer…',
              icon: _consultCallConnected
                  ? Icons.check_circle_outline_rounded
                  : Icons.hourglass_top_rounded,
              colors: _consultCallConnected
                  ? const [Color(0xFF4caf50), Color(0xFF2e7d32)]
                  : const [Color(0xFF555555), Color(0xFF444444)],
              onPressed: _consultCallConnected
                  ? () async {
                      final ok = await widget.plugin.completeAttendedTransfer();
                      if (ok && mounted) {
                        _stopConsultPoll();
                        Navigator.pop(context);
                      }
                    }
                  : null,
            ),
            const SizedBox(height: 10),
            _buildGradientActionButton(
              label: 'Cancel & Resume',
              icon: Icons.cancel_outlined,
              colors: const [Color(0xFFe53935), Color(0xFFb71c1c)],
              onPressed: () async {
                await widget.plugin.cancelAttendedTransfer();
                _stopConsultPoll();
                if (mounted) Navigator.pop(context);
              },
            ),
          ],
          const SizedBox(height: 16),
        ],
      ),
    );
  }

  // ── Shared widgets ─────────────────────────────────────────────────

  Widget _buildInfoCard({
    required String title,
    required String description,
    required Color accentColor,
    required IconData icon,
  }) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.05),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: accentColor.withOpacity(0.2)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 3,
                height: 18,
                decoration: BoxDecoration(
                  color: accentColor,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              const SizedBox(width: 8),
              Icon(icon, color: accentColor, size: 18),
              const SizedBox(width: 6),
              Text(
                title,
                style: TextStyle(
                  color: accentColor,
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            description,
            style: TextStyle(
              color: Colors.white.withOpacity(0.6),
              fontSize: 13,
              height: 1.4,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPhoneField({
    required TextEditingController controller,
    required String hint,
    bool enabled = true,
  }) {
    return TextField(
      controller: controller,
      enabled: enabled,
      keyboardType: TextInputType.phone,
      textAlign: TextAlign.center,
      style: const TextStyle(
        color: Colors.white,
        fontSize: 18,
        fontFamily: 'monospace',
      ),
      decoration: InputDecoration(
        hintText: hint,
        hintStyle: TextStyle(color: Colors.white.withOpacity(0.25)),
        filled: true,
        fillColor: Colors.white.withOpacity(0.07),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(color: Colors.white.withOpacity(0.1)),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(color: Colors.white.withOpacity(0.1)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: Color(0xFF4facfe)),
        ),
        disabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(color: Colors.white.withOpacity(0.05)),
        ),
        prefixIcon:
            const Icon(Icons.dialpad_rounded, color: Colors.white38, size: 20),
      ),
    );
  }

  Widget _buildGradientActionButton({
    required String label,
    required IconData icon,
    required List<Color> colors,
    VoidCallback? onPressed,
  }) {
    final disabled = onPressed == null;
    return Opacity(
      opacity: disabled ? 0.45 : 1.0,
      child: Container(
        width: double.infinity,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          gradient: LinearGradient(colors: colors),
          boxShadow: [
            BoxShadow(
              color: colors.first.withOpacity(0.35),
              blurRadius: 12,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: ElevatedButton.icon(
          onPressed: onPressed,
          icon: Icon(icon, size: 20),
          label: Text(label,
              style:
                  const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.transparent,
            shadowColor: Colors.transparent,
            padding: const EdgeInsets.symmetric(vertical: 14),
            shape:
                RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          ),
        ),
      ),
    );
  }
}

// ═══════════════════════════════════════════════════════════════════════
//  Conference Bottom Sheet — Creative Dialog
// ═══════════════════════════════════════════════════════════════════════

class _ConferenceSheet extends StatefulWidget {
  final LinphoneFlutterPlugin plugin;
  const _ConferenceSheet({required this.plugin});

  @override
  State<_ConferenceSheet> createState() => _ConferenceSheetState();
}

class _ConferenceSheetState extends State<_ConferenceSheet> {
  final _addController = TextEditingController();
  bool _inConference = false;
  int _participantCount = 0;
  List<String> _participants = [];
  int _callCount = 0;
  String _status = '';
  Timer? _pollTimer;

  @override
  void initState() {
    super.initState();
    _refresh();
    _pollTimer = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _addController.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (!mounted) return;
    final inConf = await widget.plugin.isInConference();
    final count =
        inConf ? await widget.plugin.getConferenceParticipantCount() : 0;
    final parts =
        inConf ? await widget.plugin.getConferenceParticipants() : <String>[];
    final calls = await widget.plugin.getCallCount();
    if (!mounted) return;
    setState(() {
      _inConference = inConf;
      _participantCount = count;
      _participants = parts;
      _callCount = calls;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints:
          BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.85),
      decoration: const BoxDecoration(
        color: Color(0xFF1a1a2e),
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
        boxShadow: [
          BoxShadow(color: Colors.black54, blurRadius: 20, spreadRadius: 5),
        ],
      ),
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 16, 24, 28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Drag handle
            Center(
              child: Container(
                width: 42,
                height: 4,
                decoration: BoxDecoration(
                  color: Colors.white24,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Header
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: const LinearGradient(
                      colors: [Color(0xFF7c4dff), Color(0xFFb388ff)],
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: const Color(0xFF7c4dff).withOpacity(0.4),
                        blurRadius: 12,
                        offset: const Offset(0, 4),
                      ),
                    ],
                  ),
                  child: const Icon(Icons.groups_rounded,
                      color: Colors.white, size: 26),
                ),
                const SizedBox(width: 14),
                const Expanded(
                  child: Text(
                    'Conference Call',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 22,
                      fontWeight: FontWeight.bold,
                      letterSpacing: 0.3,
                    ),
                  ),
                ),
                GestureDetector(
                  onTap: () => Navigator.pop(context),
                  child: Container(
                    padding: const EdgeInsets.all(6),
                    decoration: BoxDecoration(
                      color: Colors.white10,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: const Icon(Icons.close,
                        color: Colors.white54, size: 20),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              'Merge calls into a group conversation',
              style: TextStyle(
                color: Colors.white.withOpacity(0.5),
                fontSize: 14,
              ),
            ),
            const SizedBox(height: 20),

            // Status chip
            if (_status.isNotEmpty || _inConference || _callCount >= 2)
              Container(
                width: double.infinity,
                padding:
                    const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(12),
                  color: _inConference
                      ? const Color(0xFF4caf50).withOpacity(0.15)
                      : _callCount >= 2
                          ? const Color(0xFFffc107).withOpacity(0.15)
                          : Colors.white.withOpacity(0.05),
                  border: Border.all(
                    color: _inConference
                        ? const Color(0xFF4caf50).withOpacity(0.3)
                        : _callCount >= 2
                            ? const Color(0xFFffc107).withOpacity(0.3)
                            : Colors.white12,
                  ),
                ),
                child: Row(
                  children: [
                    Icon(
                      _inConference ? Icons.check_circle : Icons.info_outline,
                      color: _inConference
                          ? const Color(0xFF4caf50)
                          : const Color(0xFFffc107),
                      size: 18,
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        _inConference
                            ? 'Conference active — $_participantCount participants'
                            : _callCount >= 2
                                ? 'Multiple calls detected — ready to merge'
                                : _status.isNotEmpty
                                    ? _status
                                    : 'Add a participant to start',
                        style: TextStyle(
                          color: _inConference
                              ? const Color(0xFF4caf50)
                              : _callCount >= 2
                                  ? const Color(0xFFffc107)
                                  : Colors.white54,
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            const SizedBox(height: 16),

            // Participant list
            if (_inConference) ...[
              _buildParticipantTile('You (local)', isLocal: true),
              ..._participants.map((p) => _buildParticipantTile(p)).toList(),
              const SizedBox(height: 16),
            ],

            // If 2+ calls but no conference yet, show the calls
            if (!_inConference && _callCount >= 2) ...[
              Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(12),
                  color: Colors.white.withOpacity(0.05),
                ),
                child: Text(
                  '$_callCount calls ready to merge',
                  style: const TextStyle(
                    color: Colors.white70,
                    fontSize: 14,
                  ),
                ),
              ),
              const SizedBox(height: 16),
            ],

            // Add participant input
            Container(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(14),
                color: Colors.white.withOpacity(0.07),
                border: Border.all(color: Colors.white.withOpacity(0.1)),
              ),
              child: TextField(
                controller: _addController,
                style: const TextStyle(color: Colors.white, fontSize: 15),
                decoration: InputDecoration(
                  hintText: 'Add participant (SIP / number)',
                  hintStyle: TextStyle(color: Colors.white.withOpacity(0.3)),
                  prefixIcon: const Icon(Icons.person_add_rounded,
                      color: Colors.white38, size: 22),
                  border: InputBorder.none,
                  contentPadding:
                      const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Action buttons
            if (_inConference) ...[
              _buildConfButton(
                label: 'Add Participant',
                icon: Icons.person_add_rounded,
                colors: const [Color(0xFF4facfe), Color(0xFF00f2fe)],
                onPressed: () async {
                  final dest = _addController.text.trim();
                  if (dest.isEmpty) return;
                  final ok =
                      await widget.plugin.addToConference(destination: dest);
                  if (ok) {
                    _addController.clear();
                    setState(() => _status = 'Calling $dest…');
                    // Auto-merge after a delay
                    await Future.delayed(const Duration(seconds: 3));
                    await widget.plugin.mergeCallsToConference();
                    _refresh();
                  }
                },
              ),
              const SizedBox(height: 10),
              _buildConfButton(
                label: 'End Conference',
                icon: Icons.call_end_rounded,
                colors: const [Color(0xFFe53935), Color(0xFFb71c1c)],
                onPressed: () async {
                  await widget.plugin.endConference();
                  if (mounted) Navigator.pop(context);
                },
              ),
            ] else if (_callCount >= 2) ...[
              _buildConfButton(
                label: 'Merge All Calls',
                icon: Icons.merge_rounded,
                colors: const [Color(0xFF4caf50), Color(0xFF2e7d32)],
                onPressed: () async {
                  final ok = await widget.plugin.startConference();
                  if (ok) {
                    setState(() => _status = 'Conference started!');
                    _refresh();
                  }
                },
              ),
              const SizedBox(height: 10),
              _buildConfButton(
                label: 'Add Participant',
                icon: Icons.person_add_rounded,
                colors: const [Color(0xFF4facfe), Color(0xFF00f2fe)],
                onPressed: () async {
                  final dest = _addController.text.trim();
                  if (dest.isEmpty) return;
                  await widget.plugin.addToConference(destination: dest);
                  _addController.clear();
                  _refresh();
                },
              ),
            ] else ...[
              _buildConfButton(
                label: 'Call & Add to Conference',
                icon: Icons.add_call,
                colors: const [Color(0xFFFF9800), Color(0xFFffc107)],
                onPressed: () async {
                  final dest = _addController.text.trim();
                  if (dest.isEmpty) return;
                  final ok =
                      await widget.plugin.addToConference(destination: dest);
                  if (ok) {
                    _addController.clear();
                    setState(() => _status = 'Calling $dest…');
                  }
                },
              ),
            ],
            const SizedBox(height: 16),
          ],
        ),
      ),
    );
  }

  Widget _buildParticipantTile(String name, {bool isLocal = false}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          color: isLocal
              ? const Color(0xFF4facfe).withOpacity(0.1)
              : Colors.white.withOpacity(0.05),
          border: Border.all(
            color: isLocal
                ? const Color(0xFF4facfe).withOpacity(0.2)
                : Colors.white10,
          ),
        ),
        child: Row(
          children: [
            CircleAvatar(
              radius: 18,
              backgroundColor:
                  isLocal ? const Color(0xFF4facfe) : const Color(0xFF7c4dff),
              child: Text(
                name.isNotEmpty ? name[0].toUpperCase() : '?',
                style: const TextStyle(
                    color: Colors.white,
                    fontSize: 14,
                    fontWeight: FontWeight.bold),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                name,
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 15,
                  fontWeight: isLocal ? FontWeight.bold : FontWeight.normal,
                ),
              ),
            ),
            if (isLocal)
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: const Color(0xFF4facfe).withOpacity(0.1),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Text(
                  'HOST',
                  style: TextStyle(
                    color: Color(0xFF4facfe),
                    fontSize: 10,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildConfButton({
    required String label,
    required IconData icon,
    required List<Color> colors,
    VoidCallback? onPressed,
  }) {
    final disabled = onPressed == null;
    return Opacity(
      opacity: disabled ? 0.45 : 1.0,
      child: Container(
        width: double.infinity,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          gradient: LinearGradient(colors: colors),
          boxShadow: [
            BoxShadow(
              color: colors.first.withOpacity(0.35),
              blurRadius: 12,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: ElevatedButton.icon(
          onPressed: onPressed,
          icon: Icon(icon, size: 20),
          label: Text(label,
              style:
                  const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.transparent,
            shadowColor: Colors.transparent,
            padding: const EdgeInsets.symmetric(vertical: 14),
            shape:
                RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          ),
        ),
      ),
    );
  }
}
