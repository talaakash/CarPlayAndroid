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

/// Where the video is rendered. The other surface shows channel artwork and controls only.
enum VideoOutput {
  phone,
  car;

  static VideoOutput parse(String? v) =>
      VideoOutput.values.firstWhere((o) => o.name == v, orElse: () => VideoOutput.phone);
}

/// Fit = whole picture with bars, fill = cover the screen (crop). Shared by phone and car.
enum VideoFit {
  fit,
  fill;

  static VideoFit parse(String? v) =>
      VideoFit.values.firstWhere((o) => o.name == v, orElse: () => VideoFit.fit);
}

/// Player settings kept natively so the phone and the car always agree.
class PlayerSettings {
  const PlayerSettings({this.output = VideoOutput.phone, this.fit = VideoFit.fit});
  final VideoOutput output;
  final VideoFit fit;

  factory PlayerSettings.fromMap(Map<Object?, Object?> m) =>
      PlayerSettings(output: VideoOutput.parse(m['output'] as String?), fit: VideoFit.parse(m['fit'] as String?));
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
    this.isLocal = false,
    this.positionMs = 0,
    this.durationMs = -1,
  });

  /// True when the current item is a local video (content:// / file:// URI), not an IPTV channel.
  final bool isLocal;
  final int positionMs;

  /// -1 when unknown (live streams, not yet loaded).
  final int durationMs;

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
      isLocal: m['isLocal'] as bool? ?? false,
      positionMs: (m['positionMs'] as num?)?.toInt() ?? 0,
      durationMs: (m['durationMs'] as num?)?.toInt() ?? -1,
    );
  }
}

/// A video file picked from device storage (metadata only; the bytes stay where they are).
class PickedVideo {
  const PickedVideo({required this.uri, required this.name, this.durationMs = -1, this.sizeBytes = -1, this.width = 0, this.height = 0});
  final String uri;
  final String name;
  final int durationMs;
  final int sizeBytes;
  final int width;
  final int height;

  factory PickedVideo.fromMap(Map<Object?, Object?> m) => PickedVideo(
        uri: m['uri'] as String,
        name: (m['name'] as String?) ?? 'Video',
        durationMs: (m['durationMs'] as num?)?.toInt() ?? -1,
        sizeBytes: (m['sizeBytes'] as num?)?.toInt() ?? -1,
        width: (m['width'] as num?)?.toInt() ?? 0,
        height: (m['height'] as num?)?.toInt() ?? 0,
      );
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

/// Result of [IptvNative.sendBrowserUrl].
enum SendToCarResult {
  /// The car confirmed it is showing the page.
  sent,

  /// A car is connected but the car app is not open yet; the URL opens as soon as it is.
  queued,

  /// Sent, but the car did not show it within a few seconds (car app not in front, no surface).
  unconfirmed,

  /// Same URL was just sent (or the car already shows it): nothing was done.
  duplicate,

  /// No car is connected.
  noCar,

  /// Not an http(s) URL.
  invalid;

  static SendToCarResult parse(String? v) => switch (v) {
        'sent' => sent,
        'queued' => queued,
        'unconfirmed' => unconfirmed,
        'duplicate' => duplicate,
        'invalid' => invalid,
        _ => noCar,
      };
}

/// What the car's browser is showing (reported by the car app).
class CarBrowserState {
  const CarBrowserState({this.open = false, this.url = '', this.title = '', this.loading = false});
  final bool open;
  final String url;
  final String title;
  final bool loading;

  factory CarBrowserState.fromMap(Map<Object?, Object?> m) => CarBrowserState(
        open: m['open'] as bool? ?? false,
        url: m['url'] as String? ?? '',
        title: m['title'] as String? ?? '',
        loading: m['loading'] as bool? ?? false,
      );
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

  /// Output / fit changes, from the phone or the car.
  Stream<PlayerSettings> get playerSettings => _raw
      .where((e) => e['type'] == 'settings')
      .map(PlayerSettings.fromMap);

  Future<PlayerSettings> currentPlayerSettings() async {
    try {
      final m = await _method.invokeMethod<Map<Object?, Object?>>('settings');
      return m == null ? const PlayerSettings() : PlayerSettings.fromMap(m);
    } on MissingPluginException {
      return const PlayerSettings();
    }
  }

  Future<void> setVideoOutput(VideoOutput o) => _method.invokeMethod('setOutput', {'output': o.name});
  Future<bool> getAllowUnknownSpeed() async {
    try {
      return await _method.invokeMethod<bool>('getAllowUnknownSpeed') ?? false;
    } on MissingPluginException {
      return false;
    }
  }

  Future<void> setAllowUnknownSpeed(bool allow) =>
      _method.invokeMethod('setAllowUnknownSpeed', {'allow': allow});

  Future<void> setVideoFit(VideoFit f) => _method.invokeMethod('setFit', {'fit': f.name});

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

  /// Opens the system file picker (multi-select video). Returns [] when cancelled or unsupported.
  Future<List<PickedVideo>> pickVideos() async {
    try {
      final l = await _method.invokeListMethod<Map<Object?, Object?>>('pickVideos');
      return [for (final m in l ?? const <Map<Object?, Object?>>[]) PickedVideo.fromMap(m)];
    } on MissingPluginException {
      return const [];
    }
  }

  /// Name / duration of a previously picked URI, or null when it can no longer be read.
  Future<PickedVideo?> describeVideo(String uri) async {
    final m = await _method.invokeMapMethod<Object?, Object?>('describeVideo', {'uri': uri});
    return m == null ? null : PickedVideo.fromMap(m);
  }

  /// JPEG thumbnail bytes, or null when there is no decodable frame.
  Future<Uint8List?> videoThumbnail(String uri) =>
      _method.invokeMethod<Uint8List>('videoThumbnail', {'uri': uri});

  /// Drops the persisted read grant of a removed library entry.
  Future<void> releaseVideoUri(String uri) => _method.invokeMethod('releaseVideoUri', {'uri': uri});

  Future<void> seekTo(int positionMs) => _method.invokeMethod('seekTo', {'positionMs': positionMs});

  /// Current position / duration (ms, duration -1 if unknown).
  Future<({int positionMs, int durationMs})> position() async {
    final m = await _method.invokeMapMethod<String, Object?>('position');
    return (positionMs: (m?['positionMs'] as num?)?.toInt() ?? 0, durationMs: (m?['durationMs'] as num?)?.toInt() ?? -1);
  }

  /// Phone -> car: opens [url] in the car's browser. See [SendToCarResult].
  Future<SendToCarResult> sendBrowserUrl(String url) async {
    try {
      return SendToCarResult.parse(await _method.invokeMethod<String>('sendBrowserUrl', {'url': url}));
    } on MissingPluginException {
      return SendToCarResult.noCar;
    }
  }

  /// Phone -> car, background: the car browser follows [url] without taking over the car screen.
  /// Remembered, so a car that connects or reconnects later starts on the same page.
  Future<SendToCarResult> syncBrowserUrl(String url) async {
    try {
      return SendToCarResult.parse(await _method.invokeMethod<String>('syncBrowserUrl', {'url': url}));
    } catch (_) {
      return SendToCarResult.noCar;
    }
  }

  /// Car -> phone: the car browser's current page (also emitted when it changes).
  Stream<CarBrowserState> get carBrowser =>
      _raw.where((e) => e['type'] == 'carBrowser').map(CarBrowserState.fromMap);

  Future<CarBrowserState> currentCarBrowser() async {
    try {
      final m = await _method.invokeMethod<Map<Object?, Object?>>('carBrowser');
      return m == null ? const CarBrowserState() : CarBrowserState.fromMap(m);
    } on MissingPluginException {
      return const CarBrowserState();
    }
  }

  /// Browser history shared by phone and car (newest first). Each map: u (url), t (title), at (ms).
  Future<List<Map<String, Object?>>> browserHistory() async {
    try {
      final l = await _method.invokeMethod<List<Object?>>('browserHistory');
      return [for (final e in l ?? const []) Map<String, Object?>.from(e as Map)];
    } on MissingPluginException {
      return const [];
    }
  }

  /// Fires when the history changed on either side (phone or car).
  Stream<void> get browserHistoryChanged => _raw.where((e) => e['type'] == 'browserHistory').map((_) {});

  Future<void> recordBrowserHistory(String url, String title) =>
      _historyCall('recordBrowserHistory', {'url': url, 'title': title});
  Future<void> removeBrowserHistory(String url) => _historyCall('removeBrowserHistory', {'url': url});
  Future<void> clearBrowserHistory() => _historyCall('clearBrowserHistory', null);
  Future<void> importBrowserHistory(List<Map<String, Object?>> items) =>
      _historyCall('importBrowserHistory', {'items': items});

  Future<void> _historyCall(String m, Object? args) async {
    try {
      await _method.invokeMethod<void>(m, args);
    } on MissingPluginException {
      // no native side on this platform
    }
  }

  /// Opens a non-web link (tel:, mailto:, intent:, market:, ...) with the system. False if nothing handles it.
  Future<bool> openExternal(String url) async {
    try {
      return await _method.invokeMethod<bool>('openExternal', {'url': url}) ?? false;
    } on MissingPluginException {
      return false;
    }
  }

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
