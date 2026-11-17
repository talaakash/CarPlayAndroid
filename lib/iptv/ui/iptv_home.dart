import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../core/channel.dart';
import '../iptv_repository.dart';
import '../widgets/channel_tile.dart';
import '../widgets/filter_sheet.dart';
import 'local_videos_screen.dart';
import 'settings_screen.dart';

enum _View { all, favorites, recents, group }

/// The channel browser shown on the Home Screen: categories, search, channel list.
/// Playback lives in the mini-player (bottom) and the full-screen player. All state comes
/// from [IPTVRepository], i.e. the same native player and library Android Auto uses.
class IptvHomeBody extends StatefulWidget {
  const IptvHomeBody({super.key, required this.repo});
  final IPTVRepository repo;

  @override
  State<IptvHomeBody> createState() => _IptvHomeBodyState();
}

class _IptvHomeBodyState extends State<IptvHomeBody> {
  _View _view = _View.all;
  String? _group;
  String _search = '';
  Timer? _debounce;
  Set<String> _groups = {};
  Set<String> _countries = {};
  bool? _lastPlaying;

  IPTVRepository get repo => widget.repo;

  @override
  void initState() {
    super.initState();
    repo.playback.addListener(_onPlayback);
    repo.settings.addListener(_onPlayback);
    repo.automotive.navigation.remoteSelection.addListener(_onRemoteSelection);
  }

  @override
  void dispose() {
    repo.playback.removeListener(_onPlayback);
    repo.settings.removeListener(_onPlayback);
    repo.automotive.navigation.remoteSelection.removeListener(_onRemoteSelection);
    _debounce?.cancel();
    WakelockPlus.disable();
    super.dispose();
  }

  /// The car browsed a folder: follow it here. Not echoed back (native already stored it).
  void _onRemoteSelection() {
    final sel = repo.automotive.navigation.remoteSelection.value;
    if (sel == null || !mounted) return;
    setState(() {
      switch (sel.scope) {
        case 'favorites':
          _view = _View.favorites;
        case 'recents':
          _view = _View.recents;
        case 'all':
          _view = _View.all;
        default:
          if (sel.scope.startsWith('cat:')) {
            _view = _View.group;
            _group = sel.scope.substring(4);
          }
      }
    });
  }

  /// The user picked a view on the phone: the car's "Phone" list follows.
  void _selectView(_View v, {String? group}) {
    setState(() {
      _view = v;
      if (group != null) _group = group;
    });
    final (scope, label) = switch (v) {
      _View.all => ('all', 'All channels'),
      _View.favorites => ('favorites', 'Favorites'),
      _View.recents => ('recents', 'Recently watched'),
      _View.group => ('cat:$group', group ?? ''),
    };
    repo.automotive.navigation.selectFromPhone(scope, label).catchError((Object _) {});
  }

  /// Keep the screen on only while a stream is actually playing (auto-PiP is handled by the
  /// full-screen player, the only place the video is visible).
  void _onPlayback() {
    final playing = repo.playback.isPlaying;
    if (playing == _lastPlaying) return;
    _lastPlaying = playing;
    if (playing && repo.settings.keepScreenOn) {
      WakelockPlus.enable();
    } else {
      WakelockPlus.disable();
    }
  }

  List<Channel> get _visible {
    switch (_view) {
      case _View.favorites:
        return _filter(repo.favoriteChannels);
      case _View.recents:
        return _filter(repo.recentChannels);
      case _View.group:
        return repo.channels.query(search: _search, groups: {_group!}, countries: _countries);
      case _View.all:
        return repo.channels.query(search: _search, groups: _groups, countries: _countries);
    }
  }

  List<Channel> _filter(List<Channel> list) {
    final q = _search.trim().toLowerCase();
    return list.where((c) {
      if (_countries.isNotEmpty && !_countries.contains(c.country)) return false;
      return q.isEmpty || c.name.toLowerCase().contains(q);
    }).toList();
  }

  Future<void> _openFilter() async {
    final r = await showModalBottomSheet<(Set<String>, Set<String>)>(
      context: context,
      isScrollControlled: true,
      builder: (_) => FilterSheet(
        groups: repo.categories.groups,
        countries: repo.categories.countries,
        selectedGroups: _groups,
        selectedCountries: _countries,
      ),
    );
    if (r != null) {
      setState(() {
        _groups = r.$1;
        _countries = r.$2;
        if (_groups.isNotEmpty) _view = _View.all;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([repo, repo.playback, repo.favorites, repo.recents]),
      builder: (context, _) {
        return Column(children: [
          if (repo.loadState == LoadState.loading) const LinearProgressIndicator(),
          if (repo.staleWarning != null)
            MaterialBanner(
              content: Text(repo.staleWarning!),
              actions: [TextButton(onPressed: () => repo.refresh(force: true), child: const Text('Retry'))],
            ),
          _categoryChips(),
          _searchRow(),
          Expanded(child: _channels()),
        ]);
      },
    );
  }

  Widget _categoryChips() {
    final groups = repo.categories.groups;
    Widget chip(String label, bool selected, VoidCallback onTap, {IconData? icon}) => Padding(
          padding: const EdgeInsets.only(right: 8),
          child: ChoiceChip(
            avatar: icon == null ? null : Icon(icon, size: 18),
            label: Text(label),
            selected: selected,
            onSelected: (_) => onTap(),
          ),
        );
    return SizedBox(
      height: 48,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        itemCount: groups.length + 3,
        itemBuilder: (_, i) {
          if (i == 0) {
            return chip('All', _view == _View.all, () { _groups = {}; _selectView(_View.all); }, icon: Icons.live_tv);
          }
          if (i == 1) {
            return chip('Favorites (${repo.favorites.ids.length})', _view == _View.favorites, () => _selectView(_View.favorites), icon: Icons.favorite);
          }
          if (i == 2) {
            return chip('Recent', _view == _View.recents, () => _selectView(_View.recents), icon: Icons.history);
          }
          final g = groups[i - 3];
          return chip('$g (${repo.categories.countFor(g)})', _view == _View.group && _group == g,
              () => _selectView(_View.group, group: g));
        },
      ),
    );
  }

  Widget _searchRow() {
    final filtersOn = _groups.isNotEmpty || _countries.isNotEmpty;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 4),
      child: Row(children: [
        Expanded(
          child: TextField(
            decoration: InputDecoration(
              hintText: 'Search ${repo.channels.all.length} channels…',
              prefixIcon: const Icon(Icons.search),
              border: const OutlineInputBorder(),
              isDense: true,
            ),
            onChanged: (v) {
              _debounce?.cancel();
              _debounce = Timer(const Duration(milliseconds: 200), () => setState(() => _search = v));
            },
          ),
        ),
        const SizedBox(width: 8),
        Badge(
          isLabelVisible: filtersOn,
          label: Text('${_groups.length + _countries.length}'),
          child: IconButton.filledTonal(icon: const Icon(Icons.filter_list), tooltip: 'Filter by category / country', onPressed: _openFilter),
        ),
      ]),
    );
  }

  Widget _channels() {
    if (repo.loadState == LoadState.error && repo.channels.all.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.cloud_off, size: 48),
            const SizedBox(height: 8),
            const Text('Could not load the playlist'),
            Text(repo.errorMessage ?? '', textAlign: TextAlign.center, style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 8),
            FilledButton(onPressed: () => repo.refresh(force: true), child: const Text('Try again')),
          ]),
        ),
      );
    }
    if (repo.channels.all.isEmpty) {
      return const Center(child: Text('Loading channels…'));
    }
    final list = _visible;
    if (list.isEmpty) {
      return Center(
        child: Text(
          switch (_view) {
            _View.favorites => 'No favorites yet.\nTap the heart on a channel.',
            _View.recents => 'Nothing watched yet.',
            _ => 'No channels match.',
          },
          textAlign: TextAlign.center,
        ),
      );
    }
    final currentId = repo.playback.state.channelId;
    return Column(children: [
      if (_view == _View.recents)
        Align(
          alignment: Alignment.centerRight,
          child: TextButton.icon(onPressed: repo.recents.clear, icon: const Icon(Icons.delete_outline), label: const Text('Clear history')),
        ),
      Expanded(
        child: ListView.builder(
          itemCount: list.length,
          itemExtent: 72,
          itemBuilder: (_, i) {
            final c = list[i];
            return ChannelTile(
              channel: c,
              isFavorite: repo.favorites.isFavorite(c.id),
              isCurrent: c.id == currentId,
              isPlaying: c.id == currentId && repo.playback.isPlaying,
              onTap: () => repo.playback.play(c, list), // queue = the list the user is looking at
              onToggleFavorite: () => repo.toggleFavorite(c),
            );
          },
        ),
      ),
    ]);
  }
}

/// App bar actions: playlist selection, refresh, settings.
List<Widget> iptvAppBarActions(BuildContext context, IPTVRepository repo) {
  final s = repo.settings;
  return [
    PopupMenuButton<int>(
      tooltip: 'Playlist',
      icon: const Icon(Icons.playlist_play),
      onSelected: repo.selectPlaylist,
      itemBuilder: (_) => [
        for (var i = 0; i < s.playlists.length; i++)
          CheckedPopupMenuItem<int>(value: i, checked: i == s.activeIndex, child: Text(s.playlists[i].name)),
      ],
    ),
    IconButton(
      tooltip: 'Local videos',
      icon: const Icon(Icons.video_library),
      onPressed: () => LocalVideosScreen.open(context, repo),
    ),
    IconButton(
      tooltip: 'Reload playlist',
      icon: const Icon(Icons.refresh),
      onPressed: repo.loadState == LoadState.loading ? null : () => repo.refresh(force: true),
    ),
    IconButton(
      tooltip: 'IPTV settings',
      icon: const Icon(Icons.settings),
      onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const SettingsScreen())),
    ),
  ];
}

String iptvStatusLine(IPTVRepository repo) {
  switch (repo.loadState) {
    case LoadState.loading:
      return 'Loading playlist…';
    case LoadState.error:
      return 'Playlist failed to load';
    case LoadState.ready:
      return '${repo.settings.activePlaylist.name} · ${repo.channels.all.length} channels';
    case LoadState.idle:
      return 'Starting…';
  }
}
