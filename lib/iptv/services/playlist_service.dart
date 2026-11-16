import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import '../core/channel.dart';
import '../core/m3u_parser.dart';
import '../core/shared_files.dart';

class PlaylistResult {
  const PlaylistResult(this.channels, {this.fromCache = false, this.stale = false, this.error});
  final List<Channel> channels;
  final bool fromCache;

  /// True when a refresh failed and cached data is being shown instead.
  final bool stale;
  final Object? error;
}

class PlaylistException implements Exception {
  PlaylistException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// Downloads, parses and caches the playlist. The cache (channels.json) is the same
/// file the Android Auto / CarPlay code reads, so playlists are never parsed or
/// downloaded twice.
class PlaylistService {
  PlaylistService(this._files, {http.Client? client}) : _client = client ?? http.Client();

  final SharedFiles _files;
  final http.Client _client;

  static const _channelsFile = 'channels.json';
  static const _metaFile = 'channels_meta.json';

  Future<PlaylistResult> load({
    required String url,
    required List<Channel> customStreams,
    required Duration timeout,
    required Duration maxAge,
    bool forceRefresh = false,
  }) async {
    final cached = await _readCache(url);

    if (!forceRefresh && cached != null && cached.age < maxAge) {
      if (!setEquals(cached.customIds, customStreams.map((c) => c.id).toSet())) {
        await _writeCache(url, cached.channels, customStreams: customStreams, keepFetchedAt: cached.fetchedAt);
      }
      return PlaylistResult(_merge(cached.channels, customStreams), fromCache: true);
    }

    try {
      final response = await _client.get(Uri.parse(url)).timeout(timeout);
      if (response.statusCode != 200) {
        throw PlaylistException('Server returned HTTP ${response.statusCode}');
      }
      final channels = await compute(_parseInIsolate, utf8.decode(response.bodyBytes, allowMalformed: true));
      if (channels.isEmpty) throw PlaylistException('Playlist contains no channels');
      await _writeCache(url, channels, customStreams: customStreams);
      return PlaylistResult(_merge(channels, customStreams));
    } catch (e) {
      final err = e is TimeoutException ? PlaylistException('Playlist download timed out') : e;
      if (cached != null) {
        return PlaylistResult(_merge(cached.channels, customStreams),
            fromCache: true, stale: true, error: err);
      }
      rethrow;
    }
  }

  /// Re-writes the shared cache with the current custom streams included (so the car
  /// UIs show them too) without downloading anything.
  Future<List<Channel>> applyCustomStreams(String url, List<Channel> customStreams) async {
    final cached = await _readCache(url);
    if (cached != null) {
      await _writeCache(url, cached.channels, customStreams: customStreams, keepFetchedAt: cached.fetchedAt);
    }
    return _merge(cached?.channels ?? const [], customStreams);
  }

  List<Channel> _merge(List<Channel> channels, List<Channel> custom) {
    if (custom.isEmpty) return channels;
    return [...custom, ...channels];
  }

  Future<_Cache?> _readCache(String url) async {
    final meta = await _files.readJson(_metaFile);
    final data = await _files.readJson(_channelsFile);
    if (meta is! Map || data is! List || meta['url'] != url) return null;
    final custom = (meta['customIds'] as List?)?.cast<String>().toSet() ?? <String>{};
    final channels = data
        .map((e) => Channel.fromJson(Map<String, dynamic>.from(e as Map)))
        .where((c) => !custom.contains(c.id))
        .toList();
    final fetched = DateTime.fromMillisecondsSinceEpoch((meta['fetchedAt'] as num?)?.toInt() ?? 0);
    return _Cache(channels, fetched, custom);
  }

  Future<void> _writeCache(String url, List<Channel> channels, {List<Channel> customStreams = const [], DateTime? keepFetchedAt}) async {
    final all = [...customStreams, ...channels];
    await _files.writeString(_channelsFile, jsonEncode(all.map((c) => c.toJson()).toList()));
    await _files.writeString(
      _metaFile,
      jsonEncode({
        'url': url,
        'fetchedAt': (keepFetchedAt ?? DateTime.now()).millisecondsSinceEpoch,
        'customIds': customStreams.map((c) => c.id).toList(),
      }),
    );
  }

  void dispose() => _client.close();
}

class _Cache {
  _Cache(this.channels, this.fetchedAt, this.customIds);
  final List<Channel> channels;
  final DateTime fetchedAt;
  final Set<String> customIds;
  Duration get age => DateTime.now().difference(fetchedAt);
}

List<Channel> _parseInIsolate(String body) => M3UParser.parse(body);
