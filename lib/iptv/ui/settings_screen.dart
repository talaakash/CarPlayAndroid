import 'package:flutter/foundation.dart' show kDebugMode;
import 'package:iptv_native/iptv_native.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../iptv_repository.dart';
import '../services/settings_service.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  Future<void> _addPlaylist(BuildContext context, IPTVRepository repo) async {
    final r = await _twoFieldDialog(context, 'Add playlist', 'Name', 'M3U URL (https://…)');
    if (r == null) return;
    await repo.settings.addPlaylist(PlaylistSource(r.$1, r.$2));
    await repo.refresh(force: true);
  }

  Future<void> _addStream(BuildContext context, IPTVRepository repo) async {
    final r = await _twoFieldDialog(context, 'Add stream', 'Channel name', 'Stream URL');
    if (r != null) await repo.addCustomStream(r.$1, r.$2);
  }

  Future<(String, String)?> _twoFieldDialog(BuildContext context, String title, String l1, String l2) {
    final a = TextEditingController();
    final b = TextEditingController();
    return showDialog<(String, String)>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(controller: a, decoration: InputDecoration(labelText: l1)),
          TextField(controller: b, decoration: InputDecoration(labelText: l2), keyboardType: TextInputType.url),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () {
              final uri = Uri.tryParse(b.text.trim());
              if (a.text.trim().isEmpty || uri == null || !(uri.scheme == 'http' || uri.scheme == 'https')) return;
              Navigator.pop(ctx, (a.text.trim(), b.text.trim()));
            },
            child: const Text('Add'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final repo = context.watch<IPTVRepository>();
    return ListenableBuilder(
      listenable: Listenable.merge([repo.settings, repo.parked]),
      builder: (context, _) {
        final s = repo.settings;
        return Scaffold(
          appBar: AppBar(title: const Text('IPTV settings')),
          body: ListView(children: [
            const _Header('Appearance'),
            SwitchListTile(title: const Text('Dark theme'), value: s.isDark, onChanged: (v) => s.setTheme(dark: v)),
            ListTile(
              title: const Text('Accent color'),
              subtitle: Wrap(spacing: 8, children: [
                for (final e in IPTVSettingsService.accentColors.entries)
                  ChoiceChip(
                    label: Text(e.key),
                    avatar: CircleAvatar(backgroundColor: e.value, radius: 8),
                    selected: s.accentName == e.key,
                    onSelected: (_) => s.setTheme(accent: e.key),
                  ),
              ]),
            ),
            const _Header('Playlists'),
            RadioGroup<int>(
              groupValue: s.activeIndex,
              onChanged: (v) => repo.selectPlaylist(v!),
              child: Column(children: [
                for (var i = 0; i < s.playlists.length; i++)
                  RadioListTile<int>(
                    value: i,
                    title: Text(s.playlists[i].name),
                    subtitle: Text(s.playlists[i].url, maxLines: 1, overflow: TextOverflow.ellipsis),
                    secondary: s.playlists.length > 1
                        ? IconButton(icon: const Icon(Icons.delete_outline), onPressed: () async { await s.removePlaylist(i); await repo.refresh(); })
                        : null,
                  ),
              ]),
            ),
            ListTile(leading: const Icon(Icons.add), title: const Text('Add playlist'), onTap: () => _addPlaylist(context, repo)),
            ListTile(
              title: const Text('Refresh playlist after'),
              trailing: DropdownButton<int>(
                value: [1, 6, 12, 24, 72, 168].contains(s.cacheHours) ? s.cacheHours : 12,
                items: [1, 6, 12, 24, 72, 168].map((h) => DropdownMenuItem(value: h, child: Text(h < 24 ? '$h h' : '${h ~/ 24} d'))).toList(),
                onChanged: (v) => s.setPlayback(cacheHours: v),
              ),
            ),
            ListTile(
              title: const Text('Download timeout'),
              trailing: DropdownButton<int>(
                value: [10, 20, 30, 60, 120].contains(s.timeoutSeconds) ? s.timeoutSeconds : 30,
                items: [10, 20, 30, 60, 120].map((t) => DropdownMenuItem(value: t, child: Text('$t s'))).toList(),
                onChanged: (v) => s.setPlayback(timeoutSeconds: v),
              ),
            ),
            const _Header('Custom streams'),
            for (final c in s.customStreams)
              ListTile(
                title: Text(c.name),
                subtitle: Text(c.url, maxLines: 1, overflow: TextOverflow.ellipsis),
                trailing: IconButton(icon: const Icon(Icons.delete_outline), onPressed: () => repo.removeCustomStream(c.id)),
              ),
            ListTile(leading: const Icon(Icons.add), title: const Text('Add stream URL'), onTap: () => _addStream(context, repo)),
            const _Header('Playback'),
            SwitchListTile(title: const Text('Picture-in-picture when leaving the app'), value: s.autoPip, onChanged: (v) => s.setPlayback(autoPip: v)),
            SwitchListTile(title: const Text('Keep screen on while watching'), value: s.keepScreenOn, onChanged: (v) => s.setPlayback(keepScreenOn: v)),
            const _Header('Car'),
            const _AllowUnknownSpeedTile(),
            if (kDebugMode) ...[
              const _Header('Developer: driving restriction'),
              ListTile(
                title: const Text('Simulate driving'),
                subtitle: const Text('Debug builds only. Runs the real native policy without a car.'),
                trailing: DropdownButton<String>(
                  value: repo.parked.info.simulated
                      ? (repo.parked.info.audioWhileDriving ? 'audioOk' : 'noAudio')
                      : 'off',
                  items: const [
                    DropdownMenuItem(value: 'off', child: Text('Parked (real signals)')),
                    DropdownMenuItem(value: 'audioOk', child: Text('Driving, audio allowed')),
                    DropdownMenuItem(value: 'noAudio', child: Text('Driving, no audio')),
                  ],
                  onChanged: (m) => IptvNative.instance.debugSimulateDriving(m ?? 'off'),
                ),
              ),
            ],
          ]),
        );
      },
    );
  }
}

class _Header extends StatelessWidget {
  const _Header(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
        child: Text(text, style: Theme.of(context).textTheme.titleSmall?.copyWith(color: Theme.of(context).colorScheme.primary)),
      );
}

/// Opt-in for cars / head units that do not report vehicle speed (release builds otherwise
/// show audio only on the car display).
class _AllowUnknownSpeedTile extends StatefulWidget {
  const _AllowUnknownSpeedTile();

  @override
  State<_AllowUnknownSpeedTile> createState() => _AllowUnknownSpeedTileState();
}

class _AllowUnknownSpeedTileState extends State<_AllowUnknownSpeedTile> {
  bool _value = false;

  @override
  void initState() {
    super.initState();
    IptvNative.instance.getAllowUnknownSpeed().then((v) {
      if (mounted) setState(() => _value = v);
    });
  }

  @override
  Widget build(BuildContext context) => SwitchListTile(
        title: const Text('Allow video when speed is unavailable'),
        subtitle: const Text(
            'Show video on the car display even if the car does not report its speed. Video is still hidden when the car reports it is moving.'),
        value: _value,
        onChanged: (v) {
          setState(() => _value = v);
          IptvNative.instance.setAllowUnknownSpeed(v);
        },
      );
}
