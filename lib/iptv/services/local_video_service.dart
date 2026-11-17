import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:iptv_native/iptv_native.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../core/channel.dart';
import '../core/local_video.dart';
import '../core/shared_files.dart';
import 'channel_service.dart';

/// The local video library: files picked through the system document picker. Only the
/// persisted URI + metadata are stored; playback goes through the same native engine and
/// output selection as IPTV channels (the entries are registered with [ChannelService] so the
/// shared PlaybackController resolves them like channels).
class LocalVideoService extends ChangeNotifier {
  LocalVideoService(this._channels, this._files, [IptvNative? native]) : _native = native ?? IptvNative.instance;
  final ChannelService _channels;
  final SharedFiles _files;
  final IptvNative _native;
  static const _key = 'local_videos';

  List<LocalVideo> _videos = const [];
  List<LocalVideo> get videos => _videos;
  bool loaded = false;

  final Map<String, Future<Uint8List?>> _thumbs = {};

  Future<void> load() async {
    try {
      final raw = (await SharedPreferences.getInstance()).getString(_key);
      if (raw != null) {
        _videos = [for (final j in jsonDecode(raw) as List) LocalVideo.fromJson(Map<String, dynamic>.from(j as Map))];
      }
    } catch (_) {
      _videos = const [];
    }
    _register();
    loaded = true;
    notifyListeners();
  }

  void _register() {
    final list = [for (final v in _videos) v.toChannel()];
    _channels.setLocal(list);
    _publish(list);
  }

  /// Shares the library with the native side so Android Auto can browse it (local_videos.json).
  Future<void> _publish(List<Channel> list) async {
    try {
      await _files.writeString('local_videos.json', jsonEncode([for (final c in list) c.toJson()]));
      await _native.notifyDataChanged();
    } catch (_) {}
  }

  Future<void> _save() async {
    try {
      await (await SharedPreferences.getInstance()).setString(_key, jsonEncode([for (final v in _videos) v.toJson()]));
    } catch (_) {}
  }

  /// Opens the system picker and adds the chosen files. Returns how many were new.
  Future<int> pickAndAdd() async {
    final picked = await _native.pickVideos();
    final known = {for (final v in _videos) v.uri};
    final added = [
      for (final p in picked)
        if (!known.contains(p.uri))
          LocalVideo.create(uri: p.uri, name: p.name, durationMs: p.durationMs, sizeBytes: p.sizeBytes),
    ];
    if (added.isEmpty) return 0;
    _videos = [...added, ..._videos];
    _register();
    notifyListeners();
    await _save();
    return added.length;
  }

  Future<void> remove(LocalVideo v) async {
    _videos = [..._videos]..removeWhere((e) => e.id == v.id);
    _thumbs.remove(v.uri);
    _register();
    notifyListeners();
    await _save();
    await _native.releaseVideoUri(v.uri).catchError((Object _) {});
  }

  Future<Uint8List?> thumbnail(LocalVideo v) =>
      _thumbs.putIfAbsent(v.uri, () => _native.videoThumbnail(v.uri).catchError((Object _) => null));
}
