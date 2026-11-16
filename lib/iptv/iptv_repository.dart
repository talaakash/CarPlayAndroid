import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:iptv_native/iptv_native.dart';

import 'automotive/automotive_navigation_service.dart';
import 'automotive/automotive_playback_bridge.dart';
import 'automotive/automotive_state_synchronizer.dart';
import 'automotive/car_connection_service.dart';
import 'automotive/parked_video_service.dart';
import 'core/channel.dart';
import 'core/shared_files.dart';
import 'services/category_service.dart';
import 'services/channel_service.dart';
import 'services/favorite_service.dart';
import 'services/player_service.dart';
import 'services/playback_controller.dart';
import 'services/playlist_service.dart';
import 'services/recently_watched_service.dart';
import 'services/settings_service.dart';

enum LoadState { idle, loading, ready, error }

/// Single entry point for all IPTV data. Owns the services so every surface (Flutter
/// UI, Android Auto, CarPlay) is backed by the same channels / favorites / recents.
class IPTVRepository extends ChangeNotifier {
  IPTVRepository._(this.files)
      : playlists = PlaylistService(files),
        favorites = FavoriteService(files),
        recents = RecentlyWatchedService(files) {
    player = IPTVPlayerService();
    playback = PlaybackController(player, channels);
    final native = IptvNative.instance;
    automotive = AutomotiveStateSynchronizer(
      connection: CarConnectionService(native),
      navigation: AutomotiveNavigationService(native),
      bridge: AutomotivePlaybackBridge(playback, native),
      playback: playback,
    );
  }

  final SharedFiles files;
  final PlaylistService playlists;
  final FavoriteService favorites;
  final RecentlyWatchedService recents;
  final ChannelService channels = ChannelService();
  final CategoryService categories = CategoryService();
  final IPTVSettingsService settings = IPTVSettingsService();
  late final IPTVPlayerService player;
  late final PlaybackController playback;
  late final AutomotiveStateSynchronizer automotive;
  final ParkedVideoService parked = ParkedVideoService();

  LoadState loadState = LoadState.idle;
  String? errorMessage;

  /// Set when a refresh failed and the cached playlist is shown.
  String? staleWarning;

  StreamSubscription<void>? _dataSub;
  Future<void>? _starting;

  static IPTVRepository? _instance;

  /// Creates (once) the repository. Throws if the native side is unavailable.
  static Future<IPTVRepository> create() async {
    final existing = _instance;
    if (existing != null) return existing;
    final dir = await IptvNative.instance.storageDirectory();
    final repo = IPTVRepository._(SharedFiles(dir));
    _instance = repo;
    return repo;
  }

  Future<void> start() => _starting ??= _start();

  Future<void> _start() async {
    await settings.load();
    automotive.start();
    parked.start();
    await favorites.load();
    await recents.reload();
    _dataSub = player.userDataChanges.listen((_) async {
      await recents.reload();
      await favorites.load(); // the car can toggle favorites too
      notifyListeners();
    });
    await refresh();
  }

  Future<void> refresh({bool force = false}) async {
    loadState = LoadState.loading;
    errorMessage = null;
    staleWarning = null;
    notifyListeners();
    try {
      final result = await playlists.load(
        url: settings.activePlaylist.url,
        customStreams: settings.customStreams,
        timeout: Duration(seconds: settings.timeoutSeconds),
        maxAge: Duration(hours: settings.cacheHours),
        forceRefresh: force,
      );
      _apply(result.channels);
      if (result.stale) staleWarning = 'Could not refresh the playlist (${result.error}). Showing saved channels.';
      loadState = LoadState.ready;
    } catch (e) {
      loadState = LoadState.error;
      errorMessage = e.toString();
    }
    await IptvNative.instance.notifyDataChanged();
    notifyListeners();
  }

  void _apply(List<Channel> list) {
    channels.setChannels(list);
    categories.rebuild(list);
  }

  Future<void> selectPlaylist(int index) async {
    await settings.setActivePlaylist(index);
    await refresh();
  }

  Future<void> addCustomStream(String name, String url) async {
    await settings.addCustomStream(name, url);
    _apply(await playlists.applyCustomStreams(settings.activePlaylist.url, settings.customStreams));
    await IptvNative.instance.notifyDataChanged();
    notifyListeners();
  }

  Future<void> removeCustomStream(String id) async {
    await settings.removeCustomStream(id);
    _apply(await playlists.applyCustomStreams(settings.activePlaylist.url, settings.customStreams));
    await IptvNative.instance.notifyDataChanged();
    notifyListeners();
  }

  List<Channel> get favoriteChannels => channels.byIds(favorites.ids);
  List<Channel> get recentChannels => channels.byIds(recents.ids);

  Future<void> toggleFavorite(Channel c) async {
    await favorites.toggle(c.id);
    notifyListeners();
  }

  @override
  void dispose() {
    _dataSub?.cancel();
    automotive.dispose();
    parked.dispose();
    playback.dispose();
    playlists.dispose();
    super.dispose();
  }
}
