import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:iptv_native/iptv_native.dart';

enum CarLinkStatus { checking, disconnected, androidAuto, androidAutomotive, carPlay }

/// Car connection as confirmed by the platform. It never reports "connected" on its own:
/// Android uses `CarConnection` (Android Auto / Automotive OS), iOS uses the CarPlay scene
/// lifecycle. Neither platform exposes a "connecting" phase, so before the first answer the
/// status is [CarLinkStatus.checking].
class CarConnectionService extends ChangeNotifier {
  CarConnectionService(this._native);
  final IptvNative _native;
  StreamSubscription<CarConnectionInfo>? _sub;

  CarLinkStatus _status = CarLinkStatus.checking;
  CarLinkStatus get status => _status;

  void start() {
    _sub = _native.carConnection.listen(_apply);
    _native.currentConnection().then(_apply).catchError((Object _) {});
  }

  void _apply(CarConnectionInfo info) {
    final next = !info.known
        ? CarLinkStatus.checking
        : !info.connected
            ? CarLinkStatus.disconnected
            : switch (info.platform) {
                CarPlatform.androidAuto => CarLinkStatus.androidAuto,
                CarPlatform.androidAutomotive => CarLinkStatus.androidAutomotive,
                CarPlatform.carPlay => CarLinkStatus.carPlay,
                CarPlatform.none => CarLinkStatus.disconnected,
              };
    if (next != _status) {
      _status = next;
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }
}
