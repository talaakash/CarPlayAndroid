import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:iptv_native/iptv_native.dart';

/// Mirrors the native parked-video policy: whether video may be shown now and
/// whether playback may be resumed. The decision is made natively (Android) or by
/// the system (CarPlay); this class only reports it and never decides on its own.
class ParkedVideoService extends ChangeNotifier {
  ParkedVideoInfo _info = const ParkedVideoInfo();
  StreamSubscription<ParkedVideoInfo>? _sub;
  bool _disposed = false;

  ParkedVideoInfo get info => _info;

  Future<void> start() async {
    _sub = IptvNative.instance.parkedVideo.listen(_set, onError: (Object _) {});
    _set(await IptvNative.instance.currentParkedVideo());
  }

  void _set(ParkedVideoInfo i) {
    if (_disposed) return;
    _info = i;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _sub?.cancel();
    super.dispose();
  }
}
