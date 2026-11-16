import 'dart:convert';

import 'package:material_ui/material_ui.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../core/channel.dart';

class PlaylistSource {
  const PlaylistSource(this.name, this.url);
  final String name;
  final String url;

  Map<String, String> toJson() => {'name': name, 'url': url};
  factory PlaylistSource.fromJson(Map<String, dynamic> j) =>
      PlaylistSource(j['name'] as String, j['url'] as String);
}

/// Persistent IPTV settings: theme, playlists, custom streams, playback preferences.
class IPTVSettingsService extends ChangeNotifier {
  static const defaultPlaylist =
      PlaylistSource('iptv-org (all channels)', 'https://iptv-org.github.io/iptv/index.m3u');

  static const accentColors = <String, Color>{
    'deepPurple': Colors.deepPurple, // the host app's original seed color
    'blue': Colors.blue,
    'red': Colors.red,
    'green': Colors.green,
    'purple': Colors.purple,
    'orange': Colors.orange,
  };

  late SharedPreferences _prefs;

  bool _dark = false;
  String _accent = 'deepPurple';
  List<PlaylistSource> _playlists = [defaultPlaylist];
  int _active = 0;
  List<Channel> _customStreams = [];
  int _cacheHours = 12;
  int _timeoutSeconds = 30;
  bool _autoPip = true;
  bool _keepScreenOn = true;

  bool get isDark => _dark;
  String get accentName => _accent;
  Color get accentColor => accentColors[_accent] ?? Colors.deepPurple;
  List<PlaylistSource> get playlists => List.unmodifiable(_playlists);
  PlaylistSource get activePlaylist => _playlists[_active];
  int get activeIndex => _active;
  List<Channel> get customStreams => List.unmodifiable(_customStreams);
  int get cacheHours => _cacheHours;
  int get timeoutSeconds => _timeoutSeconds;
  bool get autoPip => _autoPip;
  bool get keepScreenOn => _keepScreenOn;

  Future<void> load() async {
    _prefs = await SharedPreferences.getInstance();
    _dark = _prefs.getBool('iptv.dark') ?? false;
    _accent = _prefs.getString('iptv.accent') ?? 'deepPurple';
    _cacheHours = _prefs.getInt('iptv.cacheHours') ?? 12;
    _timeoutSeconds = _prefs.getInt('iptv.timeoutSeconds') ?? 30;
    _autoPip = _prefs.getBool('iptv.autoPip') ?? true;
    _keepScreenOn = _prefs.getBool('iptv.keepScreenOn') ?? true;
    try {
      final p = _prefs.getString('iptv.playlists');
      if (p != null) {
        _playlists = (jsonDecode(p) as List)
            .map((e) => PlaylistSource.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList();
      }
      final c = _prefs.getString('iptv.customStreams');
      if (c != null) {
        _customStreams = (jsonDecode(c) as List)
            .map((e) => Channel.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList();
      }
    } catch (_) {
      _playlists = [defaultPlaylist];
      _customStreams = [];
    }
    if (_playlists.isEmpty) _playlists = [defaultPlaylist];
    _active = (_prefs.getInt('iptv.activePlaylist') ?? 0).clamp(0, _playlists.length - 1);
  }

  Future<void> setTheme({bool? dark, String? accent}) async {
    _dark = dark ?? _dark;
    _accent = accent ?? _accent;
    await _prefs.setBool('iptv.dark', _dark);
    await _prefs.setString('iptv.accent', _accent);
    notifyListeners();
  }

  Future<void> setActivePlaylist(int index) async {
    _active = index.clamp(0, _playlists.length - 1);
    await _prefs.setInt('iptv.activePlaylist', _active);
    notifyListeners();
  }

  Future<void> addPlaylist(PlaylistSource source) async {
    _playlists = [..._playlists, source];
    await _savePlaylists();
    await setActivePlaylist(_playlists.length - 1);
  }

  Future<void> removePlaylist(int index) async {
    if (_playlists.length <= 1) return;
    _playlists = [..._playlists]..removeAt(index);
    await _savePlaylists();
    await setActivePlaylist(_active >= _playlists.length ? 0 : _active);
  }

  Future<void> _savePlaylists() => _prefs.setString(
      'iptv.playlists', jsonEncode(_playlists.map((p) => p.toJson()).toList()));

  Future<void> addCustomStream(String name, String url) async {
    _customStreams = [
      ..._customStreams,
      Channel.create(name: name, url: url, group: 'Custom'),
    ];
    await _saveCustom();
    notifyListeners();
  }

  Future<void> removeCustomStream(String id) async {
    _customStreams = _customStreams.where((c) => c.id != id).toList();
    await _saveCustom();
    notifyListeners();
  }

  Future<void> _saveCustom() => _prefs.setString(
      'iptv.customStreams', jsonEncode(_customStreams.map((c) => c.toJson()).toList()));

  Future<void> setPlayback({int? cacheHours, int? timeoutSeconds, bool? autoPip, bool? keepScreenOn}) async {
    _cacheHours = cacheHours ?? _cacheHours;
    _timeoutSeconds = timeoutSeconds ?? _timeoutSeconds;
    _autoPip = autoPip ?? _autoPip;
    _keepScreenOn = keepScreenOn ?? _keepScreenOn;
    await _prefs.setInt('iptv.cacheHours', _cacheHours);
    await _prefs.setInt('iptv.timeoutSeconds', _timeoutSeconds);
    await _prefs.setBool('iptv.autoPip', _autoPip);
    await _prefs.setBool('iptv.keepScreenOn', _keepScreenOn);
    notifyListeners();
  }
}
