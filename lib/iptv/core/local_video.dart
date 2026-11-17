import 'channel.dart';

/// A video file the user picked from device storage. [uri] is a persisted `content://` URI
/// (Storage Access Framework); the file itself is never copied.
class LocalVideo {
  const LocalVideo({
    required this.id,
    required this.uri,
    required this.name,
    this.durationMs = -1,
    this.sizeBytes = -1,
  });

  final String id;
  final String uri;
  final String name;
  final int durationMs;
  final int sizeBytes;

  static const group = 'Local videos';

  factory LocalVideo.create({required String uri, required String name, int durationMs = -1, int sizeBytes = -1}) =>
      LocalVideo(id: Channel.stableId('local|$uri'), uri: uri, name: name, durationMs: durationMs, sizeBytes: sizeBytes);

  /// Name without the file extension, for display.
  String get title {
    final i = name.lastIndexOf('.');
    return i > 0 ? name.substring(0, i) : name;
  }

  /// The same playback model IPTV uses: the native engine recognises the content:// URL as local.
  Channel toChannel() => Channel(id: id, name: title, url: uri, group: group, country: '');

  Map<String, Object?> toJson() => {'id': id, 'uri': uri, 'name': name, 'durationMs': durationMs, 'sizeBytes': sizeBytes};

  factory LocalVideo.fromJson(Map<String, dynamic> j) => LocalVideo(
        id: j['id'] as String,
        uri: j['uri'] as String,
        name: j['name'] as String,
        durationMs: (j['durationMs'] as num?)?.toInt() ?? -1,
        sizeBytes: (j['sizeBytes'] as num?)?.toInt() ?? -1,
      );

  static String formatDuration(int ms) {
    if (ms <= 0) return '';
    final s = ms ~/ 1000;
    final h = s ~/ 3600, m = (s % 3600) ~/ 60, sec = s % 60;
    final two = sec.toString().padLeft(2, '0');
    return h > 0 ? '$h:${m.toString().padLeft(2, '0')}:$two' : '$m:$two';
  }

  static String formatSize(int bytes) {
    if (bytes <= 0) return '';
    const mb = 1024 * 1024;
    return bytes >= 1024 * mb ? '${(bytes / (1024 * mb)).toStringAsFixed(1)} GB' : '${(bytes / mb).round()} MB';
  }
}
