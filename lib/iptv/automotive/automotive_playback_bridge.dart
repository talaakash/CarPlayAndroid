import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:iptv_native/iptv_native.dart';

import '../services/playback_controller.dart';

/// Single place for remote-control commands from the phone, plus bookkeeping of where the
/// latest playback change came from. All commands go to the one [PlaybackController] (and
/// thus the one native engine); state flows back from the engine only, so a command from the
/// car can never be re-sent to the car by this app.
class AutomotivePlaybackBridge extends ChangeNotifier {
  AutomotivePlaybackBridge(this._playback, this._native) {
    _sub = _native.playbackState.listen(_onState);
  }

  final PlaybackController _playback;
  final IptvNative _native;
  StreamSubscription<NativePlaybackState>? _sub;

  int _lastVersion = -1;
  CommandOrigin _origin = CommandOrigin.phone;
  CommandOrigin get lastOrigin => _origin;
  int get lastVersion => _lastVersion;

  /// Returns false for stale/duplicate events (older or equal version).
  @visibleForTesting
  bool accept(NativePlaybackState s) {
    if (s.version != 0 && s.version <= _lastVersion) return false;
    if (s.version != 0) _lastVersion = s.version;
    return true;
  }

  void _onState(NativePlaybackState s) {
    if (!accept(s)) return;
    if (s.origin != _origin) {
      _origin = s.origin;
      notifyListeners();
    }
  }

  Future<void> togglePlay() => _playback.togglePlay();
  Future<void> next() => _playback.next();
  Future<void> previous() => _playback.previous();
  Future<void> stop() => _playback.stop();

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }
}
