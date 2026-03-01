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
        setState(() => _callData = data);
      }
    }, onError: (e) {
      print("Call data stream error: $e");
    });
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

    final durationStr = _formatDuration(duration);

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
          // Action buttons
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
                  onPressed: hangUp,
                  icon: const Icon(Icons.call_end, size: 18),
                  label: const Text('End'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: const Color(0xFFe53935),
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
    _callDataSub?.cancel();
    _linphoneSdkPlugin.removeLoginListener();
    _userController.dispose();
    _passController.dispose();
    _domainController.dispose();
    _textEditingController.dispose();
    super.dispose();
  }
}
