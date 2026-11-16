import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:iptv_native/iptv_native.dart';

/// Mirror of the native player settings (video output + fit/fill). The native layer owns the
/// value, so the phone UI and Android Auto always show and apply the same one; changes made
/// on either side arrive through [IptvNative.playerSettings].
class PlayerPrefsService extends ChangeNotifier {
  PlayerPrefsService([IptvNative? native]) : _native = native ?? IptvNative.instance;
  final IptvNative _native;
  StreamSubscription<PlayerSettings>? _sub;
  bool _disposed = false;

  PlayerSettings _settings = const PlayerSettings();
  VideoOutput get output => _settings.output;
  VideoFit get fit => _settings.fit;

  Future<void> start() async {
    _sub = _native.playerSettings.listen(_apply, onError: (Object _) {});
    try {
      _apply(await _native.currentPlayerSettings());
    } catch (_) {}
  }

  void _apply(PlayerSettings s) {
    if (_disposed) return;
    if (s.output == _settings.output && s.fit == _settings.fit) return;
    _settings = s;
    notifyListeners();
  }

  Future<void> setOutput(VideoOutput o) async {
    _apply(PlayerSettings(output: o, fit: fit)); // optimistic; native echoes the same value
    await _native.setVideoOutput(o).catchError((Object _) {});
  }

  Future<void> setFit(VideoFit f) async {
    _apply(PlayerSettings(output: output, fit: f));
    await _native.setVideoFit(f).catchError((Object _) {});
  }

  @override
  void dispose() {
    _disposed = true;
    _sub?.cancel();
    super.dispose();
  }
}
