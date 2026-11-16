import 'dart:async';

import 'package:flutter/services.dart';

/// Playback status reported by the native engine (the single engine shared by
/// the Flutter UI, Android Auto and CarPlay).
enum NativePlaybackStatus { idle, buffering, ready, ended, error }

/// Source of a playback command: this phone, the car (Android Auto or CarPlay UI), or
/// system controls (notification, Bluetooth, steering-wheel keys, assistant).
enum CommandOrigin {
  phone,
  car,
  system;

  static CommandOrigin parse(String? v) =>
      CommandOrigin.values.firstWhere((o) => o.name == v, orElse: () => CommandOrigin.phone);
}

enum CarPlatform { none, androidAuto, androidAutomotive, carPlay }

/// Connection as reported by the platform (CarConnection on Android, CarPlay scene on iOS).
/// Whether video may be shown right now. `playbackBlocked` means the platform says
/// playback must not be resumed (driving, and no background audio while driving).
/// On iOS the system decides; `videoAllowed` stays true and `carPlayVideo` reports
/// whether the connected CarPlay system supports video.
class ParkedVideoInfo {
  const ParkedVideoInfo({
    this.automotive = false,
    this.videoAllowed = true,
    this.audioWhileDriving = false,
    this.playbackBlocked = false,
    this.simulated = false,
  });
  final bool automotive;
  final bool videoAllowed;
  final bool audioWhileDriving;
  final bool playbackBlocked;
  final bool simulated;

  factory ParkedVideoInfo.fromMap(Map<Object?, Object?> m) => ParkedVideoInfo(
        automotive: m['automotive'] == true,
        videoAllowed: m['videoAllowed'] != false,
        audioWhileDriving: m['audioWhileDriving'] == true,
        playbackBlocked: m['playbackBlocked'] == true,
        simulated: m['simulated'] == true,
      );
}

class CarConnectionInfo {
  const CarConnectionInfo({this.known = false, this.connected = false, this.platform = CarPlatform.none});
  final bool known;
  final bool connected;
  final CarPlatform platform;

  factory CarConnectionInfo.fromMap(Map<Object?, Object?> m) => CarConnectionInfo(
        known: m['known'] as bool? ?? false,
        connected: m['connected'] as bool? ?? false,
        platform: CarPlatform.values.firstWhere(
          (p) => p.name == m['platform'],
          orElse: () => CarPlatform.none,
        ),
      );
}

/// What is being browsed: `all`, `favorites`, `recents` or `cat:<group>`.
class BrowseSelection {
  const BrowseSelection(this.scope, this.label, this.version, this.origin);
  final String scope;
  final String label;
  final int version;
  final CommandOrigin origin;

  factory BrowseSelection.fromMap(Map<Object?, Object?> m) => BrowseSelection(
        m['scope'] as String? ?? 'all',
        m['label'] as String? ?? 'All channels',
        (m['version'] as num?)?.toInt() ?? 0,
        CommandOrigin.parse(m['origin'] as String?),
      );
}

class NativePlaybackState {
  const NativePlaybackState({
    this.channelId,
    this.status = NativePlaybackStatus.idle,
    this.isPlaying = false,
    this.errorMessage,
    this.videoWidth = 0,
    this.videoHeight = 0,
    this.inPictureInPicture = false,
    this.version = 0,
    this.origin = CommandOrigin.phone,
  });

  final String? channelId;
  final NativePlaybackStatus status;
  final bool isPlaying;
  final String? errorMessage;
  final int videoWidth;
  final int videoHeight;
  final bool inPictureInPicture;

  /// Monotonic counter stamped by the native layer; lets receivers drop stale/duplicate events.
  final int version;

  /// Who issued the most recent playback command.
  final CommandOrigin origin;

  double get aspectRatio =>
      (videoWidth > 0 && videoHeight > 0) ? videoWidth / videoHeight : 16 / 9;

  factory NativePlaybackState.fromMap(Map<Object?, Object?> m) {
    return NativePlaybackState(
      channelId: m['channelId'] as String?,
      status: NativePlaybackStatus.values.firstWhere(
        (s) => s.name == m['status'],
        orElse: () => NativePlaybackStatus.idle,
      ),
      isPlaying: m['isPlaying'] as bool? ?? false,
      errorMessage: m['error'] as String?,
      videoWidth: (m['videoWidth'] as num?)?.toInt() ?? 0,
      videoHeight: (m['videoHeight'] as num?)?.toInt() ?? 0,
      inPictureInPicture: m['pip'] as bool? ?? false,
      version: (m['version'] as num?)?.toInt() ?? 0,
      origin: CommandOrigin.parse(m['origin'] as String?),
    );
  }
}

/// A channel handed to the native engine.
class NativeChannel {
  const NativeChannel({
    required this.id,
    required this.name,
    required this.url,
    this.logo = '',
    this.group = '',
  });
  final String id;
  final String name;
  final String url;
  final String logo;
  final String group;

  Map<String, Object?> toMap() =>
      {'id': id, 'name': name, 'url': url, 'logo': logo, 'group': group};
}

/// Dart facade over the native engine. Methods are no-ops / return defaults on
/// platforms without a native implementation.
class IptvNative {
  IptvNative._();
  static final IptvNative instance = IptvNative._();

  static const viewType = 'iptv_native/video_view';
  static const _method = MethodChannel('iptv_native/control');
  static const _events = EventChannel('iptv_native/events');

  Stream<Map<Object?, Object?>>? _stream;

  Stream<Map<Object?, Object?>> get _raw => _stream ??= _events
      .receiveBroadcastStream()
      .map((e) => Map<Object?, Object?>.from(e as Map))
      .asBroadcastStream();

  /// Playback state changes.
  Stream<NativePlaybackState> get playbackState => _raw
      .where((e) => e['type'] == 'state')
      .map(NativePlaybackState.fromMap);

  Stream<CarConnectionInfo> get carConnection => _raw
      .where((e) => e['type'] == 'connection')
      .map(CarConnectionInfo.fromMap);

  Stream<BrowseSelection> get browseSelection => _raw
      .where((e) => e['type'] == 'selection')
      .map(BrowseSelection.fromMap);

  Stream<ParkedVideoInfo> get parkedVideo => _raw
      .where((e) => e['type'] == 'parkedVideo')
      .map(ParkedVideoInfo.fromMap);

  Future<ParkedVideoInfo> currentParkedVideo() async {
    try {
      final m = await _method.invokeMethod<Map<Object?, Object?>>('parkedVideo');
      return m == null ? const ParkedVideoInfo() : ParkedVideoInfo.fromMap(m);
    } on MissingPluginException {
      return const ParkedVideoInfo();
    }
  }

  /// Debug builds only (the native side refuses otherwise): runs the real
  /// driving-restriction code path without a car. mode: 'off' | 'audioOk' | 'noAudio'.
  Future<bool> debugSimulateDriving(String mode) async =>
      await _method.invokeMethod<bool>('debugSimulateDriving', {'mode': mode}) ?? false;

  Future<CarConnectionInfo> currentConnection() async {
    final m = await _method.invokeMethod<Map<Object?, Object?>>('connection');
    return m == null ? const CarConnectionInfo() : CarConnectionInfo.fromMap(m);
  }

  Future<BrowseSelection> currentSelection() async {
    final m = await _method.invokeMethod<Map<Object?, Object?>>('selection');
    return BrowseSelection.fromMap(m ?? const {});
  }

  /// The phone changed category; the car's "Phone" list follows.
  Future<void> setSelection(String scope, String label) =>
      _method.invokeMethod('setSelection', {'scope': scope, 'label': label});

  /// Fired when favorites / recents were changed natively (e.g. from the car).
  Stream<void> get userDataChanged =>
      _raw.where((e) => e['type'] == 'userData').map((_) {});

  /// Directory shared with the native Android Auto / CarPlay code.
  Future<String> storageDirectory() async =>
      (await _method.invokeMethod<String>('storageDirectory'))!;

  Future<NativePlaybackState> currentState() async {
    final m = await _method.invokeMethod<Map<Object?, Object?>>('state');
    return m == null ? const NativePlaybackState() : NativePlaybackState.fromMap(m);
  }

  /// Starts [channel]. [queue] is the ordered list used for next/previous.
  Future<void> play(NativeChannel channel, List<NativeChannel> queue) =>
      _method.invokeMethod('play', {
        'channel': channel.toMap(),
        'queue': queue.map((c) => c.toMap()).toList(),
      });

  Future<void> resume() => _method.invokeMethod('resume');
  Future<void> pause() => _method.invokeMethod('pause');
  Future<void> stop() => _method.invokeMethod('stop');
  Future<void> next() => _method.invokeMethod('next');
  Future<void> previous() => _method.invokeMethod('previous');

  /// Re-prepares the current stream (used for retry after a failure).
  Future<void> retry() => _method.invokeMethod('retry');

  /// Tells the native side the channel/favorite/recent files changed so the car
  /// UIs refresh their lists.
  Future<void> notifyDataChanged() => _method.invokeMethod('dataChanged');

  /// Clears the recently watched list (owned by the native side).
  Future<void> clearRecents() => _method.invokeMethod('clearRecents');

  Future<bool> isPictureInPictureSupported() async =>
      await _method.invokeMethod<bool>('pipSupported') ?? false;

  Future<void> enterPictureInPicture() => _method.invokeMethod('enterPip');

  /// When true the app enters PiP automatically as it goes to background.
  Future<void> setAutoPictureInPicture(bool enabled) =>
      _method.invokeMethod('setAutoPip', {'enabled': enabled});
}
