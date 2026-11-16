import 'dart:async';

import 'package:iptv_native/iptv_native.dart' show CommandOrigin;
import 'package:material_ui/material_ui.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../automotive/car_connection_service.dart';
import '../core/channel.dart';
import '../iptv_repository.dart';
import '../widgets/channel_tile.dart';
import '../widgets/filter_sheet.dart';
import 'settings_screen.dart';
import 'video_view.dart';

enum _View { all, favorites, recents, group }

/// The complete IPTV experience, embedded directly in the Home Screen:
/// now playing (inline video + controls), categories, search, channel list.
/// All state comes from [IPTVRepository], i.e. the same native player Android Auto uses.
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
  bool _pipSupported = false;
  bool? _lastPlaying;

  IPTVRepository get repo => widget.repo;

  @override
  void initState() {
    super.initState();
    repo.playback.addListener(_onPlayback);
    repo.settings.addListener(_onPlayback);
    repo.automotive.navigation.remoteSelection.addListener(_onRemoteSelection);
    repo.player.pipSupported().then((v) {
      if (mounted) setState(() => _pipSupported = v);
    }).catchError((_) {});
  }

  @override
  void dispose() {
    repo.playback.removeListener(_onPlayback);
    repo.settings.removeListener(_onPlayback);
    repo.automotive.navigation.remoteSelection.removeListener(_onRemoteSelection);
    _debounce?.cancel();
    repo.player.setAutoPip(false).catchError((_) {});
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

  /// Keep the screen on / auto-PiP only while a stream is actually playing.
  void _onPlayback() {
    final playing = repo.playback.isPlaying;
    if (playing == _lastPlaying) return;
    _lastPlaying = playing;
    if (playing && repo.settings.keepScreenOn) {
      WakelockPlus.enable();
    } else {
      WakelockPlus.disable();
    }
    repo.player.setAutoPip(playing && repo.settings.autoPip).catchError((_) {});
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
      listenable: Listenable.merge([repo, repo.playback, repo.favorites, repo.recents, repo.automotive, repo.parked]),
      builder: (context, _) {
        return Column(children: [
          if (repo.loadState == LoadState.loading) const LinearProgressIndicator(),
          if (repo.staleWarning != null)
            MaterialBanner(
              content: Text(repo.staleWarning!),
              actions: [TextButton(onPressed: () => repo.refresh(force: true), child: const Text('Retry'))],
            ),
          CarLinkPanel(repo: repo),
          NowPlayingCard(repo: repo, pipSupported: _pipSupported),
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

/// Currently playing channel: inline video, status and controls.
class NowPlayingCard extends StatelessWidget {
  const NowPlayingCard({super.key, required this.repo, required this.pipSupported});
  final IPTVRepository repo;
  final bool pipSupported;

  @override
  Widget build(BuildContext context) {
    final pc = repo.playback;
    final ch = pc.current;
    final st = pc.state;
    final theme = Theme.of(context);
    final parked = repo.parked.info;
    // Car displays: 64 dp targets with 24 dp spacing; phones keep the compact layout.
    final big = parked.automotive;
    final target = big ? 64.0 : 48.0;
    final gap = big ? 24.0 : 8.0;
    ButtonStyle? sized() => big ? IconButton.styleFrom(minimumSize: Size(target, target)) : null;

    if (ch == null || !pc.hasMedia) {
      return Card(
        margin: const EdgeInsets.fromLTRB(12, 8, 12, 4),
        child: const ListTile(
          leading: Icon(Icons.play_circle_outline, size: 36),
          title: Text('Nothing playing'),
          subtitle: Text('Pick a channel below to start watching.'),
        ),
      );
    }

    final status = pc.hasError
        ? 'Playback error'
        : pc.isBuffering
            ? 'Buffering…'
            : pc.isPlaying
                ? 'Live'
                : 'Paused';

    return Card(
      margin: const EdgeInsets.fromLTRB(12, 8, 12, 4),
      clipBehavior: Clip.antiAlias,
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        if (!parked.videoAllowed)
          AspectRatio(
            aspectRatio: 16 / 9,
            child: ColoredBox(
              color: Colors.black,
              child: Center(
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  const Icon(Icons.headphones, color: Colors.white70, size: 44),
                  const SizedBox(height: 8),
                  Text(
                    parked.playbackBlocked
                        ? 'Playback paused while driving'
                        : 'Audio only while driving',
                    style: const TextStyle(color: Colors.white70),
                  ),
                  const Text('Video returns when the vehicle is parked.',
                      style: TextStyle(color: Colors.white38, fontSize: 12)),
                ]),
              ),
            ),
          )
        else if (st.inPictureInPicture)
          const AspectRatio(aspectRatio: 16 / 9, child: ColoredBox(color: Colors.black, child: NativeVideoView()))
        else
          AspectRatio(
            aspectRatio: 16 / 9,
            child: Stack(fit: StackFit.expand, children: [
              const ColoredBox(color: Colors.black, child: NativeVideoView()),
              if (pc.isBuffering) const Center(child: CircularProgressIndicator(color: Colors.white)),
              if (pc.hasError)
                ColoredBox(
                  color: Colors.black54,
                  child: Center(
                    child: Column(mainAxisSize: MainAxisSize.min, children: [
                      const Icon(Icons.error_outline, color: Colors.redAccent, size: 40),
                      const Text('This channel cannot be played right now.', style: TextStyle(color: Colors.white70)),
                      TextButton(onPressed: pc.retry, child: const Text('Retry')),
                    ]),
                  ),
                ),
              if (pipSupported)
                Positioned(
                  top: 4,
                  right: 4,
                  child: IconButton(
                    tooltip: 'Picture in picture',
                    color: Colors.white,
                    icon: const Icon(Icons.picture_in_picture_alt),
                    onPressed: repo.player.enterPip,
                  ),
                ),
            ]),
          ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
          child: Row(children: [
            ChannelLogo(url: ch.logo, size: 44),
            const SizedBox(width: 12),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(ch.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.titleMedium),
                Text('$status · ${ch.group}', maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodySmall),
              ]),
            ),
            IconButton(
              tooltip: 'Favorite',
              icon: Icon(repo.favorites.isFavorite(ch.id) ? Icons.favorite : Icons.favorite_border),
              onPressed: () => repo.toggleFavorite(ch),
            ),
          ]),
        ),
        Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          IconButton(iconSize: big ? 40 : 32, style: sized(), tooltip: 'Previous channel', icon: const Icon(Icons.skip_previous), onPressed: pc.previous),
          SizedBox(width: gap),
          IconButton.filled(
            iconSize: big ? 40 : 34,
            style: sized(),
            tooltip: pc.isPlaying ? 'Pause' : 'Play',
            icon: Icon(pc.isPlaying ? Icons.pause : Icons.play_arrow),
            // Driving without background audio: play/pause is withdrawn natively too.
            onPressed: parked.playbackBlocked ? null : pc.togglePlay,
          ),
          SizedBox(width: gap),
          IconButton(iconSize: big ? 40 : 32, style: sized(), tooltip: 'Next channel', icon: const Icon(Icons.skip_next), onPressed: pc.next),
        ]),
        const SizedBox(height: 4),
      ]),
    );
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


/// Connected-car panel: platform-confirmed connection status, who last controlled playback,
/// and honest information about what the car display can and cannot show.
class CarLinkPanel extends StatelessWidget {
  const CarLinkPanel({super.key, required this.repo});
  final IPTVRepository repo;

  @override
  Widget build(BuildContext context) {
    final snap = repo.automotive.snapshot;
    final scheme = Theme.of(context).colorScheme;
    final connected = snap.link != CarLinkStatus.disconnected && snap.link != CarLinkStatus.checking;

    final (label, icon) = switch (snap.link) {
      CarLinkStatus.checking => ('Checking car connection…', Icons.sync),
      CarLinkStatus.disconnected => ('Not connected to a car', Icons.directions_car_outlined),
      CarLinkStatus.androidAuto => ('Connected to Android Auto', Icons.directions_car),
      CarLinkStatus.androidAutomotive => ('Connected to Android Automotive', Icons.directions_car),
      CarLinkStatus.carPlay => ('Connected to CarPlay', Icons.directions_car),
    };

    final origin = switch (snap.origin) {
      CommandOrigin.phone => 'this phone',
      CommandOrigin.car => 'the car',
      CommandOrigin.system => 'system controls (notification, Bluetooth, steering wheel)',
    };

    return Card(
      margin: const EdgeInsets.fromLTRB(12, 8, 12, 0),
      color: connected ? scheme.primaryContainer : null,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
        child: Row(children: [
          Icon(icon, color: connected ? scheme.onPrimaryContainer : null),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(label, style: Theme.of(context).textTheme.titleSmall),
              Text(
                connected
                    ? 'Car shows: ${snap.channelName ?? 'no channel yet'} · last control from $origin'
                    : snap.channelId != null
                        ? 'Not connected to a car · last control from $origin'
                        : 'Controls here work either way. Plug in or pair a car to sync them.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ]),
          ),
          if (connected && snap.channelId != null)
            TextButton(onPressed: repo.automotive.bridge.stop, child: const Text('Stop')),
          IconButton(
            tooltip: 'What can the car display show?',
            icon: const Icon(Icons.info_outline),
            onPressed: () => showDialog<void>(
              context: context,
              builder: (_) => AlertDialog(
                title: const Text('Phone and car'),
                content: const Text(
                  'The car shows a native, synchronized interface (channel list, categories, favorites, '
                  'recent, now playing with controls). Channel, play/pause, next/previous and category '
                  'changes made on either side are shared.\n\n'
                  'Video on the car screen is decided by the car and the system, not by this app. '
                  'On Android Auto it needs Google\'s parked video category (a beta: Android 17 phone, '
                  'compatible vehicle, early-access approval) and it only plays while parked. '
                  'On CarPlay it needs Apple\'s video entitlement and a supporting vehicle. '
                  'Otherwise the car plays audio only and video stays on the phone. The connection '
                  'status above is reported by Android / iOS and cannot be ended from the app.',
                ),
                actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('OK'))],
              ),
            ),
          ),
        ]),
      ),
    );
  }
}
