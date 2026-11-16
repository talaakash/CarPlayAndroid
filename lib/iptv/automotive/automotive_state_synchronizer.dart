import 'package:flutter/foundation.dart';
import 'package:iptv_native/iptv_native.dart';

import '../services/playback_controller.dart';
import 'automotive_navigation_service.dart';
import 'automotive_playback_bridge.dart';
import 'car_connection_service.dart';

/// What the phone shows about the car link: connection status, the playing channel and
/// who last controlled playback.
class AutomotiveSnapshot {
  const AutomotiveSnapshot({
    required this.link,
    required this.channelId,
    required this.channelName,
    required this.origin,
  });
  final CarLinkStatus link;
  final String? channelId;
  final String? channelName;
  final CommandOrigin origin;
}

/// Aggregates the automotive services and the shared playback state.
/// Every field comes from the single source of truth (the native engine).
class AutomotiveStateSynchronizer extends ChangeNotifier {
  AutomotiveStateSynchronizer({
    required this.connection,
    required this.navigation,
    required this.bridge,
    required PlaybackController playback,
  }) : _playback = playback {
    for (final l in [connection, bridge, playback]) {
      l.addListener(notifyListeners);
    }
  }

  final CarConnectionService connection;
  final AutomotiveNavigationService navigation;
  final AutomotivePlaybackBridge bridge;
  final PlaybackController _playback;

  AutomotiveSnapshot get snapshot => AutomotiveSnapshot(
        link: connection.status,
        channelId: _playback.state.channelId,
        channelName: _playback.current?.name,
        origin: bridge.lastOrigin,
      );

  void start() {
    connection.start();
    navigation.start();
  }

  @override
  void dispose() {
    for (final l in [connection, bridge, _playback]) {
      l.removeListener(notifyListeners);
    }
    navigation.dispose();
    super.dispose();
  }
}
